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
import dev.cobolonjava.runtime.program.AddressSpace;
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
        new Executor(parsed.program(), context, arguments, loader,
                PliOptions.fromSource(source)).run();
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
        private final PrintFile sysprint;
        /** *PROCESS の RULES と LIMITS。翻訳時に原文へ残した注記から読む。 */
        private final PliOptions options;
        /** 評価中の式の算術の規則。式の頭で決め、式の中では変えない。 */
        private FixedValue.Arithmetic arithmetic;
        private int sqlSequence;

        Executor(PliSyntax.Program program, ProgramContext context, DataView[] arguments,
                 ClassLoader loader, PliOptions options) {
            this.options = options;
            this.program = program;
            this.context = context;
            this.loader = loader;
            this.globals = new Env(null, context);
            this.sysprint = PrintFile.of(context);
            if (arguments.length != program.parameters().size()) {
                throw new PliExecutionException("program " + program.name() + " expects "
                        + program.parameters().size() + " argument(s), but got " + arguments.length);
            }
            for (int i = 0; i < arguments.length; i++) {
                String name = program.parameters().get(i).toUpperCase(Locale.ROOT);
                parameters.put(name, arguments[i]);
                // 引数は参照で渡る。POINTER と宣言した引数 (IMS の PCB など) は、渡された記憶域の番号を
                // 値に持つ。ほかの型と宣言すれば、渡された記憶域そのものになる (declareScalar)
                globals.put(pointerTo(name, arguments[i]));
            }
        }

        /** 入口引数の記憶域。名は大文字。 */
        private final Map<String, DataView> parameters = new HashMap<>();

        /**
         * BASED の変数を、基にした POINTER が変わったときに宣言し直す手順。POINTER の名ごと。
         * BASED の変数は参照のたびに POINTER の値で決まる (LRM "BASED attribute") ので、
         * POINTER へ代入したら重ね直す。
         */
        private final Map<String, List<Runnable>> basedOn = new HashMap<>();

        /** 記憶域を指す POINTER の変数を作る。値は AddressSpace の番号 (COBOL の SET ADDRESS OF と同じ)。 */
        private Var pointerTo(String name, DataView target) {
            Var pointer = new Var(name, PliSyntax.Type.POINTER, 0, 0, Storage.allocate(4).whole());
            pointer.write(new PointerValue(address(target)), context);
            return pointer;
        }

        private int address(DataView target) {
            return target == null ? 0
                    : AddressSpace.of(context).addressOf(target.storage(), target.offset());
        }

        /**
         * BASED の変数が重なる記憶域。{@code BASED(P)} なら P の値の番号が指す場所、{@code BASED(ADDR(X))}
         * なら X の記憶域 (前処理で X の名だけが残る)。
         */
        private DataView basedView(String name, int length, Env env) {
            Var base = env.require(name);
            if (base.type != PliSyntax.Type.POINTER) {
                if (base.view.length() < length) {
                    throw new PliExecutionException("BASED variable needs " + length
                            + " bytes, but " + name + " has " + base.view.length());
                }
                return length > 0 && length < base.view.length()
                        ? base.view.subView(0, length) : base.view;
            }
            int address = ((PointerValue) base.read(context)).address();
            if (address == 0) {
                throw new PliExecutionException("BASED on " + name + ", which is a null pointer");
            }
            AddressSpace.Location location = AddressSpace.of(context).locate(address);
            if (location == null) {
                // ホストで壊れた番地を使えば保護例外になる
                throw new PliExecutionException("S0C4: " + name + " does not point into this run unit");
            }
            if (location.offset() + length > location.storage().size()) {
                throw new PliExecutionException("BASED variable needs " + length
                        + " bytes at " + name + ", beyond the storage it points to");
            }
            return location.storage().view(location.offset(), length);
        }

        void run() {
            sysprint.enter();
            try {
                execute(program.body(), globals);
            } catch (ReturnSignal ignored) {
                // 主手続きの RETURN は正常終了である。
            } finally {
                sysprint.leave();
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
                // WHILE は繰り返す前、UNTIL は繰り返した後に調べる。UNTIL だけなら少なくとも 1 度動く
                int guard = 0;
                while (loop.whileCondition() == null
                        || truth(value(loop.whileCondition(), env))) {
                    execute(loop.body(), env);
                    if (loop.untilCondition() != null
                            && truth(value(loop.untilCondition(), env))) {
                        break;
                    }
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

        /**
         * 構造を宣言する。要素の位置は {@link StructureMapping} が LRM の規則で決める。以前は要素を
         * 隙間なく並べていた (P-184)。
         */
        private void declareGroup(List<PliSyntax.Decl> tree, Env env) {
            PliSyntax.Decl root = tree.get(0);
            StructureMapping.Result mapping;
            try {
                mapping = StructureMapping.map(tree);
            } catch (IllegalArgumentException unsupported) {
                throw new PliExecutionException(unsupported.getMessage());
            }
            int length = mapping.size();
            DataView area;
            if (root.basedOn() != null) {
                area = basedView(root.basedOn(), length, env);
                remember(root.basedOn(), () -> declareGroup(tree, env));
            } else if (parameters.containsKey(root.name().toUpperCase(Locale.ROOT))) {
                area = argument(root.name(), length);
            } else {
                area = Storage.allocate(length).whole();
            }
            Var group = new Var(root.name(), PliSyntax.Type.GROUP, length, 0, area);
            env.put(group);
            // BASED の構造は自分の記憶域を持たず、別のものの上に重ねる。INITIAL は ALLOCATE で
            // 記憶域を取ったときにしか効かないので、宣言で重ねた先を書き換えてはならない。
            // 以前は文字の要素を空白で埋めており、IMS から渡された DB PCB の DBD 名と PROCOPT を消していた
            boolean based = root.basedOn() != null;
            declareMembers(tree, 0, tree.size(), env, group, area, mapping, root.name(), based);
        }

        /** {@code index} の構造の直下の要素を宣言する。小構造は入れ子で開く。 */
        private void declareMembers(List<PliSyntax.Decl> tree, int index, int end, Env env,
                                    Var group, DataView area, StructureMapping.Result mapping,
                                    String prefix, boolean based) {
            // area は構造全体の記憶域。位置は構造の頭からのビットで決まっている
            for (int i = index + 1; i < end;) {
                PliSyntax.Decl child = tree.get(i);
                int childEnd = i + 1;
                while (childEnd < end && tree.get(childEnd).level() > child.level()) childEnd++;
                DataView view = area.subView(mapping.byteOffset(i), mapping.byteLength(i));
                Var variable = new Var(child.name(), child.type(), child.precision(),
                        child.scale(), view, mapping.bitShift(i));
                env.put(variable);
                env.alias(tree.get(index).name() + "." + child.name(), variable);
                if (index > 0) {
                    env.alias(prefix + "." + child.name(), variable);
                }
                group.members.add(variable);
                if (!based) {
                    initialize(variable, child.initial(), env);
                }
                if (child.type() == PliSyntax.Type.GROUP) {
                    declareMembers(tree, i, childEnd, env, variable, area, mapping,
                            prefix + "." + child.name(), based);
                }
                i = childEnd;
            }
        }

        /** BASED(P) の宣言を、P へ代入したときに宣言し直せるよう覚えておく。 */
        private void remember(String pointer, Runnable redeclare) {
            basedOn.computeIfAbsent(pointer.toUpperCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(redeclare);
        }

        /** 入口引数の記憶域を、宣言した長さで読む。 */
        private DataView argument(String name, int length) {
            DataView argument = parameters.get(name.toUpperCase(Locale.ROOT));
            if (argument.length() < length) {
                throw new PliExecutionException("parameter " + name + " is declared with "
                        + length + " bytes, but the caller passed " + argument.length());
            }
            return length > 0 && length < argument.length() ? argument.subView(0, length) : argument;
        }

        private void declareScalar(PliSyntax.Decl declaration, Env env, DataView area,
                                   int offset, String alias) {
            if (declaration.type() == PliSyntax.Type.ENTRY
                    || declaration.type() == PliSyntax.Type.FILE) {
                return;
            }
            DataView view;
            boolean parameter = parameters.containsKey(
                    declaration.name().toUpperCase(Locale.ROOT));
            if (declaration.type() == PliSyntax.Type.POINTER && parameter) {
                return; // 入口引数の POINTER は、渡された記憶域の番号をもう持っている
            }
            if (declaration.basedOn() != null) {
                // 重ねる先のうち、自分の長さの分だけを使う。先の全体を使うと、短い変数へ書いたときに
                // 残りまで書き換えてしまう (PIC'(9)9' を 10 桁の CHAR に重ねると 10 桁目が空白になっていた)
                view = basedView(declaration.basedOn(), byteLength(declaration), env);
                remember(declaration.basedOn(),
                        () -> declareScalar(declaration, env, area, offset, alias));
            } else if (parameter) {
                // 入口引数は参照で渡る。宣言した型で、渡された記憶域そのものを読む
                view = argument(declaration.name(), byteLength(declaration));
            } else {
                int length = byteLength(declaration);
                view = area == null ? Storage.allocate(length).whole()
                        : area.subView(offset, length);
            }
            Var variable = new Var(declaration.name(), declaration.type(),
                    declaration.precision(), declaration.scale(), view);
            env.put(variable);
            if (alias != null) env.alias(alias, variable);
            if (declaration.basedOn() == null) {
                initialize(variable, declaration.initial(), env);
            }
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
            Object value = value(assignment.value(), env);
            if (target.type == PliSyntax.Type.POINTER) {
                target.write(value, context);
                List<Runnable> dependents = basedOn.get(target.name);
                if (dependents != null) {
                    // 宣言し直すと新しい手順が覚えられるので、今の並びを写してから動かす
                    List<Runnable> current = List.copyOf(dependents);
                    dependents.clear();
                    current.forEach(Runnable::run);
                }
                return;
            }
            if (target.type == PliSyntax.Type.GROUP && !target.members.isEmpty()) {
                // 構造へ単一の値を代入すると、要素ごとの代入に展開される (LRM "Aggregate
                // assignments")。要素ごとにその型へ変換するので、INPUT_AREA = 0 は数の要素を 0 に、
                // 文字の要素を '   0' (FIXED DEC(1) を文字にしたもの) にする。以前は域を X'00' で埋めていた
                for (Var element : elements(target)) {
                    element.write(value, context);
                }
                return;
            }
            target.write(value, context);
        }

        /** 構造の要素を宣言の順に、入れ子を開いて並べる。 */
        private static List<Var> elements(Var structure) {
            List<Var> result = new ArrayList<>();
            for (Var member : structure.members) {
                if (member.type == PliSyntax.Type.GROUP && !member.members.isEmpty()) {
                    result.addAll(elements(member));
                } else {
                    result.add(member);
                }
            }
            return result;
        }

        /** {@code SKIP} は書く<b>前</b>に改行する。以前は書いた後に改行していた。 */
        private void put(PliSyntax.Put put, Env env) {
            if (put.string() != null) {
                putString(put, env);
                return;
            }
            // PAGE、SKIP、値の順に効く
            if (put.page()) {
                sysprint.page();
            }
            if (put.skip() > 0) {
                sysprint.skip(put.skip());
            }
            if (put.edit()) {
                edit(put, env, sysprint::editItem);
                return;
            }
            for (PliSyntax.Expr expression : put.values()) {
                for (Object item : items(expression, env)) {
                    sysprint.listItem(listed(item));
                }
            }
        }

        /** ビット列の変数の値。list-directed では引用符と B を付ける。 */
        private record Bits(String text) {
        }

        /** POINTER の値。HEX で書く。 */
        private record Hex(String text) {
        }

        /**
         * データ並びの 1 項目を、送る値の並びにする。構造は要素の数だけの項目と同じである
         * (LRM "An array or structure variable in a data-list is equivalent to n items")。
         * 以前は構造の記憶域をそのまま文字として書いており、2 進の要素が文字化けしていた。
         */
        private List<Object> items(PliSyntax.Expr expression, Env env) {
            if (expression instanceof PliSyntax.Reference reference) {
                Var variable = env.require(reference.name());
                if (variable.type == PliSyntax.Type.GROUP && !variable.members.isEmpty()) {
                    List<Object> values = new ArrayList<>();
                    for (Var element : elements(variable)) {
                        values.add(item(element));
                    }
                    return values;
                }
                return List.of(item(variable));
            }
            return List.of(value(expression, env));
        }

        private Object item(Var variable) {
            return switch (variable.type) {
                case BIT -> new Bits(display(variable.read(context)));
                // POINTER は HEX で書く (LRM "the contents of the item will be transmitted as if the
                // item had been specified by applying the HEX built-in function")。4 byte の値は
                // 実行単位の中で振った番号 (P-150) で、COBOL の SET ADDRESS OF と同じ番号になる
                case POINTER -> new Hex(String.format("%08X",
                        ((PointerValue) variable.read(context)).address()));
                default -> variable.read(context);
            };
        }


        /**
         * list-directed で PRINT ファイルへ書く形 (LRM "PUT list-directed")。
         *
         * <p>算術値は代入と同じ規則で文字にする。{@code FIXED BIN(31)} の 5 は 14 桁の欄に右寄せ
         * した {@code "             5"} である。文字列は PRINT ファイルなので引用符を付けない。
         * ビット列は引用符で囲んで {@code B} を付ける。
         */
        private static String listed(Object value) {
            if (value instanceof Bits bits) {
                return "'" + bits.text() + "'B";
            }
            if (value instanceof Boolean bit) {
                return bit ? "'1'B" : "'0'B";
            }
            if (value instanceof PliSyntax.BitString bits) {
                return "'" + bits.bits() + "'B";
            }
            return character(value);
        }

        /**
         * edit-directed。書式並びを先頭から使い、値が残っていれば並びの頭へ戻る。
         * {@code A} は文字にした値、{@code A(w)} は w 桁に切り詰めるか右を空白で埋め、
         * {@code X(w)} は空白 w 個を書く。
         */
        /**
         * {@code PUT STRING(変数) EDIT ...}。左端から組み立て、文字の変数へ<b>代入する</b>
         * (LRM "STRING option")。代入なので、短ければ右を空白で埋める。入りきらなければ
         * ERROR の状態である。
         *
         * <p>以前はこの文を読み飛ばしていた。Bank-of-Z の {@code BNKSTMT} は明細の合計行を
         * この形で作っており、前の行の中身がそのまま印字されていた。
         */
        private void putString(PliSyntax.Put put, Env env) {
            StringBuilder text = new StringBuilder();
            edit(put, env, text::append);
            Var target = env.require(put.string());
            if (text.length() > target.view.length()) {
                throw new PliExecutionException("ERROR: PUT STRING needs " + text.length()
                        + " characters but " + target.name + " has " + target.view.length());
            }
            target.write(text.toString(), context);
        }

        private void edit(PliSyntax.Put put, Env env, java.util.function.Consumer<String> sink) {
            List<PliSyntax.FormatItem> format = put.format();
            int item = 0;
            int consumedInCycle = 0;
            List<Object> values = new ArrayList<>();
            for (PliSyntax.Expr expression : put.values()) {
                values.addAll(items(expression, env));
            }
            for (Object value : values) {
                while (true) {
                    if (item == format.size()) {
                        if (consumedInCycle == 0) {
                            throw new PliExecutionException(
                                    "PUT EDIT format list has no data format item");
                        }
                        item = 0;
                        consumedInCycle = 0;
                    }
                    PliSyntax.FormatItem next = format.get(item++);
                    if (next.code() == 'X') {
                        sink.accept(" ".repeat(next.width()));
                        continue;
                    }
                    String text;
                    if (next.code() == 'F') {
                        text = fixedField(number(value), next.width(), next.fraction());
                    } else {
                        text = character(value);
                        if (next.width() >= 0) {
                            text = text.length() >= next.width()
                                    ? text.substring(0, next.width())
                                    : text + " ".repeat(next.width() - text.length());
                        }
                    }
                    sink.accept(text);
                    consumedInCycle++;
                    break;
                }
            }
        }

        /**
         * {@code F(w,d)} の出力 (LRM "F-format item")。d 桁に<b>四捨五入</b>し (落ちる桁が 5 以上なら
         * 1 つ上の桁に 1 を足す)、w 桁の欄に右寄せする。1 未満なら点の前に 0 を置き、負なら負号を
         * 付ける。欄に入らなければ SIZE の状態である。
         */
        static String fixedField(BigDecimal value, int width, int fraction) {
            BigDecimal rounded = value.setScale(fraction, java.math.RoundingMode.HALF_UP);
            String text = (value.signum() < 0 ? "-" : "") + rounded.abs().toPlainString();
            if (text.length() > width) {
                throw new PliExecutionException("SIZE: " + value + " does not fit in F("
                        + width + "," + fraction + ")");
            }
            return " ".repeat(width - text.length()) + text;
        }

        /**
         * 出力のための文字への変換。属性の分からない算術値は断る。幅が属性で決まるので、
         * 近い形を黙って出すより止めたほうがよい (暫定判断 P-183)。
         */
        private static String character(Object value) {
            if (value instanceof Number) {
                throw new PliExecutionException("cannot write an arithmetic value whose"
                        + " precision is unknown (P-183): " + value);
            }
            if (value instanceof Bits bits) return bits.text();
            if (value instanceof Hex hex) return hex.text();
            return display(value);
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

        /**
         * 式を評価する。式の頭では、式のどこかに LIMITS の下の限りを超える被演算子があるかを
         * 先に調べ、式の中の演算の精度の上限を決める (Programming Guide "LIMITS")。
         */
        private Object value(PliSyntax.Expr expression, Env env) {
            if (arithmetic != null || expression instanceof PliSyntax.Literal
                    || expression instanceof PliSyntax.Reference) {
                return evaluate(expression, env);
            }
            boolean[] wide = new boolean[2];
            scan(expression, env, wide);
            arithmetic = new FixedValue.Arithmetic(options, wide[0], wide[1]);
            try {
                return evaluate(expression, env);
            } finally {
                arithmetic = null;
            }
        }

        /** 式の葉の精度を見る。wide[0] は 10 進、wide[1] は 2 進。 */
        private void scan(PliSyntax.Expr expression, Env env, boolean[] wide) {
            switch (expression) {
                case PliSyntax.Literal literal -> {
                    if (literal.value() instanceof FixedValue fixed && !fixed.binary()
                            && fixed.precision() > options.decimalLow()) {
                        wide[0] = true;
                    }
                }
                case PliSyntax.Reference reference -> {
                    Var variable = env.contains(reference.name())
                            ? env.require(reference.name()) : null;
                    if (variable == null) return;
                    if ((variable.type == PliSyntax.Type.DECIMAL
                            || variable.type == PliSyntax.Type.PICTURE)
                            && variable.precision > options.decimalLow()) {
                        wide[0] = true;
                    }
                    if (variable.type == PliSyntax.Type.BINARY
                            && variable.precision > options.binaryLow()) {
                        wide[1] = true;
                    }
                }
                case PliSyntax.Unary unary -> scan(unary.operand(), env, wide);
                case PliSyntax.Binary binary -> {
                    scan(binary.left(), env, wide);
                    scan(binary.right(), env, wide);
                }
                case PliSyntax.Function function ->
                        function.arguments().forEach(argument -> scan(argument, env, wide));
            }
        }

        private Object evaluate(PliSyntax.Expr expression, Env env) {
            if (expression instanceof PliSyntax.Literal literal) {
                return literal.value();
            }
            if (expression instanceof PliSyntax.Reference reference) {
                return env.require(reference.name()).read(context);
            }
            if (expression instanceof PliSyntax.Unary unary) {
                Object operand = value(unary.operand(), env);
                return switch (unary.operator()) {
                    // 2 ビット以上のビット列はビットごとに反転する
                    case "^", "¬" -> operand instanceof PliSyntax.BitString bits
                            ? bitwise(bits.bits(), "", (x, y) -> x == '1' ? '0' : '1')
                            : !truth(operand);
                    case "-" -> operand instanceof FixedValue fixed ? fixed.negate()
                            : number(operand).negate();
                    case "+" -> operand instanceof FixedValue ? operand : number(operand);
                    default -> throw new PliExecutionException("unknown unary operator "
                            + unary.operator());
                };
            }
            if (expression instanceof PliSyntax.Binary binary) {
                Object left = value(binary.left(), env);
                boolean logical = binary.operator().equals("|") || binary.operator().equals("&");
                // 1 ビットどうしなら、左で決まるときは右を評価しない。2 ビット以上のビット列は
                // ビットごとに演算する (LRM "Bit operations")。以前は真偽の演算にしていた (P-185)
                if (logical && !(left instanceof PliSyntax.BitString)) {
                    if (binary.operator().equals("|") && truth(left)) return true;
                    if (binary.operator().equals("&") && !truth(left)) return false;
                }
                Object right = value(binary.right(), env);
                if (logical && (left instanceof PliSyntax.BitString
                        || right instanceof PliSyntax.BitString)) {
                    char and = binary.operator().equals("&") ? '&' : '|';
                    return bitwise(bitText(left), bitText(right), (x, y) -> and == '&'
                            ? (x == '1' && y == '1' ? '1' : '0')
                            : (x == '1' || y == '1' ? '1' : '0'));
                }
                return switch (binary.operator()) {
                    case "|" -> truth(left) || truth(right);
                    case "&" -> truth(left) && truth(right);
                    case "||" -> display(left) + display(right);
                    case "+", "-", "*", "/" -> arithmetic(binary.operator(), left, right,
                            arithmetic == null ? new FixedValue.Arithmetic(options, false, false)
                                    : arithmetic);
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
                // 結果は x と同じ base・scale・precision を持つ (LRM "ABS")
                case "ABS" -> arguments.get(0) instanceof FixedValue fixed
                        ? (fixed.value().signum() < 0 ? fixed.negate() : fixed)
                        : number(arguments.get(0)).abs();
                // x を y 回<b>つなげ足す</b>ので y+1 個になる。y が 0 以下なら x そのもの (LRM "REPEAT")
                case "REPEAT" -> display(arguments.get(0))
                        .repeat(Math.max(0, number(arguments.get(1)).intValue()) + 1);
                default -> throw new PliExecutionException("PL/I built-in is not supported: " + name);
            };
        }

        /**
         * 算術演算。両方が属性を持てば、結果の属性を RULES(IBM) の規則で決める (LRM Table 28)。
         * 文字列など属性を持たない値が混ざれば、属性の無い値を返す。そうした値は出力できない
         * ({@link #character})。文字から算術への変換の属性はまだ持たない (P-183)。
         */
        private static Object arithmetic(String operator, Object left, Object right,
                                         FixedValue.Arithmetic rules) {
            if (left instanceof NumericPicture picture) left = picture.fixed();
            if (right instanceof NumericPicture picture) right = picture.fixed();
            if (left instanceof FixedValue a && right instanceof FixedValue b) {
                return switch (operator) {
                    case "+" -> a.add(b, rules);
                    case "-" -> a.subtract(b, rules);
                    case "*" -> a.multiply(b, rules);
                    default -> a.divide(b, rules);
                };
            }
            BigDecimal a = number(left);
            BigDecimal b = number(right);
            return switch (operator) {
                case "+" -> a.add(b);
                case "-" -> a.subtract(b);
                case "*" -> a.multiply(b);
                default -> a.divide(b, MathContext.DECIMAL128);
            };
        }

        private Object size(PliSyntax.Function function, Env env) {
            if (function.arguments().size() != 1
                    || !(function.arguments().get(0) instanceof PliSyntax.Reference reference)) {
                throw new PliExecutionException("SIZE requires one data reference");
            }
            // SIZE は FIXED BIN(31) を返す
            return FixedValue.binary(BigDecimal.valueOf(env.require(reference.name()).view.length()),
                    31, 0);
        }

        private Object address(PliSyntax.Function function, Env env) {
            if (function.arguments().size() != 1
                    || !(function.arguments().get(0) instanceof PliSyntax.Reference reference)) {
                throw new PliExecutionException("ADDR requires one data reference");
            }
            return new PointerValue(address(env.require(reference.name()).view));
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

        /**
         * CENTER / CENTRE は CENTERLEFT の略である。割り切れないときは余りの 1 桁を右に置く
         * (中央より 1 桁左に寄る)。3 つ目の引数は埋める文字で、省けば空白 (LRM "CENTERLEFT")。
         * 以前は 3 つ目を黙って捨てていた。
         */
        private static String centre(List<Object> arguments) {
            String text = display(arguments.get(0));
            int width = number(arguments.get(1)).intValue();
            String pad = " ";
            if (arguments.size() > 2) {
                pad = display(arguments.get(2));
                if (pad.length() != 1) {
                    throw new PliExecutionException("CENTER padding must be CHARACTER(1): '"
                            + pad + "'");
                }
            }
            if (text.length() >= width) return text.substring(0, width);
            int left = (width - text.length()) / 2;
            return pad.repeat(left) + text + pad.repeat(width - text.length() - left);
        }

        /** ビット列の値の字の並び。真偽値は 1 ビットである。 */
        private static String bitText(Object value) {
            if (value instanceof PliSyntax.BitString bits) return bits.bits();
            if (value instanceof Boolean bit) return bit ? "1" : "0";
            return display(value);
        }

        /** 短いほうを右に 0 で埋めて、ビットごとに演算する。 */
        private static Object bitwise(String left, String right,
                                      java.util.function.BinaryOperator<Character> operation) {
            int length = Math.max(left.length(), right.length());
            StringBuilder result = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                char x = i < left.length() ? left.charAt(i) : '0';
                char y = i < right.length() ? right.charAt(i) : '0';
                result.append(operation.apply(x, y));
            }
            return length == 1 ? (Object) (result.charAt(0) == '1')
                    : new PliSyntax.BitString(result.toString());
        }

        private static boolean isBits(Object value) {
            return value instanceof Boolean || value instanceof PliSyntax.BitString;
        }

        private static int compare(Object left, Object right) {
            if (numeric(left) || numeric(right)) {
                return number(left).compareTo(number(right));
            }
            if (left instanceof PointerValue a && right instanceof PointerValue b) {
                return Integer.compare(a.address(), b.address());
            }
            if (isBits(left) && isBits(right)) {
                // ビット列どうしは短いほうを右に 0 で埋めて比べる
                String a = bitText(left);
                String b = bitText(right);
                int length = Math.max(a.length(), b.length());
                return (a + "0".repeat(length - a.length()))
                        .compareTo(b + "0".repeat(length - b.length()));
            }
            if (left instanceof Boolean || right instanceof Boolean) {
                return Boolean.compare(truth(left), truth(right));
            }
            String a = display(left);
            String b = display(right);
            int width = Math.max(a.length(), b.length());
            return a.stripTrailing().compareTo(b.stripTrailing());
        }

        private static boolean numeric(Object value) {
            return value instanceof Number || value instanceof FixedValue
                    || value instanceof NumericPicture;
        }

        private static BigDecimal number(Object value) {
            if (value instanceof FixedValue fixed) return fixed.value();
            if (value instanceof NumericPicture picture) return picture.fixed().value();
            // ビット列を数にすると、符号なしの 2 進の値になる (LRM "Target: Coded arithmetic", BIT)
            if (value instanceof PliSyntax.BitString bits) {
                return new BigDecimal(new java.math.BigInteger(bits.bits(), 2));
            }
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof Number numeric) return new BigDecimal(numeric.toString());
            if (value instanceof Boolean bool) return bool ? BigDecimal.ONE : BigDecimal.ZERO;
            String text = display(value).strip();
            if (text.isEmpty()) {
                // 空の文字列と空白だけの文字列は 0 になり、CONVERSION は起きない (LRM "Target: Coded
                // arithmetic", CHARACTER)
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new PliExecutionException("value is not numeric: " + display(value));
            }
        }

        private static boolean truth(Object value) {
            if (value instanceof Boolean bool) return bool;
            if (numeric(value)) return number(value).signum() != 0;
            String text = display(value);
            // ビット列はどれか 1 ビットが 1 なら真 (LRM "IF statement")。'00'B は偽である
            if (!text.isEmpty() && text.chars().allMatch(c -> c == '0' || c == '1')) {
                return text.indexOf('1') >= 0;
            }
            return !text.isBlank();
        }

        private static String display(Object value) {
            if (value == null) return "";
            // 算術値から文字への変換は属性で決まる (LRM "Target: CHARACTER")。連結・CHAR・文字の
            // 変数への代入・出力が同じ規則を使う
            if (value instanceof FixedValue fixed) return fixed.toCharacter();
            // 数の PICTURE を文字にすると、字の形そのものになる
            if (value instanceof NumericPicture picture) return picture.text();
            // ビット列から文字への変換は '1' と '0' になる
            if (value instanceof Boolean bit) return bit ? "1" : "0";
            if (value instanceof PliSyntax.BitString bits) return bits.bits();
            if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
            return value.toString();
        }

        /** 変数の大きさ。POINTER も 4 byte の値を持つ。 */
        private static int byteLength(PliSyntax.Decl declaration) {
            return StructureMapping.length(declaration);
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
        /** ビット列が最初の byte の何ビット目から始まるか (左から 0)。UNALIGNED の要素だけが 0 でない。 */
        final int bitShift;
        /** 構造なら、宣言の順の要素 (名の無い * も含む)。 */
        final List<Var> members = new ArrayList<>();

        Var(String name, PliSyntax.Type type, int precision, int scale, DataView view) {
            this(name, type, precision, scale, view, 0);
        }

        Var(String name, PliSyntax.Type type, int precision, int scale, DataView view,
            int bitShift) {
            this.bitShift = bitShift;
            this.name = name.toUpperCase(Locale.ROOT);
            this.type = type;
            this.precision = precision;
            this.scale = scale;
            this.view = view;
        }

        Object read(ProgramContext context) {
            return switch (type) {
                case CHAR, GROUP -> context.codePage().decode(view.toByteArray());
                // ビット列は 8 ビットごとに 1 byte、左詰め (LRM Table 39)。値は '0' と '1' の並び
                case BIT -> new PliSyntax.BitString(bits());
                // 数の PICTURE は、出力では字をそのまま送り (LRM "For numeric character values, the
                // character value is transmitted")、演算では FIXED DEC(p,q) として扱う
                case PICTURE -> new NumericPicture(
                        context.codePage().decode(view.toByteArray()), precision, scale);
                // FIXED BIN(p,q) の記憶域は 2 の補数の整数で、値はそれを 2 の q 乗で割ったもの
                case BINARY -> FixedValue.binary(new BigDecimal(
                        new java.math.BigInteger(view.toByteArray()))
                        .divide(BigDecimal.valueOf(2).pow(Math.max(0, scale)))
                        .multiply(BigDecimal.valueOf(2).pow(Math.max(0, -scale))),
                        precision, scale);
                case DECIMAL -> FixedValue.decimal(PackedDecimal.decode(view.toByteArray(), scale,
                        NumProcMode.PFD).toBigDecimal(), precision, scale);
                case POINTER -> new PointerValue(view.length() < 4 ? 0
                        : java.nio.ByteBuffer.wrap(view.toByteArray(), 0, 4).getInt());
                case FILE, ENTRY -> "";
            };
        }

        void write(Object value, ProgramContext context) {
            switch (type) {
                case CHAR -> writeText(Executor.display(value), context);
                case BIT -> writeBits(Executor.display(value));
                case PICTURE -> writeText(picture(value), context);
                case GROUP -> {
                    if (Executor.numeric(value) && Executor.number(value).signum() == 0) {
                        view.fill((byte) 0);
                    } else {
                        writeText(Executor.display(value), context);
                    }
                }
                case BINARY -> writeBinary(Executor.number(value));
                case DECIMAL -> view.setBytes(PackedDecimal.encode(
                        Decimal.parse(Executor.number(value).toPlainString()), precision, scale, true));
                // POINTER へは POINTER の値 (ADDR や別の POINTER) だけが入る (LRM "Non-computational targets")
                case POINTER -> {
                    if (!(value instanceof PointerValue pointer)) {
                        throw new PliExecutionException("only a pointer can be assigned to "
                                + name + ": " + value);
                    }
                    view.setBytes(java.nio.ByteBuffer.allocate(4).putInt(pointer.address()).array());
                }
                case FILE, ENTRY -> throw new PliExecutionException(
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
                    shape[offset + 1] = switch (view.length()) {
                        case 2 -> 4;
                        case 4 -> 9;
                        case 8 -> 18;
                        default -> throw new PliExecutionException("FIXED BIN(" + precision
                                + ") cannot be an SQL host variable: " + name);
                    };
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

        /**
         * 2 の補数の整数として、記憶域の長さ (1 / 2 / 4 / 8 byte) に下位から書く。位取り q は 2 の q 乗を
         * 掛けて整数にし、小数の端は 0 の方へ切る。入りきらない上位は落ちる (SIZE は既定で無効であり、
         * 実機の ST / STH と同じく下位だけが残る)。以前は 2 か 4 byte しか書けなかった。
         */
        private void writeBinary(BigDecimal value) {
            BigDecimal scaled = scale >= 0
                    ? value.multiply(BigDecimal.valueOf(2).pow(scale))
                    : value.divide(BigDecimal.valueOf(2).pow(-scale));
            byte[] full = scaled.setScale(0, java.math.RoundingMode.DOWN).toBigInteger()
                    .toByteArray();
            byte[] bytes = new byte[view.length()];
            byte fill = full.length > 0 && full[0] < 0 ? (byte) 0xFF : 0;
            for (int i = 0; i < bytes.length; i++) {
                int from = full.length - bytes.length + i;
                bytes[i] = from < 0 ? fill : full[from];
            }
            view.setBytes(bytes);
        }

        /** ビット列を '0' と '1' の並びとして読む。 */
        private String bits() {
            StringBuilder text = new StringBuilder(precision);
            for (int i = 0; i < precision; i++) {
                int bit = bitShift + i;
                text.append((view.get(bit / 8) >> (7 - bit % 8) & 1) == 1 ? '1' : '0');
            }
            return text.toString();
        }

        /**
         * ビット列へ入れる。'0' と '1' 以外の字は CONVERSION であり、短ければ右を 0 で埋め、
         * 長ければ右を落とす。以前は 1 ビットを 1 字として文字のまま置いていた。
         */
        private void writeBits(String text) {
            // UNALIGNED の要素は前後の要素と byte を分け合うので、自分のビットだけを書き換える
            byte[] bytes = view.toByteArray();
            for (int i = 0; i < precision; i++) {
                char c = i < text.length() ? text.charAt(i) : '0';
                if (c != '0' && c != '1') {
                    throw new PliExecutionException("CONVERSION: '" + text
                            + "' is not a bit string for " + name);
                }
                int bit = bitShift + i;
                int mask = 1 << (7 - bit % 8);
                bytes[bit / 8] = (byte) (c == '1' ? bytes[bit / 8] | mask : bytes[bit / 8] & ~mask);
            }
            view.setBytes(bytes);
        }

        /**
         * 数の PICTURE へ入れる字。値を FIXED DEC(p,q) にし、V より右の q 桁で切り捨て、p 桁の数字に
         * する。字の値 (' 16918   ' など) はまず数に直す (空白だけなら 0)。以前は字をそのまま写していた。
         * 符号の字を持たない PICTURE に負の値は入らないので、黙って符号を落とさずに止める。
         */
        private String picture(Object value) {
            BigDecimal number = Executor.number(value).setScale(scale, java.math.RoundingMode.DOWN);
            if (number.signum() < 0) {
                throw new PliExecutionException("a negative value cannot be stored in the unsigned"
                        + " PICTURE of " + name + " yet: " + number);
            }
            String digits = number.movePointRight(scale).toBigInteger().toString();
            if (digits.length() > precision) {
                throw new PliExecutionException("SIZE: " + number + " does not fit the PICTURE of "
                        + name);
            }
            return "0".repeat(precision - digits.length()) + digits;
        }

        private void writeText(String value, ProgramContext context) {
            byte[] encoded = context.codePage().encode(value);
            view.fill(context.codePage().space());
            int length = Math.min(encoded.length, view.length());
            for (int i = 0; i < length; i++) view.set(i, encoded[i]);
        }
    }

    /**
     * POINTER の値。記憶域の位置に AddressSpace が振った番号で、0 は何も指さない (P-150)。
     */
    private record PointerValue(int address) {
    }

    /**
     * 数の PICTURE の値。字の形を持ち、演算に使うときだけ FIXED DEC(p,q) に直す。出力は字を
     * 送るだけなので、数字でない字が入っていても書ける。
     */
    private record NumericPicture(String text, int precision, int scale) {
        FixedValue fixed() {
            return FixedValue.decimal(Executor.number(text).movePointLeft(scale), precision, scale);
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
