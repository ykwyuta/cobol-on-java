package dev.cobolonjava.db2;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.TruncMode;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 生成コードが EXEC SQL に使う runtime 操作 (要件 FR-150, FR-151)。
 *
 * <p>host variable の形は翻訳時に決めて整数の組で渡す。実行時に項目の宣言を読み直さない。
 * 結果は SQLCA へ書き戻す。JDBC から得られない field を推測で埋めない (SQLCA fidelity)。
 */
public final class Db2RuntimeOps {

    /** SQLCA の長さ。 */
    public static final int SQLCA_LENGTH = 136;

    /** shape の 1 組の長さ: 種類、桁数、小数桁、付加情報。 */
    public static final int SHAPE_WIDTH = 4;
    public static final int CHARACTER = 0;
    public static final int PACKED = 1;
    public static final int ZONED = 2;
    public static final int BINARY = 3;

    private static final int SQLCODE_OFFSET = 12;
    private static final int SQLERRML_OFFSET = 16;
    private static final int SQLERRMC_OFFSET = 18;
    private static final int SQLERRMC_LENGTH = 70;
    private static final int SQLERRP_OFFSET = 88;
    private static final int SQLERRD_OFFSET = 96;
    private static final int SQLWARN_OFFSET = 120;
    private static final int SQLWARN_LENGTH = 11;
    private static final int SQLSTATE_OFFSET = 131;

    private Db2RuntimeOps() {
    }

    /**
     * SQL を 1 文実行する。
     *
     * @param values     入力の host variable を先に、出力をあとに並べたもの
     * @param indicators 各 host variable の null 標識。無ければ要素が null
     * @param shape      各 host variable の {@link #SHAPE_WIDTH} 個組
     * @param inputCount 入力の数
     */
    public static void execute(ProgramContext context, String statementId, int operation,
                               String sql, String cursorName, boolean withHold,
                               DataView[] values, DataView[] indicators, int[] shape,
                               int inputCount, DataView sqlca) {
        Objects.requireNonNull(context, "context");
        SqlOperation op = SqlOperation.values()[operation];
        boolean cursor = op == SqlOperation.OPEN_CURSOR || op == SqlOperation.FETCH_CURSOR
                || op == SqlOperation.CLOSE_CURSOR;
        // WITH HOLD は承認された方針を持つまで実行しない (設計 77 §5.5)
        CursorOptions options = cursor
                ? new CursorOptions(cursorName, withHold,
                        withHold ? CursorHoldStrategy.REJECT_UNVERIFIED : CursorHoldStrategy.NOT_HELD,
                        false, false, false, false)
                : CursorOptions.none();
        SqlPlan plan = new SqlPlan(statementId, "DB2", op, sql, options);
        SqlBindings bindings = bindingsOf(context.codePage(), values, indicators, shape, inputCount);
        Db2Execution execution = context.service(Db2Execution.class);
        SqlOutcome outcome = execution.runtime().execute(plan, bindings, execution.session());
        writeSqlca(context.codePage(), sqlca, outcome);
    }

    /** SQL の COMMIT。CICS task では SYNCPOINT を使う。 */
    public static void commit(ProgramContext context, DataView sqlca) {
        context.service(Db2Execution.class).runtime().commit();
        writeSqlca(context.codePage(), sqlca, SqlOutcome.success(-1));
    }

    /** SQL の ROLLBACK。 */
    public static void rollback(ProgramContext context, DataView sqlca) {
        context.service(Db2Execution.class).runtime()
                .rollback(new RollbackReason(RollbackReason.Kind.EXPLICIT, "SQL-ROLLBACK"));
        writeSqlca(context.codePage(), sqlca, SqlOutcome.success(-1));
    }

    static SqlBindings bindingsOf(CodePage codePage, DataView[] values, DataView[] indicators,
                                  int[] shape, int inputCount) {
        if (values.length != indicators.length || shape.length != values.length * SHAPE_WIDTH
                || inputCount < 0 || inputCount > values.length) {
            throw new IllegalArgumentException("SQL host variable arrays do not agree");
        }
        List<SqlHostVariable> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            int base = i * SHAPE_WIDTH;
            int digits = shape[base + 1];
            int scale = shape[base + 2];
            int extra = shape[base + 3];
            SqlValueDescriptor descriptor = switch (shape[base]) {
                case CHARACTER -> new SqlValueDescriptor.FixedCharacter(codePage);
                case PACKED -> new SqlValueDescriptor.PackedDecimal(digits, scale, extra == 1);
                case ZONED -> new SqlValueDescriptor.ZonedDecimal(digits, scale,
                        SignPosition.values()[extra], codePage);
                case BINARY -> new SqlValueDescriptor.BinaryInteger(digits, scale,
                        extra == 1 ? TruncMode.BIN : TruncMode.STD);
                default -> throw new IllegalArgumentException("unknown SQL host variable kind " + shape[base]);
            };
            out.add(i < inputCount
                    ? SqlHostVariable.input(values[i], descriptor, indicators[i])
                    : SqlHostVariable.output(values[i], descriptor, indicators[i]));
        }
        return new SqlBindings(out);
    }

    /**
     * SQLCA を書き戻す。SQLCAID、SQLCABC、SQLCODE、SQLSTATE は必ず置き、SQLERRD(3) は
     * 行数が得られたときだけ置く。得られない field は空白と 0 にする (暫定判断 P-121)。
     */
    static void writeSqlca(CodePage codePage, DataView sqlca, SqlOutcome outcome) {
        Objects.requireNonNull(sqlca, "sqlca");
        if (sqlca.length() != SQLCA_LENGTH) {
            throw new IllegalArgumentException("SQLCA must be " + SQLCA_LENGTH + " bytes, got "
                    + sqlca.length());
        }
        byte[] area = new byte[SQLCA_LENGTH];
        byte space = codePage.space();
        System.arraycopy(codePage.encode("SQLCA   "), 0, area, 0, 8);
        putFullword(area, 8, SQLCA_LENGTH);
        putFullword(area, SQLCODE_OFFSET, outcome.sqlCode());
        area[SQLERRML_OFFSET] = 0;
        area[SQLERRML_OFFSET + 1] = 0;
        Arrays.fill(area, SQLERRMC_OFFSET, SQLERRMC_OFFSET + SQLERRMC_LENGTH, space);
        Arrays.fill(area, SQLERRP_OFFSET, SQLERRP_OFFSET + 8, space);
        if (outcome.rowCount() >= 0) {
            putFullword(area, SQLERRD_OFFSET + 2 * 4,
                    (int) Math.min(Integer.MAX_VALUE, outcome.rowCount()));
        }
        Arrays.fill(area, SQLWARN_OFFSET, SQLWARN_OFFSET + SQLWARN_LENGTH, space);
        System.arraycopy(codePage.encode(outcome.sqlState()), 0, area, SQLSTATE_OFFSET, 5);
        sqlca.setBytes(area);
    }

    private static void putFullword(byte[] area, int offset, int value) {
        area[offset] = (byte) (value >>> 24);
        area[offset + 1] = (byte) (value >>> 16);
        area[offset + 2] = (byte) (value >>> 8);
        area[offset + 3] = (byte) value;
    }
}
