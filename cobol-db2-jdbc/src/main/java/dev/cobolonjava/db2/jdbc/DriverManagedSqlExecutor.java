package dev.cobolonjava.db2.jdbc;

import dev.cobolonjava.db2.CursorHoldStrategy;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlCaFidelityMatrix;
import dev.cobolonjava.db2.SqlDiagnostic;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.SqlHostVariable;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.db2.SqlOutcome;
import dev.cobolonjava.db2.SqlPlan;
import dev.cobolonjava.db2.SqlValueCodec;
import dev.cobolonjava.db2.SqlValueDescriptor;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.runtime.interop.CobolSession;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Task専用Db2 connection lease上で全SQLとWITH HOLD cursorを実行するadapter。 */
public final class DriverManagedSqlExecutor implements SqlExecutorPort {

    private static final int MAX_DIAGNOSTICS = 64;

    private final SqlValueCodec codec;
    private final Map<CobolSession, Map<String, NativeCursor>> cursors =
            new IdentityHashMap<>();

    public DriverManagedSqlExecutor() {
        this(new SqlValueCodec());
    }

    DriverManagedSqlExecutor(SqlValueCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public Db2ExecutionProfile profile() {
        return Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD;
    }

    @Override
    public SqlOutcome execute(
            SqlPlan plan, SqlBindings bindings, CobolSession session, UnitOfWork unitOfWork) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(session, "session");
        session.verifyUsable();
        DriverManagedJdbcUnitOfWork nativeUnit = requireNativeUnit(unitOfWork);
        requireSupported(plan, bindings);

        return switch (plan.operation()) {
            case OPEN_CURSOR -> openCursor(plan, bindings, session, nativeUnit);
            case FETCH_CURSOR -> fetchCursor(plan, bindings, session, nativeUnit);
            case CLOSE_CURSOR -> closeCursor(plan, session, nativeUnit);
            case INSERT, UPDATE, DELETE, SELECT_ONE ->
                    executeStatement(plan, bindings, nativeUnit);
        };
    }

    private SqlOutcome executeStatement(
            SqlPlan plan, SqlBindings bindings, DriverManagedJdbcUnitOfWork unit) {
        Connection connection = unit.connection();
        try {
            connection.clearWarnings();
            try (PreparedStatement statement = connection.prepareStatement(plan.normalizedSql())) {
                statement.setQueryTimeout(unit.queryTimeoutSeconds());
                bindInputs(statement, bindings);
                return switch (plan.operation()) {
                    case INSERT, UPDATE, DELETE -> executeUpdate(connection, statement);
                    case SELECT_ONE -> executeSelectOne(connection, statement, bindings);
                    case OPEN_CURSOR, FETCH_CURSOR, CLOSE_CURSOR ->
                            throw new AssertionError("cursor operation passed statement dispatch");
                };
            }
        } catch (SQLException failure) {
            discardOnConnectionFailure(unit, failure);
            return failureOutcome(failure);
        }
    }

    private static DriverManagedJdbcUnitOfWork requireNativeUnit(UnitOfWork unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        if (!(unitOfWork instanceof DriverManagedJdbcUnitOfWork nativeUnit)) {
            throw new Db2ProfileMismatchException(
                    "driver-managed SQL executor requires its native JDBC UOW");
        }
        return nativeUnit;
    }

    private static void requireSupported(SqlPlan plan, SqlBindings bindings) {
        if (!"DB2".equalsIgnoreCase(plan.dialect())) {
            throw new IllegalArgumentException(
                    "driver-managed Db2 SQL executor does not accept dialect " + plan.dialect());
        }
        if (plan.operation() == SqlOperation.OPEN_CURSOR) {
            if (plan.cursorOptions().scrollable() || plan.cursorOptions().sensitive()
                    || plan.cursorOptions().forUpdate()
                    || plan.cursorOptions().returnsLobLocator()) {
                throw new UnsupportedOperationException(
                        "scrollable, sensitive, updateable, and LOB locator cursors are not implemented");
            }
            if (plan.cursorOptions().withHold()
                    && plan.cursorOptions().holdStrategy()
                    != CursorHoldStrategy.DB2_DRIVER_MANAGED_HOLD) {
                throw new UnsupportedOperationException(
                        "native WITH HOLD requires DB2_DRIVER_MANAGED_HOLD strategy: "
                                + plan.cursorOptions().cursorName());
            }
        }
        if (plan.operation() != SqlOperation.SELECT_ONE
                && plan.operation() != SqlOperation.FETCH_CURSOR
                && bindings.values().stream().anyMatch(value -> value.mode().isOutput())) {
            throw new IllegalArgumentException(
                    "only SELECT_ONE and FETCH may declare output host variables");
        }
        if (plan.operation() == SqlOperation.FETCH_CURSOR
                && bindings.values().stream().anyMatch(value -> value.mode().isInput())) {
            throw new IllegalArgumentException("FETCH must not declare input host variables");
        }
        if (plan.operation() == SqlOperation.CLOSE_CURSOR && !bindings.values().isEmpty()) {
            throw new IllegalArgumentException("CLOSE must not declare host variables");
        }
    }

