package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.TruncMode;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.db2.Db2RuntimeOps;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.runtime.interop.ProgramParameter;
import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.file.DataSet;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 生成クラスから呼ばれる PL/I の言語ランタイム。 */
public final class PliRuntime {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private PliRuntime() {
    }

    /** 構文検査済みソースを実行する。 */
    public static void execute(String fileName, String source, ProgramContext context,
                               DataView[] arguments, ClassLoader loader) {
        PliSyntax.ParseResult parsed = PliSyntax.parse(fileName, source);
        if (!parsed.succeeded()) {
            throw new PliExecutionException(parsed.diagnostics().get(0).toString());
        }
        new Executor(parsed.program(), context, arguments, loader).run();
    }

    /** PL/I の入口引数は参照渡しであり、BASED 宣言が実際の範囲を決める。 */
    public static ProgramSignature signature(String fileName, String source) {
        PliSyntax.ParseResult parsed = PliSyntax.parse(fileName, source);
        if (!parsed.succeeded()) {
            return null;
        }
        List<ProgramParameter> parameters = parsed.program().parameters().stream()
                .map(name -> new ProgramParameter(name, 0, Short.MAX_VALUE,
                        ProgramParameter.Presence.REQUIRED,
                        ProgramParameter.PassingMode.REFERENCE,
                        ProgramParameter.Direction.INOUT, "pli-based-v1"))
                .toList();
        return ProgramSignature.of(parsed.program().name(), parameters);
    }

    /** 配備カタログと生成クラスが照合する手続きマニフェスト。 */
    public static ProcedureManifest procedureManifest(String fileName, String source) {
        PliSyntax.ParseResult parsed = PliSyntax.parse(fileName, source);
        if (!parsed.succeeded()) {
            return null;
        }
        return ProcedureManifest.of(parsed.program().name(), List.of());
    }

    public static final class PliExecutionException extends RuntimeException {
        public PliExecutionException(String message) {
            super(message);
        }
    }

    private static final class Executor {
        private final PliSyntax.Program program;
        private final ProgramContext context;
        private final ClassLoader loader;
        private final Env globals;
        private final Map<String, CursorDefinition> cursors = new HashMap<>();
        private int sqlSequence;

        Executor(PliSyntax.Program program, ProgramContext context, DataView[] arguments,
                 ClassLoader loader) {
            this.program = program;
            this.context = context;
            this.loader = loader;
            this.globals = new Env(null, context);
            if (arguments.length != program.parameters().size()) {
                throw new PliExecutionException("program " + program.name() + " expects "
                        + program.parameters().size() + " argument(s), but got " + arguments.length);
            }
            for (int i = 0; i < arguments.length; i++) {
                globals.put(new Var(program.parameters().get(i), PliSyntax.Type.POINTER,
                        0, 0, arguments[i]));
            }
        }

        void run() {
            try {
                execute(program.body(), globals);
            } catch (ReturnSignal ignored) {
                // 主手続きの RETURN は正常終了である。
            }
        }