    private SqlOutcome openCursor(
            SqlPlan plan, SqlBindings bindings, CobolSession session,
            DriverManagedJdbcUnitOfWork unit) {
        String name = cursorName(plan);
        requireCursorAbsent(session, name);
        Connection connection = unit.connection();
        PreparedStatement statement = null;
        ResultSet result = null;
        try {
            connection.clearWarnings();
            int holdability = plan.cursorOptions().withHold()
                    ? ResultSet.HOLD_CURSORS_OVER_COMMIT
                    : ResultSet.CLOSE_CURSORS_AT_COMMIT;
            statement = connection.prepareStatement(plan.normalizedSql(),
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, holdability);
            statement.setQueryTimeout(unit.queryTimeoutSeconds());
            bindInputs(statement, bindings);
            result = statement.executeQuery();
            List<SqlDiagnostic> diagnostics = warnings(connection, statement, result);
            NativeCursor cursor = new NativeCursor(name, plan, session,
                    unit.resourceLeaseId(), connection, statement, result);
            addCursor(cursor);
            try {
                unit.registerResource(cursor, plan.cursorOptions().withHold());
            } catch (RuntimeException | Error failure) {
                removeCursor(cursor);
                closeJdbc(result, statement, failure);
                throw failure;
            }
            return new SqlOutcome(0, "00000", 0, diagnostics, false,
                    SqlCaFidelityMatrix.basic(true));
        } catch (SQLException failure) {
            closeJdbc(result, statement, failure);
            discardOnConnectionFailure(unit, failure);
            return failureOutcome(failure);
        }
    }

    private SqlOutcome fetchCursor(
            SqlPlan plan, SqlBindings bindings, CobolSession session,
            DriverManagedJdbcUnitOfWork unit) {
        NativeCursor cursor = requireCursor(plan, session, unit);
        List<SqlHostVariable> outputs = bindings.values().stream()
                .filter(value -> value.mode().isOutput()).toList();
        try {
            if (!cursor.result.next()) {
                return new SqlOutcome(100, "02000", 0,
                        warnings(cursor.connection, cursor.statement, cursor.result), false,
                        SqlCaFidelityMatrix.derivedSqlCode(true));
            }
            int columns = cursor.result.getMetaData().getColumnCount();
            if (columns != outputs.size()) {
                throw new IllegalArgumentException(
                        "FETCH output count is " + outputs.size()
                                + ", result column count is " + columns);
            }
            List<SqlValueCodec.EncodedOutput> encoded = new ArrayList<>(columns);
            for (int column = 1; column <= columns; column++) {
                SqlHostVariable output = outputs.get(column - 1);
                encoded.add(codec.encodeOutput(output,
                        readColumn(cursor.result, column, output.descriptor())));
            }
            for (int index = 0; index < outputs.size(); index++) {
                codec.applyOutput(outputs.get(index), encoded.get(index));
            }
            return new SqlOutcome(0, "00000", 1,
                    warnings(cursor.connection, cursor.statement, cursor.result), false,
                    SqlCaFidelityMatrix.basic(true));
        } catch (SQLException failure) {
            discardOnConnectionFailure(unit, failure);
            return failureOutcome(failure);
        }
    }

    private SqlOutcome closeCursor(
            SqlPlan plan, CobolSession session, DriverManagedJdbcUnitOfWork unit) {
        NativeCursor cursor = requireCursor(plan, session, unit);
        List<SqlDiagnostic> diagnostics;
        try {
            diagnostics = warnings(cursor.connection, cursor.statement, cursor.result);
        } catch (SQLException failure) {
            discardOnConnectionFailure(unit, failure);
            return failureOutcome(failure);
        }
        unit.unregisterResource(cursor);
        try {
            cursor.close();
            return new SqlOutcome(0, "00000", 0, diagnostics, false,
                    SqlCaFidelityMatrix.basic(true));
        } catch (SQLException failure) {
            unit.markConnectionUnusable();
            return failureOutcome(failure);
        }
    }

    private static void discardOnConnectionFailure(
            DriverManagedJdbcUnitOfWork unit, SQLException failure) {
        Set<SQLException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLException current = failure; current != null;
                current = current.getNextException()) {
            if (!seen.add(current)) {
                return;
            }
            String state = current.getSQLState();
            if (state != null && state.toUpperCase(Locale.ROOT).startsWith("08")) {
                unit.markConnectionUnusable();
                return;
            }
        }
    }

    private void bindInputs(PreparedStatement statement, SqlBindings bindings)
            throws SQLException {
        int parameter = 1;
        for (SqlHostVariable variable : bindings.values()) {
            if (!variable.mode().isInput()) {
                continue;
            }
            Object value = codec.toJdbcValue(variable);
            if (value == null) {
                statement.setNull(parameter++, jdbcType(variable.descriptor()));
            } else if (value instanceof String text) {
                statement.setString(parameter++, text);
            } else if (value instanceof BigDecimal decimal) {
                statement.setBigDecimal(parameter++, decimal);
            } else {
                throw new AssertionError("unexpected codec input " + value.getClass());
            }
        }
    }

    private static int jdbcType(SqlValueDescriptor descriptor) {
        return switch (descriptor) {
            case SqlValueDescriptor.FixedCharacter ignored -> Types.CHAR;
            case SqlValueDescriptor.PackedDecimal ignored -> Types.DECIMAL;
            case SqlValueDescriptor.ZonedDecimal ignored -> Types.DECIMAL;
            case SqlValueDescriptor.BinaryInteger ignored -> Types.BIGINT;
        };
    }

    private static SqlOutcome executeUpdate(
            Connection connection, PreparedStatement statement) throws SQLException {
        long count = statement.executeLargeUpdate();
        return new SqlOutcome(0, "00000", count,
                warnings(connection, statement, null), false,
                SqlCaFidelityMatrix.basic(true));
    }

    private SqlOutcome executeSelectOne(
            Connection connection, PreparedStatement statement, SqlBindings bindings)
            throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return new SqlOutcome(100, "02000", 0,
                        warnings(connection, statement, result), false,
                        SqlCaFidelityMatrix.derivedSqlCode(true));
            }
            List<SqlHostVariable> outputs = bindings.values().stream()
                    .filter(value -> value.mode().isOutput()).toList();
            int columns = result.getMetaData().getColumnCount();
            if (columns != outputs.size()) {
                throw new IllegalArgumentException(
                        "SELECT output count is " + outputs.size()
                                + ", result column count is " + columns);
            }
            List<SqlValueCodec.EncodedOutput> encoded = new ArrayList<>(columns);
            for (int column = 1; column <= columns; column++) {
                SqlHostVariable output = outputs.get(column - 1);
                encoded.add(codec.encodeOutput(output,
                        readColumn(result, column, output.descriptor())));
            }
            if (result.next()) {
                return new SqlOutcome(-811, "21000", -1,
                        List.of(new SqlDiagnostic(-811, "21000", "FETCH",
                                "DERIVED-CARDINALITY-MORE-THAN-ONE")), false,
                        SqlCaFidelityMatrix.derivedSqlCode(false));
            }
            for (int index = 0; index < outputs.size(); index++) {
                codec.applyOutput(outputs.get(index), encoded.get(index));
            }
            return new SqlOutcome(0, "00000", 1,
                    warnings(connection, statement, result), false,
                    SqlCaFidelityMatrix.basic(true));
        }
    }

    private static Object readColumn(
            ResultSet result, int column, SqlValueDescriptor descriptor) throws SQLException {
        Object value = switch (descriptor) {
            case SqlValueDescriptor.FixedCharacter ignored -> result.getString(column);
            case SqlValueDescriptor.PackedDecimal ignored -> result.getBigDecimal(column);
            case SqlValueDescriptor.ZonedDecimal ignored -> result.getBigDecimal(column);
            case SqlValueDescriptor.BinaryInteger ignored -> result.getBigDecimal(column);
        };
        return result.wasNull() ? null : value;
    }

    private static List<SqlDiagnostic> warnings(
            Connection connection, PreparedStatement statement, ResultSet result)
            throws SQLException {
        List<SqlDiagnostic> diagnostics = new ArrayList<>();
        addWarnings(diagnostics, connection.getWarnings(), "CONNECTION");
        addWarnings(diagnostics, statement.getWarnings(), "STATEMENT");
        if (result != null) {
            addWarnings(diagnostics, result.getWarnings(), "RESULT_SET");
        }
        return List.copyOf(diagnostics);
    }

    private static void addWarnings(
            List<SqlDiagnostic> target, SQLWarning warning, String phase) {
        Set<SQLWarning> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLWarning current = warning; current != null;
                current = current.getNextWarning()) {
            if (target.size() >= MAX_DIAGNOSTICS || !seen.add(current)) {
                break;
            }
            String state = sqlState(current.getSQLState());
            target.add(new SqlDiagnostic(current.getErrorCode(), state, phase,
                    "JDBC-" + state + "-" + current.getErrorCode()));
        }
    }

    private static SqlOutcome failureOutcome(SQLException failure) {
        List<SqlDiagnostic> diagnostics = new ArrayList<>();
        Set<SQLException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLException current = failure; current != null;
                current = current.getNextException()) {
            if (diagnostics.size() >= MAX_DIAGNOSTICS || !seen.add(current)) {
                break;
            }
            String state = sqlState(current.getSQLState());
            diagnostics.add(new SqlDiagnostic(current.getErrorCode(), state, "EXECUTE",
                    "JDBC-" + state + "-" + current.getErrorCode()));
        }
        return new SqlOutcome(failure.getErrorCode(), sqlState(failure.getSQLState()), -1,
                diagnostics, false, SqlCaFidelityMatrix.basic(false));
    }

    private static String sqlState(String state) {
        if (state == null) {
            return "HY000";
        }
        String normalized = state.toUpperCase(Locale.ROOT);
        return normalized.matches("[0-9A-Z]{5}") ? normalized : "HY000";
    }

    private static String cursorName(SqlPlan plan) {
        return plan.cursorOptions().cursorName().toUpperCase(Locale.ROOT);
    }

    private synchronized void requireCursorAbsent(CobolSession session, String name) {
        Map<String, NativeCursor> sessionCursors = cursors.get(session);
        if (sessionCursors != null && sessionCursors.containsKey(name)) {
            throw new IllegalStateException("cursor is already open: " + name);
        }
    }

    private synchronized void addCursor(NativeCursor cursor) {
        Map<String, NativeCursor> sessionCursors = cursors.computeIfAbsent(
                cursor.session, ignored -> new HashMap<>());
        if (sessionCursors.putIfAbsent(cursor.name, cursor) != null) {
            throw new IllegalStateException("cursor is already open: " + cursor.name);
        }
    }

    private synchronized NativeCursor requireCursor(
            SqlPlan plan, CobolSession session, DriverManagedJdbcUnitOfWork unit) {
        String name = cursorName(plan);
        Map<String, NativeCursor> sessionCursors = cursors.get(session);
        NativeCursor cursor = sessionCursors == null ? null : sessionCursors.get(name);
        if (cursor == null) {
            throw new IllegalStateException("cursor is not open: " + name);
        }
        ResourceLeaseId leaseId = unit.resourceLeaseId();
        if (!cursor.leaseId.equals(leaseId) || cursor.connection != unit.connection()) {
            throw new Db2ProfileMismatchException(
                    "cursor belongs to a different native connection lease: " + name);
        }
        if (!cursor.plan.cursorOptions().equals(plan.cursorOptions())) {
            throw new IllegalArgumentException("cursor options differ from OPEN for " + name);
        }
        return cursor;
    }

    private synchronized void removeCursor(NativeCursor cursor) {
        Map<String, NativeCursor> sessionCursors = cursors.get(cursor.session);
        if (sessionCursors == null || sessionCursors.get(cursor.name) != cursor) {
            return;
        }
        sessionCursors.remove(cursor.name);
        if (sessionCursors.isEmpty()) {
            cursors.remove(cursor.session);
        }
    }

    private static void closeJdbc(
            ResultSet result, PreparedStatement statement, Throwable primary) {
        if (result != null) {
            try {
                result.close();
            } catch (SQLException cleanup) {
                primary.addSuppressed(cleanup);
            }
        }
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException cleanup) {
                primary.addSuppressed(cleanup);
            }
        }
    }

    private final class NativeCursor implements AutoCloseable {

        private final String name;
        private final SqlPlan plan;
        private final CobolSession session;
        private final ResourceLeaseId leaseId;
        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet result;
        private boolean closed;

        private NativeCursor(
                String name, SqlPlan plan, CobolSession session, ResourceLeaseId leaseId,
                Connection connection, PreparedStatement statement, ResultSet result) {
            this.name = name;
            this.plan = plan;
            this.session = session;
            this.leaseId = leaseId;
            this.connection = connection;
            this.statement = statement;
            this.result = result;
        }

        @Override
        public void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            removeCursor(this);
            SQLException failure = null;
            try {
                result.close();
            } catch (SQLException problem) {
                failure = problem;
            }
            try {
                statement.close();
            } catch (SQLException problem) {
                if (failure == null) {
                    failure = problem;
                } else {
                    failure.addSuppressed(problem);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