        private void execute(List<PliSyntax.Stmt> statements, Env env) {
            Map<String, Integer> labels = new HashMap<>();
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i) instanceof PliSyntax.Label label) {
                    labels.put(label.name(), i);
                }
            }
            int pc = 0;
            while (pc < statements.size()) {
                try {
                    execute(statements.get(pc), env);
                    pc++;
                } catch (GoToSignal jump) {
                    Integer target = labels.get(jump.label);
                    if (target == null) {
                        throw jump;
                    }
                    pc = target + 1;
                }
            }
        }

        private void execute(PliSyntax.Stmt statement, Env env) {
            if (statement instanceof PliSyntax.Declare declare) {
                declare(declare.declarations(), env);
            } else if (statement instanceof PliSyntax.Assign assign) {
                assign(assign, env);
            } else if (statement instanceof PliSyntax.Put put) {
                put(put, env);
            } else if (statement instanceof PliSyntax.If branch) {
                execute(truth(value(branch.condition(), env)) ? branch.whenTrue()
                        : branch.whenFalse(), env);
            } else if (statement instanceof PliSyntax.Loop loop) {
                int guard = 0;
                while (loop.until() != truth(value(loop.condition(), env))) {
                    execute(loop.body(), env);
                    if (++guard > 10_000_000) {
                        throw new PliExecutionException("loop iteration limit exceeded");
                    }
                }
            } else if (statement instanceof PliSyntax.IterativeLoop loop) {
                iterativeLoop(loop, env);
            } else if (statement instanceof PliSyntax.Call call) {
                call(call, env);
            } else if (statement instanceof PliSyntax.Return) {
                throw ReturnSignal.INSTANCE;
            } else if (statement instanceof PliSyntax.Block block) {
                execute(block.body(), env);
            } else if (statement instanceof PliSyntax.GoTo goTo) {
                throw new GoToSignal(goTo.label());
            } else if (statement instanceof PliSyntax.OnEndFile onEndFile) {
                env.onEndFile(onEndFile.file(), onEndFile.handler());
            } else if (statement instanceof PliSyntax.FileOperation operation) {
                file(operation, env);
            } else if (statement instanceof PliSyntax.Sql sql) {
                sql(sql.source(), env);
            } else if (statement instanceof PliSyntax.Ignored ignored) {
                throw new PliExecutionException("PL/I statement is parsed but not executable yet: "
                        + ignored.keyword());
            }
        }

        private void iterativeLoop(PliSyntax.IterativeLoop loop, Env env) {
            Var control = env.require(loop.control());
            BigDecimal current = number(value(loop.start(), env));
            BigDecimal finish = number(value(loop.finish(), env));
            BigDecimal step = number(value(loop.step(), env));
            if (step.signum() == 0) {
                throw new PliExecutionException("DO step must not be zero: " + loop.control());
            }
            int guard = 0;
            while (step.signum() > 0 ? current.compareTo(finish) <= 0
                    : current.compareTo(finish) >= 0) {
                control.write(current, context);
                execute(loop.body(), env);
                current = current.add(step);
                if (++guard > 10_000_000) {
                    throw new PliExecutionException("loop iteration limit exceeded");
                }
            }
        }

        private void declare(List<PliSyntax.Decl> declarations, Env env) {
            for (int i = 0; i < declarations.size();) {
                PliSyntax.Decl declaration = declarations.get(i);
                if (declaration.level() > 0 && declaration.type() == PliSyntax.Type.GROUP) {
                    int end = i + 1;
                    while (end < declarations.size()
                            && declarations.get(end).level() > declaration.level()) {
                        end++;
                    }
                    declareGroup(declarations.subList(i, end), env);
                    i = end;
                } else {
                    declareScalar(declaration, env, null, 0, null);
                    i++;
                }
            }
        }

        private void declareGroup(List<PliSyntax.Decl> tree, Env env) {
            PliSyntax.Decl root = tree.get(0);
            int length = groupLength(tree, 0, tree.size());
            DataView area;
            if (root.basedOn() != null) {
                Var base = env.require(root.basedOn());
                if (base.view.length() < length) {
                    throw new PliExecutionException("BASED structure " + root.name() + " needs "
                            + length + " bytes, but " + root.basedOn() + " has "
                            + base.view.length());
                }
                area = base.view.subView(0, length);
            } else {
                area = Storage.allocate(length).whole();
            }
            Var group = new Var(root.name(), PliSyntax.Type.GROUP, length, 0, area);
            env.put(group);
            int offset = 0;
            for (int i = 1; i < tree.size();) {
                PliSyntax.Decl child = tree.get(i);
                int end = i + 1;
                while (end < tree.size() && tree.get(end).level() > child.level()) end++;
                int childLength = child.type() == PliSyntax.Type.GROUP
                        ? groupLength(tree, i, end) : byteLength(child);
                DataView view = area.subView(offset, childLength);
                Var variable = new Var(child.name(), child.type(), child.precision(),
                        child.scale(), view);
                env.put(variable);
                env.alias(root.name() + "." + child.name(), variable);
                initialize(variable, child.initial(), env);
                if (child.type() == PliSyntax.Type.GROUP) {
                    declareChildren(tree.subList(i, end), env, view, root.name());
                }
                offset += childLength;
                i = end;
            }
        }

        private void declareChildren(List<PliSyntax.Decl> tree, Env env, DataView area,
                                     String prefix) {
            PliSyntax.Decl root = tree.get(0);
            Var group = new Var(root.name(), PliSyntax.Type.GROUP, area.length(), 0, area);
            env.put(group);
            env.alias(prefix + "." + root.name(), group);
            int offset = 0;
            for (int i = 1; i < tree.size();) {
                PliSyntax.Decl child = tree.get(i);
                int end = i + 1;
                while (end < tree.size() && tree.get(end).level() > child.level()) end++;
                int length = child.type() == PliSyntax.Type.GROUP
                        ? groupLength(tree, i, end) : byteLength(child);
                DataView view = area.subView(offset, length);
                Var variable = new Var(child.name(), child.type(), child.precision(),
                        child.scale(), view);
                env.put(variable);
                env.alias(root.name() + "." + child.name(), variable);
                env.alias(prefix + "." + root.name() + "." + child.name(), variable);
                initialize(variable, child.initial(), env);
                if (child.type() == PliSyntax.Type.GROUP) {
                    declareChildren(tree.subList(i, end), env, view, prefix + "." + root.name());
                }
                offset += length;
                i = end;
            }
        }

        private static int groupLength(List<PliSyntax.Decl> tree, int root, int end) {
            int length = 0;
            int level = tree.get(root).level();
            for (int i = root + 1; i < end;) {
                PliSyntax.Decl child = tree.get(i);
                int childEnd = i + 1;
                while (childEnd < end && tree.get(childEnd).level() > child.level()) childEnd++;
                if (child.level() > level) {
                    length += child.type() == PliSyntax.Type.GROUP
                            ? groupLength(tree, i, childEnd) : byteLength(child);
                }
                i = childEnd;
            }
            return length;
        }

        private void declareScalar(PliSyntax.Decl declaration, Env env, DataView area,
                                   int offset, String alias) {
            if (declaration.type() == PliSyntax.Type.ENTRY
                    || declaration.type() == PliSyntax.Type.FILE) {
                return;
            }
            DataView view;
            if (declaration.type() == PliSyntax.Type.POINTER
                    && env.contains(declaration.name())) {
                return; // 入口引数を指すポインタは上書きしない。
            }
            if (declaration.basedOn() != null) {
                view = env.require(declaration.basedOn()).view;
            } else {
                int length = byteLength(declaration);
                view = area == null ? Storage.allocate(length).whole()
                        : area.subView(offset, length);
            }
            Var variable = new Var(declaration.name(), declaration.type(),
                    declaration.precision(), declaration.scale(), view);
            env.put(variable);
            if (alias != null) env.alias(alias, variable);
            initialize(variable, declaration.initial(), env);
        }

        private void initialize(Var variable, PliSyntax.Expr initial, Env env) {
            if (variable.type == PliSyntax.Type.CHAR || variable.type == PliSyntax.Type.PICTURE) {
                variable.view.fill(context.codePage().space());
            }
            if (initial != null) {
                variable.write(value(initial, env), context);
            } else if (variable.type == PliSyntax.Type.DECIMAL) {
                variable.write(BigDecimal.ZERO, context);
            }
        }

        private void assign(PliSyntax.Assign assignment, Env env) {
            Var target = env.require(assignment.target());
            if (assignment.value() instanceof PliSyntax.Reference reference) {
                Var source = env.require(reference.name());
                if (target.type == PliSyntax.Type.GROUP && source.type == PliSyntax.Type.GROUP) {
                    copy(source.view, target.view, context.codePage().space());
                    return;
                }
            }
            target.write(value(assignment.value(), env), context);
        }

        private void put(PliSyntax.Put put, Env env) {
            StringBuilder text = new StringBuilder();
            for (PliSyntax.Expr expression : put.values()) {
                text.append(display(value(expression, env)));
            }
            context.display(context.codePage().encode(text.toString()), put.skip());
        }

        private void call(PliSyntax.Call call, Env env) {
            PliSyntax.Procedure procedure = program.procedures().get(call.name());
            if (procedure != null) {
                Env local = new Env(env, context);
                for (int i = 0; i < procedure.parameters().size(); i++) {
                    if (i >= call.arguments().size()
                            || !(call.arguments().get(i) instanceof PliSyntax.Reference reference)) {
                        throw new PliExecutionException("internal procedure arguments must be references");
                    }
                    local.alias(procedure.parameters().get(i), env.require(reference.name()));
                }
                try {
                    execute(procedure.body(), local);
                } catch (ReturnSignal ignored) {
                    // 内部プロシージャから呼出元へ戻る。
                }
                return;
            }
            DataView[] arguments = new DataView[call.arguments().size()];
            for (int i = 0; i < arguments.length; i++) {
                PliSyntax.Expr expression = call.arguments().get(i);
                if (expression instanceof PliSyntax.Reference reference) {
                    arguments[i] = env.require(reference.name()).view;
                } else {
                    Object value = value(expression, env);
                    byte[] encoded = context.codePage().encode(display(value));
                    arguments[i] = Storage.wrap(encoded).whole();
                }
            }
            Ops.call(context, call.name(), loader, arguments);
        }

        private void file(PliSyntax.FileOperation operation, Env env) {
            DataSet dataSet = context.file(operation.file(), operation.file());
            switch (operation.action()) {
                case OPEN -> checkFileStatus(operation.file(), dataSet.open(OpenMode.INPUT, false), false);
                case CLOSE -> checkFileStatus(operation.file(), dataSet.close(), false);
                case READ -> {
                    Var target = env.require(operation.target());
                    byte[] record = new byte[target.view.length()];
                    java.util.Arrays.fill(record, context.codePage().space());
                    String status = dataSet.read(record);
                    if ("10".equals(status)) {
                        List<PliSyntax.Stmt> handler = env.endFile(operation.file());
                        if (handler == null) {
                            throw new PliExecutionException("end of file without ON ENDFILE: "
                                    + operation.file());
                        }
                        execute(handler, env);
                    } else {
                        checkFileStatus(operation.file(), status, false);
                        target.view.setBytes(record);
                    }
                }
            }
        }

        private void sql(String source, Env env) {
            SqlStatement statement = SqlStatement.parse(source);
            if (statement.kind == SqlKind.INCLUDE) {
                if (!env.contains("SQLCA")) {
                    DataView area = Storage.allocate(Db2RuntimeOps.SQLCA_LENGTH).whole();
                    env.put(new Var("SQLCA", PliSyntax.Type.GROUP, area.length(), 0, area));
                    env.alias("SQLCODE", new Var("SQLCODE", PliSyntax.Type.BINARY,
                            31, 0, area.subView(12, 4)));
                }
                return;
            }
            if (statement.kind == SqlKind.DECLARE_CURSOR) {
                cursors.put(statement.cursor, new CursorDefinition(statement.cursor,
                        statement.withHold, statement.sql, statement.inputs));
                return;
            }
            Var sqlca = env.require("SQLCA");
            if (statement.kind == SqlKind.COMMIT) {
                Db2RuntimeOps.commit(context, sqlca.view);
                return;
            }
            if (statement.kind == SqlKind.ROLLBACK) {
                Db2RuntimeOps.rollback(context, sqlca.view);
                return;
            }
            String sql = statement.sql;
            List<HostRef> inputs = statement.inputs;
            boolean withHold = statement.withHold;
            if (statement.kind == SqlKind.OPEN_CURSOR) {
                CursorDefinition cursor = cursors.get(statement.cursor);
                if (cursor == null) {
                    throw new PliExecutionException("SQL cursor was not declared: "
                            + statement.cursor);
                }
                sql = cursor.sql;
                inputs = cursor.inputs;
                withHold = cursor.withHold;
            }
            List<HostRef> hosts = new ArrayList<>(inputs);
            hosts.addAll(statement.outputs);
            DataView[] values = new DataView[hosts.size()];
            DataView[] indicators = new DataView[hosts.size()];
            int[] shapes = new int[hosts.size() * Db2RuntimeOps.SHAPE_WIDTH];
            for (int i = 0; i < hosts.size(); i++) {
                HostRef host = hosts.get(i);
                Var variable = env.require(host.name);
                values[i] = variable.view;
                indicators[i] = host.indicator == null ? null : env.require(host.indicator).view;
                variable.sqlShape(shapes, i * Db2RuntimeOps.SHAPE_WIDTH);
            }
            Db2RuntimeOps.execute(context, "PLI-SQL-" + (++sqlSequence),
                    statement.operation.ordinal(), sql, statement.cursor, withHold,
                    values, indicators, shapes, inputs.size(), sqlca.view);
        }

        private static void checkFileStatus(String file, String status, boolean endAllowed) {
            if (!("00".equals(status) || "05".equals(status)
                    || endAllowed && "10".equals(status))) {
                throw new PliExecutionException("file " + file + " returned status " + status);
            }
        }

        private Object value(PliSyntax.Expr expression, Env env) {
            if (expression instanceof PliSyntax.Literal literal) {
                return literal.value();
            }
            if (expression instanceof PliSyntax.Reference reference) {
                return env.require(reference.name()).read(context);
            }
            if (expression instanceof PliSyntax.Unary unary) {
                Object operand = value(unary.operand(), env);
                return switch (unary.operator()) {
                    case "^", "¬" -> !truth(operand);
                    case "-" -> number(operand).negate();
                    case "+" -> number(operand);
                    default -> throw new PliExecutionException("unknown unary operator "
                            + unary.operator());
                };
            }
            if (expression instanceof PliSyntax.Binary binary) {
                Object left = value(binary.left(), env);
                if (binary.operator().equals("|") && truth(left)) return true;
                if (binary.operator().equals("&") && !truth(left)) return false;
                Object right = value(binary.right(), env);
                return switch (binary.operator()) {
                    case "|" -> truth(left) || truth(right);
                    case "&" -> truth(left) && truth(right);
                    case "||" -> display(left) + display(right);
                    case "+" -> number(left).add(number(right));
                    case "-" -> number(left).subtract(number(right));
                    case "*" -> number(left).multiply(number(right));
                    case "/" -> number(left).divide(number(right), MathContext.DECIMAL128);
                    case "=" -> compare(left, right) == 0;
                    case "^=", "¬=" -> compare(left, right) != 0;
                    case "<" -> compare(left, right) < 0;
                    case ">" -> compare(left, right) > 0;
                    case "<=" -> compare(left, right) <= 0;
                    case ">=" -> compare(left, right) >= 0;
                    default -> throw new PliExecutionException("unknown binary operator "
                            + binary.operator());
                };
            }
            PliSyntax.Function function = (PliSyntax.Function) expression;
            String name = function.name();
            List<Object> arguments = function.arguments().stream().map(e -> value(e, env)).toList();
            return switch (name) {
                case "TRIM" -> display(arguments.get(0)).strip();
                case "SUBSTR" -> substring(arguments);
                case "DATETIME" -> ZonedDateTime.now(context.clock()).format(DATETIME);
                case "CHAR" -> display(arguments.get(0));
                case "SIZE" -> size(function, env);
                case "ADDR" -> address(function, env);
                case "CENTRE", "CENTER" -> centre(arguments);
                case "REPEAT" -> display(arguments.get(0)).repeat(number(arguments.get(1)).intValue());
                default -> throw new PliExecutionException("PL/I built-in is not supported: " + name);
            };
        }

        private Object size(PliSyntax.Function function, Env env) {
            if (function.arguments().size() != 1
                    || !(function.arguments().get(0) instanceof PliSyntax.Reference reference)) {
                throw new PliExecutionException("SIZE requires one data reference");
            }
            return BigDecimal.valueOf(env.require(reference.name()).view.length());
        }

        private Object address(PliSyntax.Function function, Env env) {
            if (function.arguments().size() != 1
                    || !(function.arguments().get(0) instanceof PliSyntax.Reference reference)) {
                throw new PliExecutionException("ADDR requires one data reference");
            }
            return env.require(reference.name()).view;
        }

        private static String substring(List<Object> arguments) {
            String source = display(arguments.get(0));
            int start = number(arguments.get(1)).intValue() - 1;
            int length = arguments.size() > 2 ? number(arguments.get(2)).intValue()
                    : source.length() - start;
            if (start < 0 || length < 0 || start + length > source.length()) {
                throw new PliExecutionException("SUBSTR range is outside the string");
            }
            return source.substring(start, start + length);
        }

        private static String centre(List<Object> arguments) {
            String text = display(arguments.get(0));
            int width = number(arguments.get(1)).intValue();
            if (text.length() >= width) return text.substring(0, width);
            int left = (width - text.length()) / 2;
            return " ".repeat(left) + text + " ".repeat(width - text.length() - left);
        }

        private static int compare(Object left, Object right) {
            if (left instanceof Number || right instanceof Number) {
                return number(left).compareTo(number(right));
            }
            if (left instanceof Boolean || right instanceof Boolean) {
                return Boolean.compare(truth(left), truth(right));
            }
            String a = display(left);
            String b = display(right);
            int width = Math.max(a.length(), b.length());
            return a.stripTrailing().compareTo(b.stripTrailing());
        }

        private static BigDecimal number(Object value) {
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof Number numeric) return new BigDecimal(numeric.toString());
            if (value instanceof Boolean bool) return bool ? BigDecimal.ONE : BigDecimal.ZERO;
            try {
                return new BigDecimal(display(value).strip());
            } catch (NumberFormatException e) {
                throw new PliExecutionException("value is not numeric: " + display(value));
            }
        }

        private static boolean truth(Object value) {
            if (value instanceof Boolean bool) return bool;
            if (value instanceof Number) return number(value).signum() != 0;
            return !display(value).isBlank() && !display(value).equals("0");
        }

        private static String display(Object value) {
            if (value == null) return "";
            if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
            return value.toString();
        }

        private static int byteLength(PliSyntax.Decl declaration) {
            return switch (declaration.type()) {
                case CHAR, BIT, PICTURE -> Math.max(1, declaration.precision());
                case BINARY -> declaration.precision() <= 15 ? 2 : 4;
                case DECIMAL -> PackedDecimal.byteLength(Math.max(1, declaration.precision()));
                case POINTER -> 0;
                case GROUP, FILE, ENTRY -> 0;
            };
        }

        private static void copy(DataView source, DataView target, byte pad) {
            int length = Math.min(source.length(), target.length());
            target.fill(pad);
            for (int i = 0; i < length; i++) target.set(i, source.get(i));
        }
    }

    private static final class Env {
        private final Env parent;
        private final ProgramContext context;
        private final Map<String, Var> variables = new LinkedHashMap<>();
        private final Map<String, List<PliSyntax.Stmt>> endFileHandlers = new HashMap<>();

        Env(Env parent, ProgramContext context) {
            this.parent = parent;
            this.context = context;
        }

        void put(Var variable) {
            if (!variable.name.equals("*")) variables.put(variable.name, variable);
        }

        void alias(String name, Var variable) {
            variables.put(name.toUpperCase(Locale.ROOT), variable);
        }

        boolean contains(String name) {
            return find(name) != null;
        }

        Var require(String name) {
            Var value = find(name);
            if (value == null) throw new PliExecutionException("undeclared PL/I name: " + name);
            return value;
        }

        void onEndFile(String file, List<PliSyntax.Stmt> handler) {
            endFileHandlers.put(file.toUpperCase(Locale.ROOT), handler);
        }

        List<PliSyntax.Stmt> endFile(String file) {
            List<PliSyntax.Stmt> own = endFileHandlers.get(file.toUpperCase(Locale.ROOT));
            return own != null ? own : parent == null ? null : parent.endFile(file);
        }

        private Var find(String name) {
            String normalized = name.toUpperCase(Locale.ROOT);
            Var own = variables.get(normalized);
            return own != null ? own : parent == null ? null : parent.find(normalized);
        }
    }

    private static final class Var {
        final String name;
        final PliSyntax.Type type;
        final int precision;
        final int scale;
        final DataView view;

        Var(String name, PliSyntax.Type type, int precision, int scale, DataView view) {
            this.name = name.toUpperCase(Locale.ROOT);
            this.type = type;
            this.precision = precision;
            this.scale = scale;
            this.view = view;
        }

        Object read(ProgramContext context) {
            return switch (type) {
                case CHAR, BIT, PICTURE, GROUP -> context.codePage().decode(view.toByteArray());
                case BINARY -> BinaryDecimal.decode(view.toByteArray(), 0).toBigDecimal();
                case DECIMAL -> PackedDecimal.decode(view.toByteArray(), scale, NumProcMode.PFD)
                        .toBigDecimal();
                case POINTER -> view;
                case FILE, ENTRY -> "";
            };
        }

        void write(Object value, ProgramContext context) {
            switch (type) {
                case CHAR, BIT, PICTURE -> writeText(Executor.display(value), context);
                case GROUP -> {
                    if (value instanceof BigDecimal decimal && decimal.signum() == 0) {
                        view.fill((byte) 0);
                    } else {
                        writeText(Executor.display(value), context);
                    }
                }
                case BINARY -> view.setBytes(BinaryDecimal.encode(
                        Decimal.parse(Executor.number(value).toPlainString()),
                        precision <= 15 ? 4 : 9, 0, TruncMode.BIN));
                case DECIMAL -> view.setBytes(PackedDecimal.encode(
                        Decimal.parse(Executor.number(value).toPlainString()), precision, scale, true));
                case POINTER, FILE, ENTRY -> throw new PliExecutionException(
                        "assignment to " + type + " is not supported: " + name);
            }
        }

        void sqlShape(int[] shape, int offset) {
            switch (type) {
                case CHAR, BIT, PICTURE -> {
                    shape[offset] = Db2RuntimeOps.CHARACTER;
                    shape[offset + 1] = view.length();
                }
                case BINARY -> {
                    shape[offset] = Db2RuntimeOps.BINARY;
                    // Db2 descriptor は10進桁数から物理幅を導く。PL/I の BIN(p) の p はbit精度である。
                    shape[offset + 1] = view.length() == 2 ? 4 : 9;
                    shape[offset + 3] = 1;
                }
                case DECIMAL -> {
                    shape[offset] = Db2RuntimeOps.PACKED;
                    shape[offset + 1] = precision;
                    shape[offset + 2] = scale;
                    shape[offset + 3] = 1;
                }
                default -> throw new PliExecutionException(
                        "data type cannot be an SQL host variable: " + type + " " + name);
            }
        }

        private void writeText(String value, ProgramContext context) {
            byte[] encoded = context.codePage().encode(value);
            view.fill(context.codePage().space());
            int length = Math.min(encoded.length, view.length());
            for (int i = 0; i < length; i++) view.set(i, encoded[i]);
        }
    }

    private static final class ReturnSignal extends RuntimeException {
        static final ReturnSignal INSTANCE = new ReturnSignal();

        private ReturnSignal() {
            super(null, null, false, false);
        }
    }

    private static final class GoToSignal extends RuntimeException {
        final String label;

        GoToSignal(String label) {
            super(null, null, false, false);
            this.label = label;
        }
    }

    private record CursorDefinition(String name, boolean withHold, String sql,
                                    List<HostRef> inputs) {
    }

    private record HostRef(String name, String indicator) {
    }

    private enum SqlKind {
        INCLUDE, DECLARE_CURSOR, SELECT, INSERT, UPDATE, DELETE,
        OPEN_CURSOR, FETCH_CURSOR, CLOSE_CURSOR, COMMIT, ROLLBACK
    }

    /** SQL全体はDBへ渡し、ここではhost variableとcursorの境界だけを解く。 */
    private static final class SqlStatement {
        private static final Pattern DECLARE = Pattern.compile(
                "(?is)^DECLARE\\s+([A-Z_#$@][A-Z0-9_#$@]*)\\s+CURSOR\\s+"
                        + "(WITH\\s+HOLD\\s+)?FOR\\s+(.+)$");
        private static final Pattern CURSOR = Pattern.compile(
                "(?is)^(OPEN|CLOSE)\\s+([A-Z_#$@][A-Z0-9_#$@]*)$");
        private static final Pattern FETCH = Pattern.compile(
                "(?is)^FETCH(?:\\s+NEXT)?(?:\\s+FROM)?\\s+"
                        + "([A-Z_#$@][A-Z0-9_#$@]*)\\s+INTO\\s+(.+)$");
        private static final Pattern HOST = Pattern.compile(
                ":([A-Z_#$@][A-Z0-9_#$@]*)", Pattern.CASE_INSENSITIVE);

        final SqlKind kind;
        final SqlOperation operation;
        final String sql;
        final String cursor;
        final boolean withHold;
        final List<HostRef> inputs;
        final List<HostRef> outputs;

        private SqlStatement(SqlKind kind, SqlOperation operation, String sql, String cursor,
                             boolean withHold, List<HostRef> inputs, List<HostRef> outputs) {
            this.kind = kind;
            this.operation = operation;
            this.sql = sql;
            this.cursor = cursor;
            this.withHold = withHold;
            this.inputs = List.copyOf(inputs);
            this.outputs = List.copyOf(outputs);
        }

        static SqlStatement parse(String source) {
            String text = source.strip();
            String upper = text.toUpperCase(Locale.ROOT);
            if (upper.equals("INCLUDE SQLCA")) {
                return simple(SqlKind.INCLUDE, null);
            }
            Matcher declare = DECLARE.matcher(text);
            if (declare.matches()) {
                String cursor = declare.group(1).toUpperCase(Locale.ROOT);
                String query = declare.group(3).strip();
                return new SqlStatement(SqlKind.DECLARE_CURSOR, null,
                        parameterize(query), cursor, declare.group(2) != null,
                        inputHosts(query), List.of());
            }
            Matcher cursor = CURSOR.matcher(text);
            if (cursor.matches()) {
                boolean open = cursor.group(1).equalsIgnoreCase("OPEN");
                String name = cursor.group(2).toUpperCase(Locale.ROOT);
                return new SqlStatement(open ? SqlKind.OPEN_CURSOR : SqlKind.CLOSE_CURSOR,
                        open ? SqlOperation.OPEN_CURSOR : SqlOperation.CLOSE_CURSOR,
                        text, name, false, List.of(), List.of());
            }
            Matcher fetch = FETCH.matcher(text);
            if (fetch.matches()) {
                String name = fetch.group(1).toUpperCase(Locale.ROOT);
                return new SqlStatement(SqlKind.FETCH_CURSOR, SqlOperation.FETCH_CURSOR,
                        "FETCH " + name, name, false, List.of(), outputHosts(fetch.group(2)));
            }
            if (upper.startsWith("SELECT ")) {
                int into = topLevelKeyword(text, "INTO", 0);
                int from = topLevelKeyword(text, "FROM", into < 0 ? 0 : into + 4);
                if (into < 0 || from < 0) {
                    throw new PliExecutionException("SELECT outside a cursor requires INTO ... FROM");
                }
                String output = text.substring(into + 4, from);
                String query = text.substring(0, into) + " " + text.substring(from);
                return new SqlStatement(SqlKind.SELECT, SqlOperation.SELECT_ONE,
                        parameterize(query), null, false, inputHosts(query), outputHosts(output));
            }
            for (SqlKind kind : List.of(SqlKind.INSERT, SqlKind.UPDATE, SqlKind.DELETE)) {
                if (upper.startsWith(kind.name() + " ")) {
                    return new SqlStatement(kind, SqlOperation.valueOf(kind.name()),
                            parameterize(text), null, false, inputHosts(text), List.of());
                }
            }
            if (upper.equals("COMMIT") || upper.equals("COMMIT WORK")) {
                return simple(SqlKind.COMMIT, null);
            }
            if (upper.equals("ROLLBACK") || upper.equals("ROLLBACK WORK")) {
                return simple(SqlKind.ROLLBACK, null);
            }
            throw new PliExecutionException("unsupported EXEC SQL statement: " + text);
        }

        private static SqlStatement simple(SqlKind kind, SqlOperation operation) {
            return new SqlStatement(kind, operation, "", null, false, List.of(), List.of());
        }

        private static List<HostRef> inputHosts(String sql) {
            List<HostRef> result = new ArrayList<>();
            Matcher matcher = HOST.matcher(withoutQuotedText(sql));
            while (matcher.find()) {
                result.add(new HostRef(matcher.group(1).toUpperCase(Locale.ROOT), null));
            }
            return result;
        }

        private static List<HostRef> outputHosts(String list) {
            List<HostRef> result = new ArrayList<>();
            for (String item : splitTopLevel(list, ',')) {
                Matcher matcher = HOST.matcher(item);
                if (!matcher.find()) {
                    throw new PliExecutionException("SQL INTO item is not a host variable: " + item);
                }
                String value = matcher.group(1).toUpperCase(Locale.ROOT);
                String indicator = matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
                if (matcher.find()) {
                    throw new PliExecutionException("SQL INTO item has too many host variables: " + item);
                }
                result.add(new HostRef(value, indicator));
            }
            return result;
        }

        private static String parameterize(String sql) {
            StringBuilder out = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < sql.length();) {
                char c = sql.charAt(i);
                if (c == '\'') {
                    out.append(c);
                    if (quoted && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i += 2;
                        continue;
                    }
                    quoted = !quoted;
                    i++;
                } else if (!quoted && c == ':') {
                    Matcher matcher = HOST.matcher(sql.substring(i));
                    if (!matcher.lookingAt()) {
                        out.append(c);
                        i++;
                    } else {
                        out.append('?');
                        i += matcher.end();
                    }
                } else {
                    out.append(c);
                    i++;
                }
            }
            return normalizeSql(out.toString());
        }

        private static String normalizeSql(String sql) {
            StringBuilder out = new StringBuilder(sql.length());
            boolean quoted = false;
            boolean whitespace = false;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '\'') {
                    if (!quoted && whitespace && !out.isEmpty()) out.append(' ');
                    whitespace = false;
                    out.append(c);
                    if (quoted && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (!quoted && Character.isWhitespace(c)) {
                    whitespace = true;
                } else {
                    if (!quoted && c == '?' && !out.isEmpty()
                            && "=<>".indexOf(out.charAt(out.length() - 1)) >= 0) {
                        out.append(' ');
                    } else if (whitespace && !out.isEmpty()) {
                        out.append(' ');
                    }
                    whitespace = false;
                    out.append(c);
                }
            }
            return out.toString().strip();
        }

        private static String withoutQuotedText(String sql) {
            StringBuilder out = new StringBuilder(sql.length());
            boolean quoted = false;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '\'') quoted = !quoted;
                out.append(quoted ? ' ' : c);
            }
            return out.toString();
        }

        private static int topLevelKeyword(String text, String keyword, int from) {
            int depth = 0;
            boolean quoted = false;
            for (int i = from; i <= text.length() - keyword.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'') quoted = !quoted;
                if (quoted) continue;
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (depth == 0 && text.regionMatches(true, i, keyword, 0, keyword.length())
                        && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))
                        && (i + keyword.length() == text.length()
                        || !Character.isLetterOrDigit(text.charAt(i + keyword.length())))) {
                    return i;
                }
            }
            return -1;
        }

        private static List<String> splitTopLevel(String text, char separator) {
            List<String> result = new ArrayList<>();
            int start = 0;
            int depth = 0;
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'') quoted = !quoted;
                if (!quoted && c == '(') depth++;
                if (!quoted && c == ')') depth--;
                if (!quoted && depth == 0 && c == separator) {
                    result.add(text.substring(start, i).strip());
                    start = i + 1;
                }
            }
            result.add(text.substring(start).strip());
            return result;
        }
    }
}
