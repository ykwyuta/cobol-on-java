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
    private static final Pattern SHA = Pattern.compile(
            "SHA(1|2|3)(DIGEST|INIT|UPDATE|FINAL)(224|256|384|512)?");

    private PliRuntime() {
    }

    /** 構文検査済みソースを実行する。 */
    public static void execute(String fileName, String source, ProgramContext context,
                               DataView[] arguments, ClassLoader loader) {
        PliSyntax.ParseResult parsed = PliSyntax.parse(fileName, source);
        if (!parsed.succeeded()) {
            throw new PliExecutionException(parsed.diagnostics().get(0).toString());
        }
        PliOptions options = PliOptions.fromSource(source);
        Runnable run = () -> new Executor(parsed.program(), context, arguments, loader, options).run();
        if (options.ccsid() == null) {
            run.run();
        } else {
            context.runWithCodePage(
                    dev.cobolonjava.runtime.codepage.CodePages.forName(options.ccsid().toString()), run);
        }
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

    /**
     * 起きた条件 (LRM "Conditions")。on-unit があればそこへ、無ければ既定の処置へ進む。
     *
     * @param source CONVERSION なら変換できなかった字の並び (ONSOURCE)。ほかは {@code null}
     */
    static final class Condition extends RuntimeException {
        final String name;
        final String qualifier;
        final int code;
        final String source;
        final Runnable resume;
        /** 条件が起きた手続きの名。最初に処置しようとした手続きで決まる。 */
        String procedure;

        Condition(String name, String qualifier, int code, String source) {
            this(name, qualifier, code, source, null);
        }

        Condition(String name, String qualifier, int code, String source, Runnable resume) {
            super(name + (qualifier == null ? "" : "(" + qualifier + ")"), null, false, false);
            this.name = name;
            this.qualifier = qualifier;
            this.code = code;
            this.source = source;
            this.resume = resume;
        }
    }

    /** CONVERSION の on-unit が直した字の並び。変換をやり直すときに、元の並びの代わりに使う。 */
    private static final ThreadLocal<Map<String, String>> REPAIRED =
            ThreadLocal.withInitial(HashMap::new);

    /** 条件の前置きで有効にした SIZE。既定では無効である (LRM "Condition prefixes")。 */
    private static final ThreadLocal<Boolean> SIZE_ENABLED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> STRINGSIZE_ENABLED = ThreadLocal.withInitial(() -> false);

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
        /** ファイル名から、最後の OPEN の TITLE で結んだ DD 名への表。 */
        private final Map<String, String> titles = new java.util.HashMap<>();
        /** *PROCESS の RULES と LIMITS。翻訳時に原文へ残した注記から読む。 */
        private final PliOptions options;
        /** 評価中の式の算術の規則。式の頭で決め、式の中では変えない。 */
        private FixedValue.Arithmetic arithmetic;
        private boolean conformanceEnabled;
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
                // 引数は参照で渡る。主手続き (OPTIONS(MAIN)) の POINTER の引数 (IMS の PCB など) は、
                // 渡された記憶域の番号を値に持つ。主でない手続きの POINTER の引数は、呼んだ側の
                // POINTER の変数そのものであり、代入すると呼んだ側の値が変わる。以前はどちらも前者と
                // して扱い、呼んだ先の代入が呼んだ側に届かなかった (z/OS probe の PLIPTRS)。
                // ほかの型と宣言すれば、渡された記憶域そのものになる (declareScalar)
                globals.put(program.main() || arguments[i].length() < 4
                        ? pointerTo(name, arguments[i])
                        : new Var(name, PliSyntax.Type.POINTER, 0, 0,
                                arguments[i].subView(0, 4)));
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

        /**
         * locator で修飾した参照 {@code Q->X} を、X の宣言の locator (BASED(P) の P) を Q の値に
         * 一時的に置き換えて扱う (LRM "Locator qualification")。済んだら P を元の値に戻す。
         */
        private <T> T located(String qualified, Env env,
                              java.util.function.Function<String, T> action) {
            int arrow = qualified.indexOf("->");
            String locatorName = qualified.substring(0, arrow).toUpperCase(Locale.ROOT);
            String rest = qualified.substring(arrow + 2);
            String root = rest.split("\\.|->")[0].toUpperCase(Locale.ROOT);
            List<PliSyntax.Decl> chunk = null;
            for (List<PliSyntax.Decl> candidate : declaredChunks.values()) {
                if (candidate.stream().anyMatch(d -> d.name().equalsIgnoreCase(root))) {
                    chunk = candidate;
                }
            }
            if (chunk == null || chunk.get(0).basedOn() == null) {
                throw new PliExecutionException(qualified + ": " + root
                        + " is not BASED on a POINTER");
            }
            Var locator = env.require(locatorName);
            if (locator.type != PliSyntax.Type.POINTER) {
                throw new PliExecutionException(qualified + ": " + locatorName
                        + " is not a POINTER");
            }
            String implicit = chunk.get(0).basedOn().toUpperCase(Locale.ROOT);
            if (implicit.equals(locatorName)) {
                return action.apply(rest);
            }
            Var base = env.require(implicit);
            Object saved = base.read(context);
            base.write(locator.read(context), context);
            rebase(base.name);
            try {
                return action.apply(rest);
            } finally {
                env.require(implicit).write(saved, context);
                rebase(implicit);
            }
        }

        /** REFER の対象の名から、その構造の locator の名。 */
        private final Map<String, String> referObjects = new HashMap<>();

        private static String referKey(String target) {
            String name = target.toUpperCase(Locale.ROOT);
            return name.substring(name.lastIndexOf('.') + 1);
        }

        /** 動いている内部手続きの環境。手続きの名ごとに、新しい呼び出しが上。 */
        private final Map<String, java.util.ArrayDeque<Env>> activations = new HashMap<>();

        /** FETCH した入口の名から、ロードモジュールの名 (TITLE)。 */
        private final Map<String, String> fetched = new HashMap<>();

        /** 今動いている手続きの名。主手続きなら空。STATIC の記憶域を引く鍵に使う。 */
        private String scope = "";

        /**
         * STATIC の宣言 (LRM "STATIC attribute")。記憶域は実行単位で 1 つで、手続きに入るたびに
         * 取り直さず、INITIAL も最初の 1 度だけ効く。以前は AUTOMATIC と同じに扱っており、
         * 呼ぶたびに INITIAL の値へ戻っていた。
         */
        private void declareStatic(List<PliSyntax.Decl> chunk, Env env) {
            Map<String, Map<String, Var>> statics = STATICS.computeIfAbsent(context.registers(),
                    ignored -> new HashMap<>());
            String key = program.name().toUpperCase(Locale.ROOT) + "." + scope + "."
                    + chunk.get(0).name().toUpperCase(Locale.ROOT);
            Map<String, Var> saved = statics.get(key);
            if (saved != null) {
                env.variables.putAll(saved);
                return;
            }
            Map<String, Var> before = new HashMap<>(env.variables);
            declare(chunk, env);
            Map<String, Var> bindings = new HashMap<>();
            for (Map.Entry<String, Var> entry : env.variables.entrySet()) {
                if (before.get(entry.getKey()) != entry.getValue()) {
                    bindings.put(entry.getKey(), entry.getValue());
                }
            }
            statics.put(key, bindings);
        }

        /** 最上位の名から、その宣言の塊。ALLOCATE が宣言をたどるのに使う。 */
        private final Map<String, List<PliSyntax.Decl>> declaredChunks = new HashMap<>();

        /**
         * {@code ALLOCATE}。CONTROLLED なら新しい世代を宣言し直し、前の世代の束縛を退避する。
         * BASED なら記憶域を取り、その番地を locator (SET か BASED(P) の P) へ入れる
         * (LRM "ALLOCATE statement for controlled variables" / "for based variables")。
         */
        private void allocate(PliSyntax.AllocateItem item, Env env) {
            String name = item.name().toUpperCase(Locale.ROOT);
            Var current = env.find(name);
            if (current != null && current.generations != null) {
                Generations generations = current.generations;
                List<PliSyntax.Decl> chunk = new ArrayList<>(generations.chunk);
                PliSyntax.Decl root = chunk.get(0);
                if (!item.extents().isEmpty()) {
                    if (item.extents().size() != root.dimensions().size()) {
                        throw new PliExecutionException("ALLOCATE " + name + " gives "
                                + item.extents().size() + " bound(s), but it has "
                                + root.dimensions().size() + " dimension(s)");
                    }
                    List<PliSyntax.Bound> bounds = new ArrayList<>();
                    for (PliSyntax.Expr extent : item.extents()) {
                        bounds.add(new PliSyntax.Bound(1, number(value(extent, env)).intValue()));
                    }
                    chunk.set(0, root.withDimensions(bounds));
                } else if (root.dimensions().contains(PliSyntax.Bound.STAR)) {
                    // (*) の上下限は、ALLOCATE に書くか、前の世代から受け継ぐ。前の世代から
                    // 受け継ぐ規則はまだ持たない
                    throw new PliExecutionException("ALLOCATE " + name
                            + " needs bounds for its (*) dimensions");
                }
                Env owner = generations.owner;
                Map<String, Var> before = new HashMap<>(owner.variables);
                declare(chunk, owner);
                Map<String, Var> saved = new HashMap<>();
                for (Map.Entry<String, Var> entry : owner.variables.entrySet()) {
                    Var previous = before.get(entry.getKey());
                    if (previous != entry.getValue()) {
                        saved.put(entry.getKey(), previous);
                        entry.getValue().generations = generations;
                    }
                }
                generations.saved.push(saved);
                rebindParameter(name, generations, env);
                return;
            }
            List<PliSyntax.Decl> chunk = declaredChunks.get(name);
            if (chunk == null || !"BASED".equals(chunk.get(0).storage())) {
                throw new PliExecutionException(name + " is not BASED or CONTROLLED");
            }
            PliSyntax.Decl root = chunk.get(0);
            String locator = item.set() != null ? item.set() : root.basedOn();
            if (locator == null) {
                throw new PliExecutionException("ALLOCATE " + name
                        + " needs SET, because it is BASED without a locator");
            }
            for (PliSyntax.Decl decl : chunk) {
                if (decl.initial() != null || !decl.initialItems().isEmpty()) {
                    // ALLOCATE は BASED の INITIAL を効かせる。重ね直しは INITIAL を書かないので、
                    // 黙って抜かすより断る
                    throw new PliExecutionException("INITIAL of a BASED variable on ALLOCATE"
                            + " is not supported yet: " + name);
                }
            }
            List<PliSyntax.Decl> shape = hasRefer(chunk) ? allocationShape(chunk, env) : chunk;
            int length;
            StructureMapping.Result mapping = null;
            try {
                if (shape.size() > 1) {
                    mapping = StructureMapping.map(shape);
                    length = mapping.size();
                } else {
                    length = byteLength(root);
                }
            } catch (IllegalArgumentException unsupported) {
                throw new PliExecutionException(unsupported.getMessage());
            }
            DataView storage;
            int areaOffset = -1;
            if (item.in() != null) {
                // AREA の中に取る。次の空きから、倍語の境界にそろえて置く
                Var area = env.require(item.in());
                if (area.type != PliSyntax.Type.AREA) {
                    throw new PliExecutionException(item.in() + " is not an AREA");
                }
                areaOffset = (area.areaUsed() + 7) / 8 * 8;
                if (areaOffset + Math.max(1, length) > area.view.length()) {
                    throw new Condition("AREA", null, 360, null);
                }
                area.areaUse(areaOffset + Math.max(1, length));
                storage = area.view.subView(areaOffset, Math.max(1, length));
            } else {
                storage = Storage.allocate(Math.max(1, length)).whole();
            }
            if (shape != chunk) {
                // ALLOCATE は REFER の対象に、宣言の式で決めた上限を入れる (LRM "REFER option")
                for (PliSyntax.Decl decl : shape) {
                    for (PliSyntax.Bound bound : decl.dimensions()) {
                        if (bound.refer() == null) continue;
                        int j = indexOf(shape, bound.refer());
                        PliSyntax.Decl object = shape.get(j);
                        Var target = new Var(object.name(), object.type(), object.precision(),
                                object.scale(), storage.subView(mapping.byteOffset(j),
                                        mapping.byteLength(j)), mapping.bitShift(j));
                        target.unsigned = object.unsigned();
                        target.write(FixedValue.binary(BigDecimal.valueOf(bound.high()), 31, 0),
                                context);
                    }
                }
            }
            Var pointer = env.require(locator);
            if (pointer.type == PliSyntax.Type.OFFSET && areaOffset >= 0) {
                // OFFSET の locator には AREA の頭からの位置を入れる
                pointer.write(FixedValue.binary(BigDecimal.valueOf(areaOffset), 31, 0), context);
                return;
            }
            if (pointer.type != PliSyntax.Type.POINTER) {
                throw new PliExecutionException("SET(" + locator + ") needs a POINTER");
            }
            pointer.write(new PointerValue(address(storage)), context);
            rebase(pointer.name);
        }

        /** {@code FREE}。CONTROLLED なら前の世代に戻す。BASED の記憶域は取り戻さない。 */
        private void free(String freed, Env env) {
            String name = freed.toUpperCase(Locale.ROOT);
            Var current = env.find(name);
            if (current != null && current.generations != null) {
                Generations generations = current.generations;
                if (generations.saved.isEmpty()) {
                    throw new PliExecutionException("FREE " + name + ", which is not allocated");
                }
                Map<String, Var> saved = generations.saved.pop();
                for (Map.Entry<String, Var> entry : saved.entrySet()) {
                    if (entry.getValue() == null) {
                        generations.owner.variables.remove(entry.getKey());
                    } else {
                        generations.owner.variables.put(entry.getKey(), entry.getValue());
                    }
                }
                rebindParameter(name, generations, env);
                return;
            }
            List<PliSyntax.Decl> chunk = declaredChunks.get(name);
            if (chunk == null || !"BASED".equals(chunk.get(0).storage())) {
                throw new PliExecutionException(name + " is not BASED or CONTROLLED");
            }
            // BASED の記憶域は Java の記憶域であり、取り戻すのは GC である。解いたあとの参照が
            // 誤りであることは検出しない
        }

        /**
         * 引数として渡された CONTROLLED (仮引数の名で ALLOCATE / FREE した) なら、仮引数の名を
         * 呼んだ側の今の世代へ結び直す。呼んだ側の名は宣言の環境で置き換わっている。
         */
        private void rebindParameter(String name, Generations generations, Env env) {
            String root = generations.chunk.get(0).name().toUpperCase(Locale.ROOT);
            if (!name.equals(root)) {
                env.alias(name, generations.owner.variables.get(root));
            }
        }

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
            if (address == 0 || address == NULL_ADDRESS) {
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

        /**
         * 宣言中の INITIAL を後回しにする置き場。{@code null} なら宣言したその場で初期化する。
         * {@link #declareAll} が、すべての記憶域を取ってから初期化するために使う。
         */
        private List<Runnable> deferredInits;

        /**
         * 手続きの宣言をすべて、本体を動かす前に済ませる。
         *
         * <p>PL/I の宣言は実行する文ではなく、手続きに入ったときに、書いた場所によらず確立する
         * (LRM "Scopes of declarations")。以前は宣言を文として上から順に動かしていたので、
         * {@code DCL V CHAR(4) BASED(P);} を {@code DCL P POINTER;} より前に書くと「宣言されて
         * いない名」で止まっていた。z/OS probe の PLIMAP の下書きで見つかった。
         *
         * <p>順は 3 段である。(1) BASED でないものの記憶域を取る、(2) その INITIAL を書いた順に
         * 評価する ({@code INIT(ADDR(S))} は S が後に書かれていてもよい)、(3) BASED のものを
         * 重ねる (重ねる先の POINTER は (2) で値を持っている)。
         */
        private void declareAll(List<PliSyntax.Stmt> body, Env env) {
            List<List<PliSyntax.Decl>> chunks = new ArrayList<>();
            collectDeclarations(body, chunks);
            List<Runnable> inits = new ArrayList<>();
            deferredInits = inits;
            try {
                for (List<PliSyntax.Decl> chunk : chunks) {
                    declaredChunks.put(chunk.get(0).name().toUpperCase(Locale.ROOT), chunk);
                    if (chunk.get(0).basedOn() != null && hasRefer(chunk)) {
                        for (PliSyntax.Decl decl : chunk) {
                            for (PliSyntax.Bound bound : decl.dimensions()) {
                                if (bound.refer() != null) {
                                    referObjects.put(bound.refer().toUpperCase(Locale.ROOT),
                                            chunk.get(0).basedOn().toUpperCase(Locale.ROOT));
                                }
                            }
                        }
                    }
                    if (chunk.get(0).controlled() && !env.arguments.containsKey(
                            chunk.get(0).name().toUpperCase(Locale.ROOT))) {
                        // CONTROLLED は ALLOCATE するまで記憶域を持たない (LRM "CONTROLLED
                        // storage")。名だけを置き、世代の積み重ねをそこに持たせる
                        PliSyntax.Decl root = chunk.get(0);
                        Var placeholder = new Var(root.name(), root.type(), root.precision(),
                                root.scale(), Storage.allocate(0).whole());
                        placeholder.generations = new Generations(chunk, env);
                        placeholder.unallocated = true;
                        env.put(placeholder);
                    } else if ("STATIC".equals(chunk.get(0).storage())
                            && !env.arguments.containsKey(
                                    chunk.get(0).name().toUpperCase(Locale.ROOT))) {
                        declareStatic(chunk, env);
                    } else if (chunk.get(0).basedOn() == null && chunk.get(0).defined() == null) {
                        declare(chunk, env);
                    }
                }
            } finally {
                deferredInits = null;
            }
            inits.forEach(Runnable::run);
            for (List<PliSyntax.Decl> chunk : chunks) {
                // DEFINED は基の記憶域に重ねるので、基が値を持ってから宣言する
                if (chunk.get(0).basedOn() != null || chunk.get(0).defined() != null) {
                    declare(chunk, env);
                }
            }
        }

        /** 宣言を、最上位の 1 つ (スカラーか、構造とその要素) ずつの塊に分けて集める。 */
        private static void collectDeclarations(List<PliSyntax.Stmt> body,
                                                List<List<PliSyntax.Decl>> chunks) {
            for (PliSyntax.Stmt statement : body) {
                switch (statement) {
                    case PliSyntax.Declare declare -> {
                        List<PliSyntax.Decl> chunk = null;
                        for (PliSyntax.Decl decl : declare.declarations()) {
                            if (decl.level() <= 1 || chunk == null) {
                                chunk = new ArrayList<>();
                                chunks.add(chunk);
                            }
                            chunk.add(decl);
                        }
                    }
                    case PliSyntax.Block block -> collectDeclarations(block.body(), chunks);
                    case PliSyntax.Group group -> collectDeclarations(group.body(), chunks);
                    case PliSyntax.Loop loop -> collectDeclarations(loop.body(), chunks);
                    case PliSyntax.IterativeLoop loop -> collectDeclarations(loop.body(), chunks);
                    case PliSyntax.OnEndFile on -> collectDeclarations(on.handler(), chunks);
                    case PliSyntax.If branch -> {
                        collectDeclarations(List.of(branch.whenTrue()), chunks);
                        if (branch.whenFalse() != null) {
                            collectDeclarations(List.of(branch.whenFalse()), chunks);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        void run() {
            sysprint.enter();
            try {
                if (program.packageName() != null) {
                    declareAll(program.packageBody(), globals);
                }
                Env main = program.packageName() == null ? globals : new Env(globals, context);
                if (main != globals) {
                    main.arguments.putAll(globals.arguments);
                }
                activations.computeIfAbsent(program.name(), key -> new java.util.ArrayDeque<>())
                        .push(main);
                try {
                    declareAll(program.body(), main);
                    execute(program.body(), main);
                } finally {
                    activations.get(program.name()).pop();
                }
            } catch (ReturnSignal | StopSignal ignored) {
                // 主手続きの RETURN と STOP は正常終了である。
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

        private static String conditionKey(String condition, String qualifier) {
            return qualifier == null ? condition : condition + "(" + qualifier + ")";
        }

        /** いま処置している条件の ONCODE と ONSOURCE / ONCHAR。 */
        private int onCode;
        private String onSource;
        /** いま処置している条件の名と、CONDITION の名 (ONCONDID、ONCONDCOND)。 */
        private String onConditionName;
        private String onConditionQualifier;
        /** 条件が起きた手続きの名 (ONPROCEDURE、ONLOC)。 */
        private String onProcedureName;
        /** on-unit が ONSOURCE / ONCHAR に入れた直し。正常に戻れば変換をやり直す。 */
        private String repairedSource;

        /**
         * 文を 1 つ動かし、起きた条件を処置する。
         *
         * <p>on-unit は条件の起きた場所で呼ばれ、正常に戻れば文の次へ進む。CONVERSION だけは、
         * on-unit が ONSOURCE か ONCHAR を直していれば、その文を直した字の並びでやり直す
         * (LRM "CONVERSION condition")。on-unit の中の GO TO は on-unit を抜けて飛ぶ。
         * on-unit が無ければ既定の処置である。CONDITION は何もせずに続け、ほかは止める。
         */
        private void execute(PliSyntax.Stmt statement, Env env) {
            for (int attempt = 0; ; attempt++) {
                try {
                    executeStatement(statement, env);
                    return;
                } catch (Condition condition) {
                    Env.Handler handler = env.handler(conditionKey(condition.name,
                            condition.qualifier));
                    if (handler == null || handler.system()) {
                        if (condition.name.equals("CONDITION")) {
                            return;
                        }
                        if (condition.name.equals("UNDERFLOW") && condition.resume != null) {
                            condition.resume.run();
                            return;
                        }
                        // 既定の処置は ERROR を起こすことである。ERROR の on-unit があれば動かし、
                        // 正常に戻っても実行は終わる (LRM "ERROR condition")
                        if (!condition.name.equals("ERROR")) {
                            runErrorHandler(condition.code, env);
                        }
                        throw new PliExecutionException(condition.name
                                + (condition.source == null ? "" : ": '" + condition.source
                                + "' cannot be converted"));
                    }
                    int savedCode = onCode;
                    String savedSource = onSource;
                    String savedName = onConditionName;
                    String savedQualifier = onConditionQualifier;
                    String savedProcedure = onProcedureName;
                    if (condition.procedure == null) condition.procedure = currentProcedure();
                    onProcedureName = condition.procedure;
                    onCode = condition.code;
                    onSource = condition.source;
                    onConditionName = condition.name;
                    onConditionQualifier = condition.qualifier;
                    repairedSource = null;
                    try {
                        execute(handler.statements(), new Env(handler.established(), context));
                    } finally {
                        onCode = savedCode;
                        onSource = savedSource;
                        onConditionName = savedName;
                        onConditionQualifier = savedQualifier;
                        onProcedureName = savedProcedure;
                    }
                    if (!condition.name.equals("CONVERSION") || repairedSource == null
                            || attempt > 16) {
                        if (condition.name.equals("CONVERSION")) {
                            throw new PliExecutionException("CONVERSION: the on-unit returned"
                                    + " without repairing '" + condition.source + "'");
                        }
                        if (condition.resume != null) {
                            condition.resume.run();
                        }
                        if (condition.name.equals("CONFORMANCE")) {
                            runErrorHandler(condition.code, env);
                            throw new PliExecutionException("CONFORMANCE: the on-unit returned");
                        }
                        return;
                    }
                    REPAIRED.get().put(condition.source, repairedSource);
                }
            }
        }

        private void runErrorHandler(int code, Env env) {
            Env.Handler error = env.handler("ERROR");
            if (error != null && !error.system()) {
                int savedCode = onCode;
                onCode = code;
                try {
                    execute(error.statements(), new Env(error.established(), context));
                } finally {
                    onCode = savedCode;
                }
            }
        }

        private void executeStatement(PliSyntax.Stmt statement, Env env) {
            try {
                executeStatementOnce(statement, env);
            } finally {
                if (!REPAIRED.get().isEmpty() && onSource == null) {
                    REPAIRED.get().clear();
                }
            }
        }

        private void executeStatementOnce(PliSyntax.Stmt statement, Env env) {
            if (statement instanceof PliSyntax.Declare) {
                // 手続きに入ったときに declareAll が済ませている
                return;
            } else if (statement instanceof PliSyntax.Assign assign
                    && referObjects.containsKey(referKey(assign.target()))) {
                assign(assign, env);
                // REFER の対象が変わると構造の形が変わる。重ね直す
                rebase(referObjects.get(referKey(assign.target())));
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
                    if (!body(loop.body(), env, loop.label())) {
                        break;
                    }
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
            } else if (statement instanceof PliSyntax.Return result) {
                returned = result.value() == null ? null : value(result.value(), env);
                throw ReturnSignal.INSTANCE;
            } else if (statement instanceof PliSyntax.Allocate allocate) {
                allocate.items().forEach(item -> allocate(item, env));
            } else if (statement instanceof PliSyntax.Free free) {
                free.names().forEach(name -> free(name, env));
            } else if (statement instanceof PliSyntax.AssignByName byName) {
                assignByName(env.require(byName.target()), env.require(byName.source()));
            } else if (statement instanceof PliSyntax.Fetch fetch) {
                for (int i = 0; i < fetch.entries().size(); i++) {
                    String entry = fetch.entries().get(i).toUpperCase(Locale.ROOT);
                    String module = fetch.titles().get(i) == null ? entry
                            : character(value(fetch.titles().get(i), env)).strip()
                                    .toUpperCase(Locale.ROOT);
                    try {
                        context.resolve(module, loader);
                    } catch (dev.cobolonjava.runtime.program.ProgramNotFoundException missing) {
                        // モジュールが見つからない場合は 9250。9251 は読み込み時の伝送エラー。
                        // Zeus の vm/mod.rs と同じ。実機とは突き合わせていない。
                        throw new Condition("ERROR", null, 9250, null);
                    }
                    fetched.put(entry, module);
                }
            } else if (statement instanceof PliSyntax.Release release) {
                for (String entry : release.entries()) {
                    String module = fetched.getOrDefault(entry.toUpperCase(Locale.ROOT),
                            entry.toUpperCase(Locale.ROOT));
                    // 読み込みを忘れ、その STATIC も捨てる。次の FETCH は初めの状態から始まる
                    Ops.cancel(context, module);
                    releaseStatics(context, module);
                }
            } else if (statement instanceof PliSyntax.Get get) {
                getString(get, env);
            } else if (statement instanceof PliSyntax.Select select) {
                Object subject = select.subject() == null ? null : value(select.subject(), env);
                for (PliSyntax.When when : select.whens()) {
                    for (PliSyntax.Expr candidate : when.values()) {
                        Object value = value(candidate, env);
                        if (subject == null ? truth(value) : compare(subject, value) == 0) {
                            execute(when.action(), env);
                            return;
                        }
                    }
                }
                if (select.otherwise() != null) {
                    execute(select.otherwise(), env);
                } else {
                    // 当たる WHEN も OTHERWISE も無ければ ERROR である (LRM "SELECT statement")
                    throw new Condition("ERROR", null, 3, null);
                }
            } else if (statement instanceof PliSyntax.OnCondition on) {
                env.establish(conditionKey(on.condition(), on.qualifier()),
                        new Env.Handler(on.handler(), env, on.system()));
            } else if (statement instanceof PliSyntax.Revert revert) {
                env.revert(conditionKey(revert.condition(), revert.qualifier()));
            } else if (statement instanceof PliSyntax.Signal signal) {
                if (signal.condition().equals("CONFORMANCE") && !conformanceEnabled) {
                    return;
                }
                throw new Condition(signal.condition(), signal.qualifier(),
                        switch (signal.condition()) {
                            case "CONDITION" -> 500;
                            case "ERROR" -> 9;
                            case "CONFORMANCE" -> 550;
                            case "ATTENTION" -> 400;
                            default -> 0;
                        }, null);
            } else if (statement instanceof PliSyntax.Prefixed prefixed) {
                boolean before = SIZE_ENABLED.get();
                boolean stringSizeBefore = STRINGSIZE_ENABLED.get();
                boolean conformanceBefore = conformanceEnabled;
                if (prefixed.enabled().contains("SIZE")) {
                    SIZE_ENABLED.set(true);
                }
                if (prefixed.disabled().contains("SIZE")) {
                    SIZE_ENABLED.set(false);
                }
                if (prefixed.enabled().contains("STRINGSIZE")) {
                    STRINGSIZE_ENABLED.set(true);
                }
                if (prefixed.disabled().contains("STRINGSIZE")) {
                    STRINGSIZE_ENABLED.set(false);
                }
                if (prefixed.enabled().contains("CONFORMANCE")) {
                    conformanceEnabled = true;
                }
                if (prefixed.disabled().contains("CONFORMANCE")) {
                    conformanceEnabled = false;
                }
                try {
                    execute(prefixed.statement(), env);
                } finally {
                    SIZE_ENABLED.set(before);
                    STRINGSIZE_ENABLED.set(stringSizeBefore);
                    conformanceEnabled = conformanceBefore;
                }
            } else if (statement instanceof PliSyntax.Leave leave) {
                throw new LoopSignal(true, leave.label());
            } else if (statement instanceof PliSyntax.Iterate iterate) {
                throw new LoopSignal(false, iterate.label());
            } else if (statement instanceof PliSyntax.Delay delay) {
                long milliseconds = number(value(delay.milliseconds(), env))
                        .max(BigDecimal.ZERO).min(BigDecimal.valueOf(86_400_000)).longValue();
                try {
                    Thread.sleep(milliseconds);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new PliExecutionException("DELAY interrupted");
                }
            } else if (statement instanceof PliSyntax.Flush flush) {
                // RECORD ファイルはレコード単位で書き込み済み。PRINT の保留中の行を送る。
                if (flush.file() == null) {
                    printFiles.values().forEach(UserPrintFile::flushPending);
                } else {
                    UserPrintFile file = printFiles.get(flush.file().toUpperCase(Locale.ROOT));
                    if (file != null) {
                        file.flushPending();
                    }
                }
            } else if (statement instanceof PliSyntax.Stop) {
                throw StopSignal.INSTANCE;
            } else if (statement instanceof PliSyntax.Block block) {
                execute(block.body(), env);
            } else if (statement instanceof PliSyntax.Group group) {
                try {
                    execute(group.body(), env);
                } catch (LoopSignal signal) {
                    // 繰り返さない DO も LEAVE で出られる。ITERATE は繰り返す DO のものなので、
                    // 名前を書かない ITERATE は外の繰り返しへ渡す
                    boolean mine = signal.label == null ? signal.leave
                            : signal.label.equalsIgnoreCase(group.label());
                    if (!mine) {
                        throw signal;
                    }
                }
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

        /** 関数の手続きが RETURN(値) で返した値。呼んだ側がすぐに取り出す。 */
        private Object returned;

        /**
         * 繰り返しの本体を 1 回動かす。
         *
         * @return LEAVE で出たなら {@code false}。ITERATE と、ふつうに終わったときは {@code true}
         */
        private boolean body(List<PliSyntax.Stmt> statements, Env env, String label) {
            try {
                execute(statements, env);
                return true;
            } catch (LoopSignal signal) {
                if (signal.label != null && !signal.label.equalsIgnoreCase(label)) {
                    throw signal; // 外側の、その名前の DO が受ける
                }
                return !signal.leave;
            }
        }

        private void iterativeLoop(PliSyntax.IterativeLoop loop, Env env) {
            Var control = env.require(loop.control());
            Object start = value(loop.start(), env);
            BigDecimal finish = number(value(loop.finish(), env));
            BigDecimal step = number(value(loop.step(), env));
            if (step.signum() == 0) {
                throw new PliExecutionException("DO step must not be zero: " + loop.control());
            }
            control.write(start, context);
            int guard = 0;
            while (number(control.read(context)).compareTo(finish) * step.signum() <= 0) {
                if (!body(loop.body(), env, loop.label())) {
                    break;
                }
                BigDecimal current = number(control.read(context));
                control.write(current.add(step), context);
                if (++guard > 10_000_000) {
                    throw new PliExecutionException("loop iteration limit exceeded");
                }
            }
        }

        private void declare(List<PliSyntax.Decl> declarations, Env env) {
            String unsupported = declarations.stream().map(PliSyntax.Decl::unsupported)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (unsupported != null) {
                // まだ持たない型の宣言。名だけを置き、使ったら止める
                for (PliSyntax.Decl decl : declarations) {
                    Var placeholder = new Var(decl.name(), decl.type(), decl.precision(),
                            decl.scale(), Storage.allocate(0).whole());
                    placeholder.unsupported = unsupported;
                    env.put(placeholder);
                }
                return;
            }
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
            if (tree.stream().anyMatch(decl -> decl.defined() != null)) {
                throw new PliExecutionException("DEFINED structures are not supported yet: "
                        + root.name());
            }
            StructureMapping.Result mapping;
            try {
                mapping = StructureMapping.map(tree);
            } catch (IllegalArgumentException unsupported) {
                throw new PliExecutionException(unsupported.getMessage());
            }
            int length = mapping.size();
            DataView area;
            if (root.basedOn() != null && nullPointer(root.basedOn(), env)) {
                // NULL の POINTER に重ねた宣言は誤りではない。誤りなのは、値を持たないまま
                // 参照することである。POINTER に値が入ったときに重ねる
                unbound(tree, root.basedOn(), env);
                remember(root.basedOn(), () -> declareGroup(tree, env));
                return;
            }
            List<PliSyntax.Decl> shape = tree;
            if (root.basedOn() != null && hasRefer(tree)) {
                // REFER の構造の形は、記憶域の中の対象の今の値で決まる
                shape = resolveRefer(tree, mapping, env);
                try {
                    mapping = StructureMapping.map(shape);
                } catch (IllegalArgumentException unsupported) {
                    throw new PliExecutionException(unsupported.getMessage());
                }
                length = mapping.size();
            }
            if (root.basedOn() != null) {
                area = basedView(root.basedOn(), length, env);
                remember(root.basedOn(), () -> declareGroup(tree, env));
            } else if (parameters.containsKey(root.name().toUpperCase(Locale.ROOT))) {
                area = argument(root.name(), length);
            } else {
                area = Storage.allocate(length).whole();
            }
            Var group = new Var(root.name(), PliSyntax.Type.GROUP, length, 0, area);
            group.littleEndian = "LITTLE".equals(root.byteOrder());
            env.put(group);
            // BASED の構造は自分の記憶域を持たず、別のものの上に重ねる。INITIAL は ALLOCATE で
            // 記憶域を取ったときにしか効かないので、宣言で重ねた先を書き換えてはならない。
            // 以前は文字の要素を空白で埋めており、IMS から渡された DB PCB の DBD 名と PROCOPT を消していた
            boolean based = root.basedOn() != null;
            declareMembers(shape, 0, shape.size(), env, group, area, mapping, root.name(), based);
        }

        private static boolean hasRefer(List<PliSyntax.Decl> tree) {
            return tree.stream().anyMatch(decl -> decl.dimensions().stream()
                    .anyMatch(bound -> bound.refer() != null));
        }

        private static int indexOf(List<PliSyntax.Decl> tree, String name) {
            for (int i = 0; i < tree.size(); i++) {
                if (tree.get(i).name().equalsIgnoreCase(name)) return i;
            }
            throw new PliExecutionException("REFER object " + name + " is not in the structure");
        }

        /** REFER の上限を、記憶域にある対象の今の値に置き換えた宣言の塊。 */
        private List<PliSyntax.Decl> resolveRefer(List<PliSyntax.Decl> tree,
                                                  StructureMapping.Result placeholder, Env env) {
            // 対象は REFER の配列より前にあるので、その位置は仮の上限によらない
            int needed = 0;
            for (PliSyntax.Decl decl : tree) {
                for (PliSyntax.Bound bound : decl.dimensions()) {
                    if (bound.refer() != null) {
                        int j = indexOf(tree, bound.refer());
                        needed = Math.max(needed, placeholder.byteOffset(j)
                                + placeholder.byteLength(j));
                    }
                }
            }
            DataView area = basedView(tree.get(0).basedOn(), needed, env);
            List<PliSyntax.Decl> shape = new ArrayList<>(tree);
            for (int i = 0; i < shape.size(); i++) {
                PliSyntax.Decl decl = shape.get(i);
                if (decl.dimensions().stream().noneMatch(bound -> bound.refer() != null)) {
                    continue;
                }
                List<PliSyntax.Bound> bounds = new ArrayList<>();
                for (PliSyntax.Bound bound : decl.dimensions()) {
                    if (bound.refer() == null) {
                        bounds.add(bound);
                        continue;
                    }
                    int j = indexOf(tree, bound.refer());
                    PliSyntax.Decl object = tree.get(j);
                    Var current = new Var(object.name(), object.type(), object.precision(),
                            object.scale(), area.subView(placeholder.byteOffset(j),
                                    placeholder.byteLength(j)), placeholder.bitShift(j));
                    current.unsigned = object.unsigned();
                    int extent = number(current.read(context)).intValue();
                    if (extent < 0) {
                        throw new PliExecutionException("REFER object " + object.name()
                                + " is negative: " + extent);
                    }
                    bounds.add(new PliSyntax.Bound(1, extent, bound.referExtent(), bound.refer()));
                }
                shape.set(i, decl.withDimensions(bounds));
            }
            return shape;
        }

        /** ALLOCATE のとき、REFER の上限を宣言の式で決めた塊。 */
        private List<PliSyntax.Decl> allocationShape(List<PliSyntax.Decl> tree, Env env) {
            List<PliSyntax.Decl> shape = new ArrayList<>(tree);
            for (int i = 0; i < shape.size(); i++) {
                PliSyntax.Decl decl = shape.get(i);
                if (decl.dimensions().stream().noneMatch(bound -> bound.refer() != null)) {
                    continue;
                }
                List<PliSyntax.Bound> bounds = new ArrayList<>();
                for (PliSyntax.Bound bound : decl.dimensions()) {
                    bounds.add(bound.refer() == null ? bound : new PliSyntax.Bound(1,
                            number(value(bound.referExtent(), env)).intValue(),
                            bound.referExtent(), bound.refer()));
                }
                shape.set(i, decl.withDimensions(bounds));
            }
            return shape;
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
                variable.varying = child.varying();
                variable.unsigned = child.unsigned();
                // 構造に書いたバイト順は、自分で書いていない要素へ受け継がれる
                variable.littleEndian = child.byteOrder() == null ? group.littleEndian
                        : child.byteOrder().equals("LITTLE");
                variable.floatFormat = child.floatFormat();
                variable.picture = pictureOf(child);
                variable.valueSet = child.valueSet();
                variable.complex = child.complex();
                env.put(variable);
                env.alias(tree.get(index).name() + "." + child.name(), variable);
                if (index > 0) {
                    env.alias(prefix + "." + child.name(), variable);
                }
                group.members.add(variable);
                if (!child.dimensions().isEmpty()) {
                    // 構造の中の配列。要素の間隔は構造の外の配列と同じ規則である
                    declareElements(variable, child, StructureMapping.unaligned(tree, i),
                            mapping.bitShift(i));
                    if (!based) {
                        initializeElements(variable, child.initialItems(), env);
                    }
                } else if (!based) {
                    initialize(variable, child.initial(), env);
                }
                if (child.type() == PliSyntax.Type.GROUP) {
                    declareMembers(tree, i, childEnd, env, variable, area, mapping,
                            prefix + "." + child.name(), based);
                }
                i = childEnd;
            }
        }

        /** {@code name} が今 NULL の POINTER か。POINTER でない (BASED(ADDR(X))) なら偽。 */
        private boolean nullPointer(String name, Env env) {
            Var base = env.require(name);
            return base.type == PliSyntax.Type.POINTER
                    && (((PointerValue) base.read(context)).address() == 0
                        || ((PointerValue) base.read(context)).address() == NULL_ADDRESS);
        }

        /**
         * NULL の POINTER に重ねた宣言の名を、参照すると止まる印で置く。以前は宣言した時点で
         * 「NULL の POINTER」で止めていたので、POINTER に後から値を入れる普通の書き方が動かなかった
         * (z/OS probe の PLIPTR)。構造なら要素の名と修飾した名も置く。
         */
        private void unbound(List<PliSyntax.Decl> tree, String pointer, Env env) {
            List<String> path = new ArrayList<>();
            for (PliSyntax.Decl decl : tree) {
                int depth = tree.size() == 1 ? 0 : Math.max(0, decl.level() - 1);
                while (path.size() > depth) path.remove(path.size() - 1);
                Var placeholder = new Var(decl.name(), decl.type(), decl.precision(),
                        decl.scale(), Storage.allocate(0).whole());
                placeholder.unboundOn = pointer.toUpperCase(Locale.ROOT);
                env.put(placeholder);
                for (int from = 0; from < path.size(); from++) {
                    env.alias(String.join(".", path.subList(from, path.size())) + "."
                            + decl.name(), placeholder);
                }
                path.add(decl.name());
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

        /**
         * DEFINED の変数 (LRM "DEFINED attribute")。iSUB の定義なら、要素ごとに iSUB を自分の
         * 添字に置いた基の要素を共有する。添字の無い定義は文字の重ね (POSITION) だけを持つ。
         */
        private void declareDefined(PliSyntax.Decl declaration, Env env) {
            PliSyntax.Defined defined = declaration.defined();
            Var base = env.require(defined.base());
            if (defined.subscripts() != null) {
                if (base.dimensions == null) {
                    throw new PliExecutionException("DEFINED " + defined.base()
                            + "(...) needs an array base: " + declaration.name());
                }
                if (declaration.dimensions().isEmpty()) {
                    throw new PliExecutionException("a scalar DEFINED on an array element is not"
                            + " supported yet: " + declaration.name());
                }
                Var array = new Var(declaration.name(), declaration.type(),
                        declaration.precision(), declaration.scale(), Storage.allocate(0).whole());
                array.dimensions = declaration.dimensions();
                int rank = declaration.dimensions().size();
                int[] index = new int[rank];
                for (int d = 0; d < rank; d++) index[d] = declaration.dimensions().get(d).low();
                for (int n = 0; n < declaration.count(); n++) {
                    Env subscripts = new Env(env, context);
                    for (int d = 0; d < rank; d++) {
                        Var isub = new Var("ISUB_" + (d + 1), PliSyntax.Type.BINARY, 31, 0,
                                Storage.allocate(4).whole());
                        isub.write(FixedValue.binary(BigDecimal.valueOf(index[d]), 31, 0),
                                context);
                        subscripts.put(isub);
                    }
                    Var element = element(base, defined.subscripts(), subscripts);
                    if (element.type != declaration.type()
                            || element.precision != declaration.precision()
                            || element.scale != declaration.scale()) {
                        throw new PliExecutionException("DEFINED with attributes other than the"
                                + " base's is not supported yet: " + declaration.name());
                    }
                    array.elements.add(element);
                    for (int d = rank - 1; d >= 0; d--) {
                        if (++index[d] <= declaration.dimensions().get(d).high()) break;
                        index[d] = declaration.dimensions().get(d).low();
                    }
                }
                env.put(array);
                return;
            }
            if (!declaration.dimensions().isEmpty() || base.dimensions != null
                    || declaration.type() != PliSyntax.Type.CHAR || base.type != PliSyntax.Type.CHAR
                    || declaration.varying() || base.varying) {
                // 文字の重ね (string overlay defining) だけを持つ。配列の単純な定義と、
                // 型の違う重ねは記憶域の並びの規則を確かめていないので断る
                throw new PliExecutionException("only CHARACTER overlay DEFINED (with POSITION)"
                        + " and iSUB DEFINED are supported yet: " + declaration.name());
            }
            int position = defined.position() == null ? 1
                    : number(value(defined.position(), env)).intValue();
            int length = byteLength(declaration);
            if (position < 1 || position - 1 + length > base.view.length()) {
                throw new PliExecutionException("DEFINED " + declaration.name() + " POSITION("
                        + position + ") lies outside " + base.name);
            }
            Var overlay = new Var(declaration.name(), declaration.type(), declaration.precision(),
                    declaration.scale(), base.view.subView(position - 1, length));
            overlay.valueSet = declaration.valueSet();
            env.put(overlay);
        }

        private static PliPicture pictureOf(PliSyntax.Decl declaration) {
            return declaration.type() == PliSyntax.Type.PICTURE && declaration.picture() != null
                    ? PliPicture.parse(declaration.picture()) : null;
        }

        private void declareScalar(PliSyntax.Decl declaration, Env env, DataView area,
                                   int offset, String alias) {
            if (declaration.defined() != null) {
                declareDefined(declaration, env);
                return;
            }
            if ((declaration.type() == PliSyntax.Type.ENTRY && declaration.precision() == 0)
                    || declaration.type() == PliSyntax.Type.FILE) {
                return;
            }
            DataView view;
            String upper = declaration.name().toUpperCase(Locale.ROOT);
            if (env.arguments.containsKey(upper)) {
                bindParameter(declaration, env.arguments.get(upper), env);
                return;
            }
            boolean parameter = parameters.containsKey(upper);
            if (declaration.type() == PliSyntax.Type.POINTER && parameter) {
                return; // 入口引数の POINTER は、渡された記憶域の番号をもう持っている
            }
            if (declaration.basedOn() != null && nullPointer(declaration.basedOn(), env)) {
                unbound(List.of(declaration), declaration.basedOn(), env);
                remember(declaration.basedOn(),
                        () -> declareScalar(declaration, env, area, offset, alias));
                return;
            }
            if (declaration.basedOn() != null) {
                // 重ねる先のうち、自分の長さの分だけを使う。先の全体を使うと、短い変数へ書いたときに
                // 残りまで書き換えてしまう (PIC'(9)9' を 10 桁の CHAR に重ねると 10 桁目が空白になっていた)
                view = basedView(declaration.basedOn(), byteLength(declaration), env);
                remember(declaration.basedOn(),
                        () -> declareScalar(declaration, env, area, offset, alias));
            } else if (parameter && program.main() && declaration.type() == PliSyntax.Type.CHAR
                    && declaration.precision() < 0) {
                // 主手続きの CHAR(*) VARYING は PARM を受ける。PARM の頭の半語が長さで、
                // 最初の '/' より前は実行時オプションである (LE の既定。Programming Guide
                // "Passing an argument to the MAIN procedure")
                DataView passed = parameters.get(upper);
                int length = passed.length() < 2 ? 0
                        : ((passed.get(0) & 0xFF) << 8) | (passed.get(1) & 0xFF);
                String text = context.codePage().decode(
                        passed.subView(2, Math.min(length, passed.length() - 2)).toByteArray());
                int slash = program.noExecOps() ? -1 : text.indexOf('/');
                text = slash < 0 ? text : text.substring(slash + 1);
                byte[] encoded = context.codePage().encode(text);
                view = Storage.allocate(encoded.length + 2).whole();
                Var variable = new Var(declaration.name(), declaration.type(), encoded.length, 0,
                        view);
                variable.varying = true;
                variable.write(text, context);
                env.put(variable);
                return;
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
            variable.varying = declaration.varying();
            variable.unsigned = declaration.unsigned();
            variable.littleEndian = "LITTLE".equals(declaration.byteOrder());
            variable.floatFormat = declaration.floatFormat();
            variable.picture = pictureOf(declaration);
            variable.valueSet = declaration.valueSet();
            variable.complex = declaration.complex();
            if (!declaration.dimensions().isEmpty()) {
                declareElements(variable, declaration,
                        StructureMapping.unaligned(List.of(declaration), 0), 0);
            }
            env.put(variable);
            if (alias != null) env.alias(alias, variable);
            if (declaration.basedOn() == null) {
                if (variable.dimensions == null) {
                    initialize(variable, declaration.initial(), env);
                } else {
                    initializeElements(variable, declaration.initialItems(), env);
                }
            }
        }

        /**
         * 配列の要素を、行の順 (最後の添字がいちばん速く変わる) に並べる (LRM "Array storage")。
         * 要素の間隔は {@link StructureMapping#strideBits} が決める。UNALIGNED のビット列の配列は
         * ビット単位で詰まり、要素が byte の途中から始まる。
         *
         * @param baseShift 配列の頭が最初の byte の何ビット目から始まるか
         */
        private static void declareElements(Var array, PliSyntax.Decl declaration,
                                            boolean unaligned, int baseShift) {
            array.dimensions = declaration.dimensions();
            int stride = StructureMapping.strideBits(declaration, unaligned);
            int bits = StructureMapping.elementBits(declaration, unaligned);
            for (int k = 0; k < declaration.count(); k++) {
                int at = baseShift + k * stride;
                Var element = new Var(declaration.name(), declaration.type(),
                        declaration.precision(), declaration.scale(),
                        array.view.subView(at / 8, (at % 8 + bits + 7) / 8), at % 8);
                element.varying = declaration.varying();
                element.littleEndian = array.littleEndian;
                element.floatFormat = declaration.floatFormat();
                element.picture = pictureOf(declaration);
                element.valueSet = declaration.valueSet();
                element.complex = declaration.complex();
                element.unsigned = declaration.unsigned();
                array.elements.add(element);
            }
        }

        /**
         * 配列の INITIAL。並びの値を要素へ順に入れ、{@code (n)} は n 回、{@code (*)} は残り全部に
         * 繰り返す。並びが尽きた先の要素は初期値を持たない (LRM "INITIAL attribute")。
         */
        private void initializeElements(Var array, List<PliSyntax.InitItem> items, Env env) {
            int next = 0;
            for (PliSyntax.InitItem item : items) {
                int count = item.count() == 0 ? array.elements.size() - next : item.count();
                for (int k = 0; k < count; k++) {
                    if (next >= array.elements.size()) {
                        throw new PliExecutionException("INITIAL of " + array.name
                                + " has more values than the array has elements");
                    }
                    initialize(array.elements.get(next++), item.value(), env);
                }
            }
            while (next < array.elements.size()) {
                initialize(array.elements.get(next++), null, env);
            }
        }

        private void initialize(Var variable, PliSyntax.Expr initial, Env env) {
            if (deferredInits != null) {
                deferredInits.add(() -> initializeNow(variable, initial, env));
                return;
            }
            initializeNow(variable, initial, env);
        }

        private void initializeNow(Var variable, PliSyntax.Expr initial, Env env) {
            if (variable.type == PliSyntax.Type.CHAR || variable.type == PliSyntax.Type.PICTURE) {
                variable.view.fill(context.codePage().space());
            }
            if (variable.varying) {
                // INITIAL の無い VARYING は長さ 0 から始める (長さの半語を 0 にする)
                variable.write("", context);
            }
            if (initial != null) {
                variable.write(value(initial, env), context);
            } else if (variable.type == PliSyntax.Type.DECIMAL) {
                variable.write(BigDecimal.ZERO, context);
            }
        }

        /** 配列の式を要素ごとに評価しているときの要素の番号。そうでなければ -1。 */
        private int elementIndex = -1;
        /** 要素ごとに評価しているときの、代入先の配列の上下限。 */
        private List<PliSyntax.Bound> elementShape;

        /**
         * 式が配列そのものを含むか。添字を付けた要素 ({@code A(I)}) と、配列そのものを引数に取る
         * 組込み関数 ({@code SIZE} ほか) の中は数えない。
         */
        private boolean containsArray(PliSyntax.Expr expression, Env env) {
            return switch (expression) {
                case PliSyntax.Literal literal -> false;
                case PliSyntax.Reference reference -> env.contains(reference.name())
                        && env.require(reference.name()).dimensions != null;
                case PliSyntax.Unary unary -> containsArray(unary.operand(), env);
                case PliSyntax.Binary binary -> containsArray(binary.left(), env)
                        || containsArray(binary.right(), env);
                case PliSyntax.Function function -> {
                    String name = function.name().toUpperCase(Locale.ROOT);
                    if (List.of("SIZE", "STORAGE", "STG", "ADDR").contains(name)) {
                        yield false;
                    }
                    boolean any = false;
                    for (PliSyntax.Expr argument : function.arguments()) {
                        any |= containsArray(argument, env);
                    }
                    yield any;
                }
            };
        }

        private void assign(PliSyntax.Assign assignment, Env env) {
            if (assignment.target().contains("->")) {
                // 右辺は locator を入れ替える前に評価する。Q->A = NODE.A + 1 の NODE.A は
                // 今の P が指すものである
                Object value = value(assignment.value(), env);
                located(assignment.target(), env, rest -> {
                    assign(new PliSyntax.Assign(rest, assignment.subscripts(),
                            new PliSyntax.Literal(value)), env);
                    return null;
                });
                return;
            }
            String pseudo = assignment.target().toUpperCase(Locale.ROOT);
            if ((pseudo.equals("ONSOURCE") || pseudo.equals("ONCHAR")) && !env.contains(pseudo)) {
                // 擬似変数。CONVERSION の on-unit の中で、変換できなかった字の並びを直す
                if (onSource == null) {
                    throw new PliExecutionException(pseudo + " is assigned outside a"
                            + " CONVERSION on-unit");
                }
                String value = display(value(assignment.value(), env));
                if (pseudo.equals("ONSOURCE")) {
                    repairedSource = value;
                } else {
                    String current = repairedSource != null ? repairedSource : onSource;
                    int at = badCharacter(current);
                    repairedSource = at < 0 ? current
                            : current.substring(0, at) + (value.isEmpty() ? " " : value.substring(0, 1))
                                    + current.substring(at + 1);
                }
                return;
            }
            if (!assignment.subscripts().isEmpty()) {
                Var array = arrayNamed(assignment.target(), env);
                if (array != null && assignment.subscripts().stream().anyMatch(
                        s -> s instanceof PliSyntax.Literal l && l.value() == PliSyntax.Asterisk.STAR)) {
                    crossSection(array, assignment, env);
                    return;
                }
                if (array != null) {
                    element(array, assignment.subscripts(), env)
                            .write(value(assignment.value(), env), context);
                    return;
                }
                if (assignment.target().equals("SUBSTR") && !env.contains("SUBSTR")) {
                    substrPseudovariable(assignment, env);
                    return;
                }
                if (assignment.target().equals("UNSPEC") && !env.contains("UNSPEC")) {
                    unspecPseudovariable(assignment, env);
                    return;
                }
                throw new PliExecutionException(assignment.target() + " is not an array");
            }
            Var target = env.require(assignment.target());
            if (target.dimensions != null) {
                // 配列への代入。配列から配列なら要素ごと、スカラーならすべての要素へ
                // (LRM "Array assignments")
                if (assignment.value() instanceof PliSyntax.Reference reference
                        && arrayNamed(reference.name(), env) != null) {
                    Var source = env.require(reference.name());
                    if (!source.dimensions.equals(target.dimensions)) {
                        throw new PliExecutionException("arrays " + source.name + " and "
                                + target.name + " have different bounds");
                    }
                    for (int k = 0; k < target.elements.size(); k++) {
                        target.elements.get(k).write(source.elements.get(k).read(context), context);
                    }
                    return;
                }
                if (containsArray(assignment.value(), env)) {
                    // 配列の式 (A = B + 1、A = B * C)。要素ごとに、式の中の配列をその要素に
                    // 置き換えて評価する (LRM "Array expressions")。上下限の違う配列は混ぜられない
                    for (int k = 0; k < target.elements.size(); k++) {
                        elementIndex = k;
                        elementShape = target.dimensions;
                        try {
                            target.elements.get(k).write(value(assignment.value(), env), context);
                        } finally {
                            elementIndex = -1;
                            elementShape = null;
                        }
                    }
                    return;
                }
                Object value = value(assignment.value(), env);
                for (Var element : target.elements) {
                    element.write(value, context);
                }
                return;
            }
            if (assignment.value() instanceof PliSyntax.Reference reference
                    && env.contains(reference.name())) {
                Var source = env.require(reference.name());
                if (target.type == PliSyntax.Type.GROUP && source.type == PliSyntax.Type.GROUP) {
                    copy(source.view, target.view, context.codePage().space());
                    return;
                }
            }
            Object value = value(assignment.value(), env);
            if (target.type == PliSyntax.Type.POINTER) {
                target.write(value, context);
                rebase(target.name);
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
            if (put.file() != null) {
                UserPrintFile file = printFiles.get(put.file().toUpperCase(Locale.ROOT));
                if (file == null) {
                    throw new PliExecutionException("PUT FILE(" + put.file() + ") needs an open"
                            + " PRINT file; other stream files are not supported yet");
                }
                if (put.page()) file.page();
                if (put.line() != null) file.line(number(value(put.line(), env)).intValue());
                if (put.skip() > 0) file.skip(put.skip());
                if (put.edit()) {
                    edit(put, env, file::text);
                } else {
                    int count = 0;
                    for (PliSyntax.Expr expression : put.values()) {
                        for (Object item : items(expression, env)) {
                            file.text(" " + listed(item));
                            count++;
                        }
                    }
                    file.count(count);
                }
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
            if (expression instanceof PliSyntax.Reference reference
                    && reference.name().contains("->")) {
                return located(reference.name(), env,
                        rest -> items(new PliSyntax.Reference(rest), env));
            }
            if (expression instanceof PliSyntax.Reference reference) {
                Var variable = env.require(reference.name());
                if (variable.dimensions != null) {
                    // 配列は要素の数だけの項目と同じ。行の順に送る
                    List<Object> values = new ArrayList<>();
                    for (Var element : variable.elements) {
                        values.add(item(element));
                    }
                    return values;
                }
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
        /**
         * {@code GET STRING}。EDIT は書式の欄ごとに字を取り (F は数、A は字、X は飛ばす)、LIST は
         * 空白か ',' で区切った項目を順に受け取る (LRM "Edit-directed input", "List-directed
         * input")。項目の字は代入の規則で受け取る側の型へ変換する。
         */
        private void getString(PliSyntax.Get get, Env env) {
            String source = display(env.require(get.string()).read(context));
            List<Var> targets = new ArrayList<>();
            for (PliSyntax.Expr target : get.targets()) {
                targets.add(dataReference(target, env, "GET"));
            }
            if (get.edit()) {
                int at = 0;
                int item = 0;
                for (Var target : targets) {
                    while (true) {
                        if (item == get.format().size()) {
                            throw new PliExecutionException("GET EDIT format list has fewer data"
                                    + " items than targets");
                        }
                        PliSyntax.FormatItem next = get.format().get(item++);
                        if (next.width() < 0) {
                            throw new PliExecutionException("GET EDIT format item "
                                    + next.code() + " needs a width");
                        }
                        String field = at >= source.length() ? ""
                                : source.substring(at, Math.min(source.length(), at + next.width()));
                        at += next.width();
                        if (next.code() == 'X') {
                            continue;
                        }
                        if (next.code() == 'F') {
                            String digits = field.strip();
                            if (digits.isEmpty()) {
                                throw new Condition("CONVERSION", null, 600, field);
                            }
                            BigDecimal number;
                            try {
                                number = new BigDecimal(digits);
                            } catch (NumberFormatException bad) {
                                throw new Condition("CONVERSION", null, 600, field);
                            }
                            if (!digits.contains(".") && next.fraction() > 0) {
                                // 点の無い欄は d 桁の小数を含むとみなす
                                number = number.movePointLeft(next.fraction());
                            }
                            target.write(FixedValue.decimal(number, Math.max(1,
                                    number.precision()), Math.max(0, number.scale())), context);
                        } else {
                            target.write(field, context);
                        }
                        break;
                    }
                }
                return;
            }
            int at = 0;
            for (Var target : targets) {
                while (at < source.length() && source.charAt(at) == ' ') at++;
                if (at >= source.length()) {
                    throw new Condition("ENDFILE", null, 70, null);
                }
                String text;
                if (source.charAt(at) == '\'') {
                    StringBuilder quoted = new StringBuilder();
                    at++;
                    while (at < source.length()) {
                        char c = source.charAt(at++);
                        if (c == '\'') {
                            if (at < source.length() && source.charAt(at) == '\'') {
                                quoted.append(c);
                                at++;
                                continue;
                            }
                            break;
                        }
                        quoted.append(c);
                    }
                    text = quoted.toString();
                    target.write(text, context);
                } else {
                    int end = at;
                    while (end < source.length() && source.charAt(end) != ','
                            && source.charAt(end) != ' ') end++;
                    text = source.substring(at, end);
                    at = end;
                    if (!text.isEmpty()) {
                        Object value = text;
                        if (target.type == PliSyntax.Type.BINARY
                                || target.type == PliSyntax.Type.DECIMAL) {
                            try {
                                BigDecimal number = new BigDecimal(text);
                                value = FixedValue.decimal(number, Math.max(1, number.precision()),
                                        Math.max(0, number.scale()));
                            } catch (NumberFormatException bad) {
                                throw new Condition("CONVERSION", null, 600, text);
                            }
                        }
                        target.write(value, context);
                    }
                }
                while (at < source.length() && source.charAt(at) == ' ') at++;
                if (at < source.length() && source.charAt(at) == ',') at++;
            }
        }

        private void putString(PliSyntax.Put put, Env env) {
            StringBuilder text = new StringBuilder();
            if (put.data()) {
                // data-directed: 名=値 を空白で区切り、最後に ';' を置く (LRM "Data-directed
                // output")。値の形は list-directed と同じにした。実機と突き合わせていない
                for (PliSyntax.Expr expression : put.values()) {
                    if (!(expression instanceof PliSyntax.Reference reference)) {
                        throw new PliExecutionException("PUT DATA needs variable names");
                    }
                    for (Object item : items(expression, env)) {
                        if (!text.isEmpty()) text.append(' ');
                        text.append(reference.name().toUpperCase(Locale.ROOT)).append('=')
                                .append(listed(item).strip());
                    }
                }
                text.append(';');
            } else {
                edit(put, env, text::append);
            }
            Var target = env.require(put.string());
            if (text.length() > target.capacity()) {
                throw new PliExecutionException("ERROR: PUT STRING needs " + text.length()
                        + " characters but " + target.name + " has " + target.capacity());
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

        /**
         * 内部手続きを呼ぶ。
         *
         * <p>引数が変数の名前なら参照で渡る。式や定数なら、値を入れた仮の変数 (dummy argument) が
         * 渡る (LRM "Passing arguments to procedures")。以前は名前しか受けず、
         * {@code CALL P('ABC', X=1)} を断っていた。仮の変数の型は、手続きの中のパラメーターの
         * 宣言が決める。型が合わない変数を渡したときも仮の変数になる。
         *
         * @return RETURN(値) の値。値を返さなければ {@code null}
         */
        private Object invoke(PliSyntax.Procedure procedure, List<PliSyntax.Expr> arguments,
                              Env env) {
            if (arguments.size() != procedure.parameters().size()) {
                throw new PliExecutionException("procedure " + procedure.name() + " takes "
                        + procedure.parameters().size() + " argument(s), but "
                        + arguments.size() + " were passed");
            }
            // 名は手続きを宣言した場所の環境でたどる (静的な有効範囲。LRM "Scopes of
            // declarations")。以前は呼んだ側の環境につないでいたので、呼んだ側の同じ名の
            // 変数が見えていた (暫定判断 P-197)。on-unit は呼んだ側の鎖でたどる
            String enclosing = program.enclosing().get(procedure.name());
            java.util.ArrayDeque<Env> outer = enclosing == null ? null : activations.get(enclosing);
            Env declared = outer == null || outer.isEmpty() ? globals : outer.peek();
            Env local = new Env(declared, env, context);
            for (int i = 0; i < arguments.size(); i++) {
                String name = procedure.parameters().get(i).toUpperCase(Locale.ROOT);
                PliSyntax.Expr argument = arguments.get(i);
                if (argument instanceof PliSyntax.Reference reference
                        && env.contains(reference.name())) {
                    Var variable = env.require(reference.name());
                    local.alias(name, variable);
                    local.arguments.put(name, variable);
                } else {
                    local.arguments.put(name, value(argument, env));
                }
            }
            returned = null;
            String outerScope = scope;
            scope = outerScope + "/" + procedure.name().toUpperCase(Locale.ROOT);
            activations.computeIfAbsent(procedure.name(), k -> new java.util.ArrayDeque<>())
                    .push(local);
            try {
                declareAll(procedure.body(), local);
                execute(procedure.body(), local);
            } catch (ReturnSignal ignored) {
                // 内部プロシージャから呼出元へ戻る。
            } finally {
                scope = outerScope;
                activations.get(procedure.name()).pop();
            }
            Object result = returned;
            returned = null;
            return result;
        }

        /** 内部手続きのパラメーターを、渡された引数に結ぶ。 */
        private void bindParameter(PliSyntax.Decl declaration, Object argument, Env env) {
            if (!declaration.dimensions().isEmpty() || declaration.level() > 0) {
                if (argument instanceof Var) {
                    return; // 配列と構造は、渡された変数そのものを使う (alias 済み)
                }
                throw new PliExecutionException("an array or structure parameter needs a variable"
                        + " as its argument: " + declaration.name());
            }
            boolean star = declaration.type() == PliSyntax.Type.CHAR && declaration.precision() < 0;
            if (argument instanceof Var variable && variable.type == declaration.type()
                    && variable.varying == declaration.varying() && variable.dimensions == null
                    && (star || declaration.type() == PliSyntax.Type.POINTER
                        || (variable.precision == declaration.precision()
                            && variable.scale == declaration.scale())
                        || (declaration.type() == PliSyntax.Type.CHAR && !variable.varying
                            && variable.view.length() == declaration.precision()))) {
                env.alias(declaration.name(), variable);
                return;
            }
            Object value = argument instanceof Var variable ? variable.read(context) : argument;
            int precision = star ? display(value).length() : declaration.precision();
            int length = switch (declaration.type()) {
                case CHAR -> precision + (declaration.varying() ? 2 : 0);
                default -> byteLength(declaration);
            };
            Var dummy = new Var(declaration.name(), declaration.type(), precision,
                    declaration.scale(), Storage.allocate(length).whole());
            dummy.varying = declaration.varying();
            dummy.picture = pictureOf(declaration);
            dummy.valueSet = declaration.valueSet();
            dummy.write(value, context);
            env.alias(declaration.name(), dummy);
        }

        /** 入口の値が指す内部手続き。 */
        private PliSyntax.Procedure entryProcedure(Object value) {
            if (!(value instanceof EntryValue entry) || entry.name() == null) {
                throw new PliExecutionException("an ENTRY variable without a value was called");
            }
            PliSyntax.Procedure procedure = program.procedures().get(entry.name());
            if (procedure == null) {
                throw new PliExecutionException("entry " + entry.name() + " is not an internal"
                        + " procedure of this program");
            }
            return procedure;
        }

        /**
         * GENERIC の選択 (LRM "GENERIC attribute")。引数の種類が記述に合う最初の選択肢を選ぶ。
         * 合うものが無ければ誤りである。
         */
        private PliSyntax.Procedure generic(String name, List<PliSyntax.Expr> arguments, Env env) {
            List<String> kinds = new ArrayList<>();
            for (PliSyntax.Expr argument : arguments) {
                Var variable = argument instanceof PliSyntax.Reference reference
                        ? env.find(reference.name()) : null;
                Object value = variable != null ? null : value(argument, env);
                PliSyntax.Type type = variable != null ? variable.type : null;
                kinds.add(type == PliSyntax.Type.BINARY || type == PliSyntax.Type.DECIMAL
                        || type == PliSyntax.Type.PICTURE || value instanceof FixedValue
                        || value instanceof Number ? "FIXED"
                        : type == PliSyntax.Type.CHAR || value instanceof String ? "CHAR"
                        : type == PliSyntax.Type.BIT || value instanceof PliSyntax.BitString
                                || value instanceof Boolean ? "BIT"
                        : type == PliSyntax.Type.POINTER || value instanceof PointerValue
                                ? "POINTER" : "?");
            }
            for (PliSyntax.GenericAlternative alternative : program.generics().get(name)) {
                if (alternative.matches(kinds)) {
                    PliSyntax.Procedure procedure = program.procedures().get(alternative.entry());
                    if (procedure == null) {
                        throw new PliExecutionException("GENERIC " + name + " selects "
                                + alternative.entry() + ", which is not an internal procedure");
                    }
                    return procedure;
                }
            }
            throw new PliExecutionException("no GENERIC alternative of " + name + " matches "
                    + kinds);
        }

        private void call(PliSyntax.Call call, Env env) {
            PliSyntax.Procedure procedure = program.procedures().get(call.name());
            if (procedure == null && program.generics().containsKey(call.name())) {
                procedure = generic(call.name(), call.arguments(), env);
            } else if (procedure == null && env.find(call.name()) != null
                    && env.find(call.name()).type == PliSyntax.Type.ENTRY
                    && env.find(call.name()).dimensions == null) {
                procedure = entryProcedure(env.require(call.name()).read(context));
            }
            if (procedure != null) {
                invoke(procedure, call.arguments(), env);
                return;
            }
            if (call.name().equals("QUICKSORT") || call.name().equals("QUICKSORTX")) {
                arrayOrder(call.name(), call.arguments(), env);
                return;
            }
            if (call.name().equalsIgnoreCase("PLIFREE")
                    && program.procedures().get("PLIFREE") == null) {
                // NEW で取った記憶域を解く。記憶域は Java の GC が取り戻すので、何もしない。
                // 解いたあとの参照が誤りであることは検出しない
                return;
            }
            if (call.name().equalsIgnoreCase("PLIRETC")) {
                // 戻りコードを決める (LRM "PLIRETC")
                context.setReturnCode(number(value(call.arguments().get(0), env)).intValue());
                return;
            }
            switch (call.name()) {
                case "PLIASCII", "PLIEBCDIC" -> translateLatin1(call, env);
                case "PLIATTN", "PLICANC" -> {
                    if (!call.arguments().isEmpty()) {
                        throw new PliExecutionException(call.name() + " takes no arguments");
                    }
                    if (call.name().equals("PLIATTN")) {
                        throw new Condition("ATTENTION", null, 400, null);
                    }
                    // この処理系には取り消すべき自動再始動処理がない。
                }
                case "PLIDELETE" -> {
                    if (call.arguments().size() != 1
                            || !(value(call.arguments().getFirst(), env) instanceof PointerValue)) {
                        throw new PliExecutionException("PLIDELETE requires a handle");
                    }
                    // NEW の領域の扱いは PLIFREE と同じ。
                }
                // 記憶域を埋める・写す (LRM "PLIFILL", "PLIMOVE", "PLIOVER")。PLIOVER は重なりを
                // 考えて写す。PLIMOVE は重ならないことを前提にしてよいが、同じ写し方にする
                case "PLIFILL" -> {
                    List<Object> a = call.arguments().stream().map(e -> value(e, env)).toList();
                    int n = number(a.get(2)).intValue();
                    byte[] fill = new byte[n];
                    java.util.Arrays.fill(fill, characterBytes(a.get(1))[0]);
                    memoryWrite(a.get(0), fill);
                }
                case "PLIMOVE", "PLIOVER" -> {
                    List<Object> a = call.arguments().stream().map(e -> value(e, env)).toList();
                    memoryWrite(a.get(0), memoryRead(a.get(1), number(a.get(2)).intValue()));
                }
                case "PLITRAN11" -> translateBuffer(call, env, 1, 1);
                case "PLITRAN12" -> translateBuffer(call, env, 1, 2);
                case "PLITRAN21" -> translateBuffer(call, env, 2, 1);
                case "PLITRAN22" -> translateBuffer(call, env, 2, 2);
                default -> {
                    callExternal(call, env);
                }
            }
        }

        private void callExternal(PliSyntax.Call call, Env env) {
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
            Ops.call(context, fetched.getOrDefault(call.name().toUpperCase(Locale.ROOT),
                    call.name()), loader, arguments);
            // 呼んだ先が POINTER の引数を書き換えたかもしれない。BASED の変数は参照のたびに
            // POINTER の値で決まる (LRM "BASED attribute") ので、渡した POINTER に重ねた変数を
            // 重ね直す。以前は同じプログラムの中の代入でしか重ね直さなかった (P-185 の 3 点目)
            for (PliSyntax.Expr expression : call.arguments()) {
                if (expression instanceof PliSyntax.Reference reference) {
                    Var argument = env.require(reference.name());
                    if (argument.type == PliSyntax.Type.POINTER) {
                        rebase(argument.name);
                    }
                }
            }
        }

        private void translateBuffer(PliSyntax.Call call, Env env, int from, int to) {
            List<PliSyntax.Expr> args = call.arguments();
            String name = call.name();
            if (args.size() != 4) {
                throw new PliExecutionException(name + " requires three pointers and a length");
            }
            Object target = value(args.get(0), env);
            Object source = value(args.get(1), env);
            int length = Math.max(0, number(value(args.get(2), env)).intValueExact());
            Object tableAddress = value(args.get(3), env);
            byte[] input = pointerBuffer(source, length, name).toByteArray();
            DataView table = pointerBuffer(tableAddress, (from == 1 ? 256 : 65536) * to, name);
            int units = Math.ceilDiv(length, from);
            DataView output = pointerBuffer(target, Math.multiplyExact(units, to), name);
            byte[] translated = new byte[output.length()];
            for (int unit = 0; unit < units; unit++) {
                int offset = unit * from;
                int key = from == 1 ? input[offset] & 0xFF : wideUnit(input, offset);
                System.arraycopy(table.storage().array(), table.offset() + key * to,
                        translated, unit * to, to);
            }
            output.setBytes(translated);
        }

        private void translateLatin1(PliSyntax.Call call, Env env) {
            List<PliSyntax.Expr> args = call.arguments();
            String name = call.name();
            if (args.size() != 3) {
                throw new PliExecutionException(name + " requires two pointers and a length");
            }
            Object target = value(args.get(0), env);
            Object source = value(args.get(1), env);
            int length = Math.max(0, number(value(args.get(2), env)).intValueExact());
            if (length == 0) return;
            byte[] bytes = pointerBuffer(source, length, name).toByteArray();
            var page = context.codePage();
            boolean toLatin1 = name.equals("PLIASCII");
            for (int i = 0; i < bytes.length; i++) {
                if (toLatin1) {
                    String decoded = page.decode(new byte[] {bytes[i]});
                    int character = decoded.isEmpty() ? 0x1A : decoded.codePointAt(0);
                    bytes[i] = (byte) (character <= 0xFF ? character : 0x1A);
                } else {
                    String character = Character.toString(bytes[i] & 0xFF);
                    byte[] encoded = page.canEncode(character) ? page.encode(character) : new byte[0];
                    bytes[i] = encoded.length == 1 ? encoded[0] : (byte) 0x3F;
                }
            }
            pointerBuffer(target, length, name).setBytes(bytes);
        }

        /** 1 次元配列を部分範囲で整列・検索する。Zeus vm/bif.rs と同じく添字ではなく相対位置を返す。 */
        private FixedValue arrayOrder(String operation, List<PliSyntax.Expr> arguments, Env env) {
            boolean search = operation.startsWith("BINSEARCH");
            boolean custom = operation.endsWith("X");
            int required = 1 + (search ? 1 : 0) + (custom ? 1 : 0);
            if (arguments.size() < required || arguments.size() > required + 2
                    || !(arguments.getFirst() instanceof PliSyntax.Reference reference)) {
                throw new PliExecutionException(operation + " requires an array and valid arguments");
            }
            Var array = arrayNamed(reference.name(), env);
            if (array == null || array.dimensions.size() != 1) {
                throw new PliExecutionException(operation + " requires a one-dimensional array");
            }
            int size = array.elements.size();
            int start = arguments.size() == required ? 0 : (int) Math.clamp(
                    (long) number(value(arguments.get(required), env)).intValue()
                            - array.dimensions.getFirst().low(), 0, size);
            int count = arguments.size() <= required + 1 ? size - start
                    : Math.clamp(number(value(arguments.get(required + 1), env)).intValue(), 0, size - start);
            PliSyntax.Procedure comparator = custom
                    ? entryProcedure(value(arguments.get(required - 1), env)) : null;
            int found = 0;
            if (search) {
                Object key = value(arguments.get(1), env);
                if (custom && !(key instanceof PointerValue)) {
                    throw new PliExecutionException("BINSEARCHX requires the address of its key");
                }
                int low = start;
                int high = start + count;
                while (low < high) {
                    int middle = low + (high - low) / 2;
                    int order = arrayCompare(array.elements.get(middle), key, comparator, env);
                    if (order == 0) {
                        found = middle + 1;
                        break;
                    }
                    if (order < 0) {
                        low = middle + 1;
                    } else {
                        high = middle;
                    }
                }
            } else {
                List<Var> sorted = new ArrayList<>(array.elements.subList(start, start + count));
                sorted.sort((left, right) -> arrayCompare(left, comparator == null ? right.read(context)
                        : new PointerValue(address(right.view)), comparator, env));
                List<Object> values = sorted.stream().map(item -> item.read(context)).toList();
                for (int i = 0; i < values.size(); i++) {
                    array.elements.get(start + i).write(values.get(i), context);
                }
            }
            return FixedValue.binary(BigDecimal.valueOf(found), 31, 0);
        }

        private int arrayCompare(Var left, Object right, PliSyntax.Procedure comparator, Env env) {
            if (comparator != null) {
                return number(invoke(comparator, List.of(
                        new PliSyntax.Literal(new PointerValue(address(left.view))),
                        new PliSyntax.Literal(right)), env)).signum();
            }
            Object value = left.read(context);
            if (value instanceof String a && right instanceof String b) {
                int length = Math.max(a.length(), b.length());
                return java.util.Arrays.compareUnsigned(
                        context.codePage().encode(a + " ".repeat(length - a.length())),
                        context.codePage().encode(b + " ".repeat(length - b.length())));
            }
            return compare(value, right);
        }

        /** POINTER に重ねた BASED の変数を、今の値で宣言し直す。 */
        private void rebase(String pointer) {
            List<Runnable> dependents = basedOn.get(pointer.toUpperCase(Locale.ROOT));
            if (dependents != null) {
                // 宣言し直すと新しい手順が覚えられるので、今の並びを写してから動かす
                List<Runnable> current = List.copyOf(dependents);
                dependents.clear();
                current.forEach(Runnable::run);
            }
        }

        private void file(PliSyntax.FileOperation operation, Env env) {
            PliSyntax.FileInfo info = program.files().get(
                    operation.file().toUpperCase(Locale.ROOT));
            String name = operation.file().toUpperCase(Locale.ROOT);
            if (operation.action() == PliSyntax.FileAction.OPEN) {
                // TITLE はファイルを結ぶ DD 名を開くたびに決める。無ければファイルの名である
                // (LRM "TITLE option")。同じ DD を別のファイルが開くこともあるので、用意した
                // データセットは「ファイル名と DD 名の組」で引く
                titles.put(name, operation.title() == null ? name
                        : character(value(operation.title(), env)).strip().toUpperCase(Locale.ROOT));
            }
            String ddName = titles.getOrDefault(name, name);
            String key = ddName.equals(name) ? name : name + "@" + ddName;
            // RECSIZE を書いた FILE は、その形のレコードである。書いていなければ
            // 割り当てた側 (DD) の形に任せる。V 形式の RECSIZE は 4 byte の RDW を含む
            // PRINT でない STREAM の出力も、行を組み立てて書く道は同じである (制御文字は付けない)
            if (info != null && (info.print() || (info.stream() && info.output()))) {
                printFile(operation, info, key, ddName, env);
                return;
            }
            if (info != null && info.keyed()) {
                DataSet indexed = context.file(key, ddName,
                        dev.cobolonjava.runtime.file.RecordFormat.FIXED, info.recordSize(),
                        List.of(new dev.cobolonjava.runtime.file.IndexedDataSet.Key(
                                info.keyLocation() - 1, info.keyLength(), false)));
                if (!(indexed instanceof dev.cobolonjava.runtime.file.IndexedDataSet ksds)) {
                    throw new PliExecutionException(operation.file() + " was opened before as a"
                            + " non-KEYED data set");
                }
                keyed(operation, info, ksds, env);
                return;
            }
            if (operation.key() != null) {
                throw new PliExecutionException("KEY / KEYFROM needs a KEYED file: "
                        + operation.file());
            }
            if (operation.action() == PliSyntax.FileAction.LOCATE) {
                locate(operation, key, env, info, ddName);
                return;
            }
            if (operation.action() == PliSyntax.FileAction.WRITE
                    || operation.action() == PliSyntax.FileAction.CLOSE) {
                flushLocated(key, info, ddName);
            }
            if (operation.action() == PliSyntax.FileAction.REWRITE
                    || operation.action() == PliSyntax.FileAction.DELETE) {
                throw new PliExecutionException(operation.action() + " of a non-KEYED file is"
                        + " not supported yet: " + operation.file());
            }
            DataSet dataSet = info != null && info.recordSize() > 0
                    ? context.file(key, ddName,
                            dev.cobolonjava.runtime.file.Organization.SEQUENTIAL,
                            info.variable() ? dev.cobolonjava.runtime.file.RecordFormat.VARIABLE
                                    : dev.cobolonjava.runtime.file.RecordFormat.FIXED,
                            info.variable() ? info.recordSize() - 4 : info.recordSize())
                    : openSequential(operation, key, ddName);
            switch (operation.action()) {
                case OPEN -> {
                    // 向きは OPEN に書いたもの、無ければ宣言のもの、どちらも無ければ INPUT
                    boolean output = operation.target() != null
                            ? operation.target().equals("OUTPUT") : info != null && info.output();
                    if ("UPDATE".equals(operation.target())) {
                        throw new PliExecutionException("OPEN UPDATE is not supported yet: "
                                + operation.file());
                    }
                    String status = dataSet.open(output ? OpenMode.OUTPUT : OpenMode.INPUT, false);
                    checkOpenStatus(operation.file(), status);
                }
                case WRITE -> {
                    Var source = env.require(operation.target());
                    if (source.varying) {
                        throw new PliExecutionException("WRITE FROM a VARYING string is not"
                                + " supported yet: " + source.name);
                    }
                    checkFileStatus(operation.file(), dataSet.write(source.view.toByteArray()),
                            false);
                }
                case CLOSE -> checkFileStatus(operation.file(), dataSet.close(), false);
                case READ -> {
                    Var target = env.require(operation.target());
                    if (target.varying) {
                        // VARYING へ読むとレコードの長さが今の長さになる。可変長のレコードをまだ持たない
                        throw new PliExecutionException("READ INTO a VARYING string is not"
                                + " supported yet: " + target.name);
                    }
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

        private DataSet openSequential(PliSyntax.FileOperation operation, String key, String ddName) {
            if (operation.action() != PliSyntax.FileAction.OPEN) {
                return context.file(key, ddName);
            }
            // RECSIZE のない FILE も、OPEN した同一のデータセットを後続の文から使う。
            return context.openUndeclaredFile(key, ddName);
        }

        /** 開いている PRINT ファイル。ファイル名ごと。 */
        private final Map<String, UserPrintFile> printFiles = new HashMap<>();

        /** PRINT ファイルの OPEN と CLOSE。LINESIZE と PAGESIZE の既定は 120 と 60。 */
        private void printFile(PliSyntax.FileOperation operation, PliSyntax.FileInfo info,
                               String key, String ddName, Env env) {
            String name = operation.file().toUpperCase(Locale.ROOT);
            switch (operation.action()) {
                case OPEN -> {
                    int control = info.print() ? 1 : 0;
                    int lineSize = operation.lineSize() != null
                            ? number(value(operation.lineSize(), env)).intValue()
                            : info.recordSize() > 1 ? info.recordSize() - control : PrintFile.LINESIZE;
                    int pageSize = operation.pageSize() != null
                            ? number(value(operation.pageSize(), env)).intValue()
                            : PrintFile.PAGESIZE;
                    int recordLength = info.recordSize() > 0 ? info.recordSize() : lineSize + control;
                    if (lineSize + control > recordLength) {
                        throw new PliExecutionException("LINESIZE(" + lineSize + ") does not fit"
                                + " RECSIZE(" + recordLength + ") of " + name);
                    }
                    DataSet dataSet = context.file(key, ddName,
                            dev.cobolonjava.runtime.file.Organization.SEQUENTIAL,
                            dev.cobolonjava.runtime.file.RecordFormat.FIXED, recordLength);
                    checkOpenStatus(name, dataSet.open(OpenMode.OUTPUT, false));
                    printFiles.put(name, new UserPrintFile(dataSet, context.codePage(), lineSize,
                            pageSize, recordLength, () -> endPage(name, env), info.print()));
                }
                case CLOSE -> {
                    UserPrintFile file = printFiles.remove(name);
                    if (file != null) {
                        file.close();
                    }
                    checkFileStatus(name, context.file(key, ddName).close(), false);
                }
                default -> throw new PliExecutionException(operation.action()
                        + " of the PRINT file " + name + " is not supported");
            }
        }

        /** ENDPAGE の on-unit があれば動かして真を返す。PUT はそのあと続く。 */
        private boolean endPage(String file, Env env) {
            Env.Handler handler = env.handler(conditionKey("ENDPAGE", file));
            if (handler == null || handler.system()) {
                return false;
            }
            int savedCode = onCode;
            onCode = 90;
            try {
                execute(handler.statements(), new Env(handler.established(), context));
            } finally {
                onCode = savedCode;
            }
            return true;
        }

        /** LOCATE で取ったまま、まだ書いていないバッファ。ファイルの鍵ごと。 */
        private final Map<String, DataView> located = new HashMap<>();

        /**
         * {@code LOCATE}。出力のバッファを取って locator (SET か BASED(P) の P) に入れる。
         * バッファは、そのファイルの次の WRITE・LOCATE・CLOSE の前に書き出す (LRM "LOCATE
         * statement")。
         */
        private void locate(PliSyntax.FileOperation operation, String key, Env env,
                            PliSyntax.FileInfo info, String ddName) {
            flushLocated(key, info, ddName);
            String name = operation.target().toUpperCase(Locale.ROOT);
            List<PliSyntax.Decl> chunk = declaredChunks.get(name);
            String locator = operation.title() instanceof PliSyntax.Reference set ? set.name()
                    : chunk == null ? null : chunk.get(0).basedOn();
            if (chunk == null || locator == null) {
                throw new PliExecutionException("LOCATE " + name + " needs SET or BASED(pointer)");
            }
            int length;
            try {
                length = chunk.size() > 1 ? StructureMapping.map(chunk).size()
                        : byteLength(chunk.get(0));
            } catch (IllegalArgumentException unsupported) {
                throw new PliExecutionException(unsupported.getMessage());
            }
            DataView buffer = Storage.allocate(Math.max(1, length)).whole();
            Var pointer = env.require(locator);
            pointer.write(new PointerValue(address(buffer)), context);
            rebase(pointer.name);
            located.put(key, buffer);
        }

        private void flushLocated(String key, PliSyntax.FileInfo info, String ddName) {
            DataView buffer = located.remove(key);
            if (buffer == null) {
                return;
            }
            DataSet dataSet = info != null && info.recordSize() > 0
                    ? context.file(key, ddName,
                            dev.cobolonjava.runtime.file.Organization.SEQUENTIAL,
                            dev.cobolonjava.runtime.file.RecordFormat.FIXED, info.recordSize())
                    : context.file(key, ddName);
            checkFileStatus(key, dataSet.write(buffer.toByteArray()), false);
        }

        /**
         * KEYED のファイル (VSAM KSDS) の操作 (LRM "Keyed access")。鍵はレコードの中の
         * KEYLOC から KEYLENGTH byte にある。鍵の無いレコードを読もうとすれば ONCODE 51、
         * 重なる鍵を書こうとすれば 52 で KEY 条件を起こす (番号は Zeus と同じ。実機と
         * 突き合わせていない)。
         */
        private void keyed(PliSyntax.FileOperation operation, PliSyntax.FileInfo info,
                           dev.cobolonjava.runtime.file.IndexedDataSet dataSet, Env env) {
            String file = operation.file().toUpperCase(Locale.ROOT);
            byte[] key = operation.key() == null ? null : keyBytes(operation.key(), info, env);
            switch (operation.action()) {
                case OPEN -> {
                    OpenMode mode = "UPDATE".equals(operation.target()) ? OpenMode.IO
                            : "OUTPUT".equals(operation.target())
                                    || (operation.target() == null && info.output())
                                    ? OpenMode.OUTPUT : OpenMode.INPUT;
                    checkOpenStatus(operation.file(), dataSet.open(mode, false));
                }
                case CLOSE -> checkFileStatus(operation.file(), dataSet.close(), false);
                case READ -> {
                    if (key == null) {
                        throw new PliExecutionException("sequential READ of a KEYED file is not"
                                + " supported yet: " + operation.file());
                    }
                    Var target = env.require(operation.target());
                    byte[] record = new byte[info.recordSize()];
                    String status = dataSet.readKey(0, key, record);
                    if ("23".equals(status)) {
                        throw new Condition("KEY", file, 51, null);
                    }
                    checkFileStatus(operation.file(), status, false);
                    copy(dev.cobolonjava.runtime.storage.Storage.wrap(record).whole(),
                            target.view, context.codePage().space());
                }
                case WRITE, REWRITE -> {
                    Var source = env.require(operation.target());
                    byte[] record = new byte[info.recordSize()];
                    java.util.Arrays.fill(record, context.codePage().space());
                    byte[] data = source.view.toByteArray();
                    System.arraycopy(data, 0, record, 0, Math.min(data.length, record.length));
                    if (key != null && !java.util.Arrays.equals(key, java.util.Arrays.copyOfRange(
                            record, info.keyLocation() - 1,
                            info.keyLocation() - 1 + info.keyLength()))) {
                        // 鍵がレコードの中にある (KEYLOC) とき、KEYFROM / KEY はその鍵と同じで
                        // なければならない。違うときの扱いは確かめていないので断る
                        throw new PliExecutionException("the KEY of " + operation.action()
                                + " differs from the key in the record: " + operation.file());
                    }
                    boolean write = operation.action() == PliSyntax.FileAction.WRITE;
                    String status = write ? dataSet.writeKey(record) : dataSet.rewriteKey(record);
                    if (write && "22".equals(status)) {
                        throw new Condition("KEY", file, 52, null);
                    }
                    if (!write && "23".equals(status)) {
                        throw new Condition("KEY", file, 51, null);
                    }
                    checkFileStatus(operation.file(), status, false);
                }
                case DELETE -> {
                    String status = dataSet.deleteKey(key);
                    if ("23".equals(status)) {
                        throw new Condition("KEY", file, 51, null);
                    }
                    checkFileStatus(operation.file(), status, false);
                }
            }
        }

        /** KEY / KEYFROM の値を、鍵の長さの文字にする (短ければ空白で埋める)。 */
        private byte[] keyBytes(PliSyntax.Expr expression, PliSyntax.FileInfo info, Env env) {
            byte[] encoded = context.codePage().encode(character(value(expression, env)));
            byte[] key = new byte[info.keyLength()];
            java.util.Arrays.fill(key, context.codePage().space());
            System.arraycopy(encoded, 0, key, 0, Math.min(encoded.length, key.length));
            return key;
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

        private static void checkOpenStatus(String file, String status) {
            if (!"00".equals(status) && !"05".equals(status)) {
                throw new Condition("UNDEFINEDFILE", file, 84, null);
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
                    // 属性を見るだけで値は読まない。ALLOCATE していない CONTROLLED は
                    // ALLOCATION(X) の引数に書けるので、ここで止めない
                    Var variable = env.find(reference.name());
                    scanVariable(variable == null || variable.unallocated ? null : variable, wide);
                }
                case PliSyntax.Unary unary -> scan(unary.operand(), env, wide);
                case PliSyntax.Binary binary -> {
                    scan(binary.left(), env, wide);
                    scan(binary.right(), env, wide);
                }
                case PliSyntax.Function function -> {
                    // 配列の要素は、その配列の型の葉である
                    scanVariable(arrayNamed(function.name(), env), wide);
                    function.arguments().forEach(argument -> scan(argument, env, wide));
                }
            }
        }

        private void scanVariable(Var variable, boolean[] wide) {
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

        private Object evaluate(PliSyntax.Expr expression, Env env) {
            if (expression instanceof PliSyntax.Literal literal) {
                if (literal.value() instanceof PliSyntax.HexString hex) {
                    // 16 進の文字の定数は、実行時のコードページで文字に直す。代入で同じコードページへ
                    // 戻すので、書いた byte がそのまま記憶域に入る
                    return context.codePage().decode(hex.bytes());
                }
                return literal.value();
            }
            if (expression instanceof PliSyntax.Reference reference
                    && reference.name().contains("->")) {
                return located(reference.name(), env,
                        rest -> evaluate(new PliSyntax.Reference(rest), env));
            }
            if (expression instanceof PliSyntax.Reference reference && !env.contains(reference.name())
                    && program.procedures().containsKey(reference.name())) {
                // 手続きの名は入口の値である (入口の変数へ代入できる)
                return new EntryValue(reference.name());
            }
            if (expression instanceof PliSyntax.Reference reference
                    && reference.name().equals("CODEPAGE") && !env.contains(reference.name())) {
                return codepage();
            }
            if (expression instanceof PliSyntax.Reference reference) {
                Var variable = env.require(reference.name());
                if (variable.dimensions != null) {
                    if (elementIndex >= 0) {
                        if (!variable.dimensions.equals(elementShape)) {
                            throw new PliExecutionException("array " + variable.name
                                    + " has different bounds from the target of the assignment");
                        }
                        return variable.elements.get(elementIndex).read(context);
                    }
                    // 配列の式は、配列への代入の右辺にだけ書ける。スカラーへの代入や PUT の中では、
                    // 記憶域をまとめて読むと黙って違う値になるので断る
                    throw new PliExecutionException("array expressions are supported only as the"
                            + " value assigned to an array: " + variable.name);
                }
                return variable.read(context);
            }
            if (expression instanceof PliSyntax.Function function
                    && arrayNamed(function.name(), env) != null) {
                return element(arrayNamed(function.name(), env), function.arguments(), env)
                        .read(context);
            }
            if (expression instanceof PliSyntax.Unary unary) {
                Object operand = value(unary.operand(), env);
                return switch (unary.operator()) {
                    // 2 ビット以上のビット列はビットごとに反転する
                    case "^", "¬" -> operand instanceof PliSyntax.BitString bits
                            ? bitwise(bits.bits(), "", (x, y) -> x == '1' ? '0' : '1')
                            : !truth(operand);
                    case "-" -> operand instanceof FixedValue fixed ? fixed.negate()
                            : operand instanceof PliSyntax.Complex z
                                    ? new PliSyntax.Complex(z.real().negate(),
                                            z.imaginary().negate())
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
                    case "**" -> power(number(left), number(right));
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
            // SIZE と ADDR は引数の値ではなく変数そのものを見る。値を先に求めると、配列を
            // 渡したときに配列の式として断られる
            switch (name) {
                case "VALIDVALUE" -> {
                    return validValue(function.arguments(), env);
                }
                case "SIZE", "STORAGE", "STG" -> {
                    return size(function, env);
                }
                case "CURRENTSIZE", "CSTG", "CURRENTSTORAGE" -> {
                    // 今の大きさ (LRM "CURRENTSIZE")。VARYING の文字なら今の長さ + 2、REFER の
                    // 構造は今の形の大きさ (重ね直しで決まる) である
                    Var target = dataReference(function.arguments().get(0), env, name);
                    int size = target.type == PliSyntax.Type.CHAR && target.varying
                            ? target.currentLength() + 2 : target.view.length();
                    if (target.type == PliSyntax.Type.GROUP && elements(target).stream()
                            .anyMatch(member -> member.varying)) {
                        throw new PliExecutionException("CURRENTSIZE of a structure with VARYING"
                                + " members is not supported yet: " + target.name);
                    }
                    return FixedValue.binary(BigDecimal.valueOf(size), 31, 0);
                }
                case "ADDR", "ADDRDATA" -> {
                    return address(function, env);
                }
                case "BITLOCATION", "BITLOC" -> {
                    if (function.arguments().size() != 1) {
                        throw new PliExecutionException(name + " requires one data reference");
                    }
                    Var target = dataReference(function.arguments().getFirst(), env, name);
                    return FixedValue.binary(BigDecimal.valueOf(target.bitShift), 31, 0);
                }
                // UNSPEC と HEX は値ではなく記憶域のビットを見る (LRM "UNSPEC", "HEX")
                case "UNSPEC" -> {
                    return new PliSyntax.BitString(bitsOf(storageOf(function, env)));
                }
                case "HEX" -> {
                    return hexOf(function, env);
                }
                case "RANK" -> {
                    if (function.arguments().size() != 1) {
                        throw new PliExecutionException("RANK requires one argument");
                    }
                    return rank(function.arguments().getFirst(), env);
                }
                case "GRAPHIC", "MPSTR" -> {
                    return mixedGraphic(function, env);
                }
                case "UTF8STG" -> {
                    if (function.arguments().size() != 1) {
                        throw new PliExecutionException("UTF8STG requires one argument");
                    }
                    PliSyntax.Expr input = function.arguments().getFirst();
                    Object evaluated = input instanceof PliSyntax.Literal literal
                            ? literal.value() : value(input, env);
                    byte[] bytes = evaluated instanceof PliSyntax.HexString hex ? hex.bytes()
                            : context.codePage().encode(display(evaluated));
                    return FixedValue.binary(BigDecimal.valueOf(utf8Size(bytes)), 31, 0);
                }
                case "POINTER", "PTR" -> {
                    if (env.contains(name)) {
                        break;
                    }
                    // POINTER(offset, area): AREA の中の位置を指す POINTER (LRM "POINTER")
                    Var area = dataReference(function.arguments().get(1), env, name);
                    if (area.type != PliSyntax.Type.AREA) {
                        throw new PliExecutionException(name + " needs an AREA: " + area.name);
                    }
                    int at = number(value(function.arguments().get(0), env)).intValue();
                    if (at < AREA_HEADER || at > area.view.length()) {
                        throw new PliExecutionException("offset " + at + " is outside the AREA "
                                + area.name);
                    }
                    return new PointerValue(AddressSpace.of(context).addressOf(
                            area.view.storage(), area.view.offset() + at));
                }
                case "OFFSET" -> {
                    if (env.contains(name)) {
                        break;
                    }
                    // OFFSET(pointer, area): POINTER が指す位置の AREA の頭からの位置
                    Object pointer = value(function.arguments().get(0), env);
                    Var area = dataReference(function.arguments().get(1), env, name);
                    AddressSpace.Location location = pointer instanceof PointerValue p
                            ? AddressSpace.of(context).locate(p.address()) : null;
                    if (area.type != PliSyntax.Type.AREA || location == null
                            || location.storage() != area.view.storage()
                            || location.offset() < area.view.offset()
                            || location.offset() > area.view.offset() + area.view.length()) {
                        throw new PliExecutionException("OFFSET: the POINTER does not point into"
                                + " the AREA");
                    }
                    return FixedValue.binary(BigDecimal.valueOf(
                            location.offset() - area.view.offset()), 31, 0);
                }
                case "EMPTY" -> {
                    return EMPTY_AREA;
                }
                case "HANDLE" -> {
                    if (env.contains(name)) break;
                    // 型つきの構造を指す値 (LRM "HANDLE")。ここでは POINTER と同じ番号である
                    return address(function, env);
                }
                case "NEW" -> {
                    if (env.contains(name)) break;
                    // NEW(:T:) は型 T の記憶域を取る。翻訳で NEW('#T') に読み替えてある
                    Object type = value(function.arguments().get(0), env);
                    List<PliSyntax.Decl> chunk = type instanceof String text
                            ? declaredChunks.get(text) : null;
                    if (chunk == null) {
                        throw new PliExecutionException("NEW needs a type defined by DEFINE"
                                + " STRUCTURE");
                    }
                    int length;
                    try {
                        length = StructureMapping.map(chunk).size();
                    } catch (IllegalArgumentException unsupported) {
                        throw new PliExecutionException(unsupported.getMessage());
                    }
                    return new PointerValue(address(Storage.allocate(Math.max(1, length)).whole()));
                }
                case "COUNT" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        String file = function.arguments().get(0) instanceof PliSyntax.Reference r
                                ? r.name().toUpperCase(Locale.ROOT) : "";
                        UserPrintFile stream = printFiles.get(file);
                        if (stream == null) {
                            throw new PliExecutionException("COUNT needs an open STREAM output file"
                                    + " yet: " + file);
                        }
                        return fixedBinary31(stream.count());
                    }
                }
                case "LINENO", "PAGENO" -> {
                    String file = function.arguments().get(0) instanceof PliSyntax.Reference r
                            ? r.name().toUpperCase(Locale.ROOT) : "";
                    UserPrintFile print = printFiles.get(file);
                    if (print == null) {
                        throw new PliExecutionException(name + " needs an open PRINT file other"
                                + " than SYSPRINT yet: " + file);
                    }
                    return FixedValue.binary(BigDecimal.valueOf(name.equals("LINENO")
                            ? print.lineNumber() : print.pageNumber()), 31, 0);
                }
                case "ALLOCATION", "ALLOCN" -> {
                    // 世代の数 (LRM "ALLOCATION")。CONTROLLED でなければ誤りである
                    PliSyntax.Expr argument = function.arguments().get(0);
                    Var target = argument instanceof PliSyntax.Reference reference
                            ? env.find(reference.name()) : null;
                    if (target == null || target.generations == null) {
                        throw new PliExecutionException("ALLOCATION needs a CONTROLLED variable");
                    }
                    return FixedValue.binary(BigDecimal.valueOf(target.generations.saved.size()),
                            31, 0);
                }
                case "BINSEARCH", "BINSEARCHX" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return arrayOrder(name, function.arguments(), env);
                    }
                }
                case "ALLCOMPARE" -> {
                    return allCompare(function.arguments(), env);
                }
                case "INARRAY" -> {
                    if (function.arguments().size() != 2) {
                        throw new PliExecutionException("INARRAY requires a value and an array");
                    }
                    Object sought = value(function.arguments().get(0), env);
                    Var array = dataReference(function.arguments().get(1), env, name);
                    if (array.dimensions == null) {
                        throw new PliExecutionException("INARRAY requires an array");
                    }
                    for (Var element : array.elements) {
                        if (compare(sought, element.read(context)) == 0) return true;
                    }
                    return false;
                }
                case "XMLCHAR" -> {
                    return xmlChar(function.arguments(), env);
                }
                case "DIM", "HBOUND", "LBOUND" -> {
                    Var array = dataReference(function.arguments().get(0), env, name);
                    if (array.dimensions == null) {
                        throw new PliExecutionException(name + " needs an array: " + array.name);
                    }
                    int dimension = function.arguments().size() > 1
                            ? number(value(function.arguments().get(1), env)).intValue() : 1;
                    if (dimension < 1 || dimension > array.dimensions.size()) {
                        throw new PliExecutionException(name + ": " + array.name + " has no"
                                + " dimension " + dimension);
                    }
                    PliSyntax.Bound bound = array.dimensions.get(dimension - 1);
                    int result = switch (name) {
                        case "DIM" -> bound.extent();
                        case "HBOUND" -> bound.high();
                        default -> bound.low();
                    };
                    return FixedValue.binary(BigDecimal.valueOf(result), 31, 0);
                }
                case "SUM" -> {
                    // 結果は要素と同じ型 (LRM "SUM")。FIXED の要素だけを持つ
                    Var array = dataReference(function.arguments().get(0), env, name);
                    if (array.dimensions == null) {
                        throw new PliExecutionException("SUM needs an array: " + array.name);
                    }
                    Object total = null;
                    for (Var element : array.elements) {
                        Object value = element.read(context);
                        if (!(value instanceof FixedValue)) {
                            throw new PliExecutionException("SUM of a non-FIXED array is not"
                                    + " supported yet: " + array.name);
                        }
                        total = total == null ? value : arithmetic("+", total, value,
                                new FixedValue.Arithmetic(options, false, false));
                    }
                    return total;
                }
                case "PROD", "ALL", "ANY" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return arrayReduction(name, function.arguments(), env);
                    }
                }
                case "HUGE", "TINY", "EPSILON", "MAXEXP", "MINEXP", "RADIX" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return floatModel(name, function.arguments(), env);
                    }
                }
                // 選んだほうだけを評価する (LRM "IFTHENELSE")
                case "IFTHENELSE" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return truth(value(function.arguments().get(0), env))
                                ? value(function.arguments().get(1), env)
                                : value(function.arguments().get(2), env);
                    }
                }
                case "MAXVAL", "MINVAL", "PLACES", "PRECVAL", "SCALEVAL", "SUCC", "PRED",
                     "LOCATION" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return attributeFunction(name, function.arguments(), env);
                    }
                }
                case "PLISTCK", "PLISTCKE", "PLISTCKF", "PLISTCKLOCAL", "PLISTCKUTC", "PLISTCKELOCAL",
                     "PLISTCKEUTC", "PLISTCKP", "PLISTCKPLOCAL", "PLISTCKPUTC" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return storeClock(name, function.arguments(), env);
                    }
                }
                // 10 進の IEEE の値の種類 (LRM "ISZERO" ほか)。翻訳で FLOAT(DFP) を確かめてある
                case "ISZERO", "ISINF", "ISNAN", "ISFINITE", "ISNORMAL" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        Var target = dataReference(function.arguments().get(0), env, name);
                        byte[] bytes = target.view.toByteArray();
                        PliFloat.Kind kind = "DFP".equals(target.floatFormat)
                                ? PliFloat.classifyDecimal(bytes)
                                : "IEEE".equals(target.floatFormat) ? PliFloat.classifyBinary(bytes)
                                : number(target.read(context)).signum() == 0 ? PliFloat.Kind.ZERO
                                : PliFloat.Kind.NORMAL;
                        return switch (name) {
                            case "ISZERO" -> kind == PliFloat.Kind.ZERO;
                            case "ISINF" -> kind == PliFloat.Kind.INFINITE;
                            case "ISNAN" -> kind == PliFloat.Kind.NAN;
                            case "ISFINITE" -> kind != PliFloat.Kind.INFINITE && kind != PliFloat.Kind.NAN;
                            default -> kind == PliFloat.Kind.NORMAL;
                        };
                    }
                }
                case "POLY" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return polynomial(function.arguments(), env);
                    }
                }
                case "VALID" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return valid(dataReference(function.arguments().get(0), env, name));
                    }
                }
                // いま動いている手続きの名と、主手続き (LRM "PROCEDURENAME", "MAINNAME", "ISMAIN")
                case "PROCEDURENAME", "PROCNAME" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return currentProcedure();
                    }
                }
                case "MAINNAME" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return program.name().toUpperCase(Locale.ROOT);
                    }
                }
                case "ISMAIN" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return scope.isEmpty();
                    }
                }
                case "SOURCEFILE" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return program.name().toUpperCase(Locale.ROOT) + ".PLI";
                    }
                }
                case "POINTERDIFF" -> {
                    if (!env.contains(name) && !program.procedures().containsKey(name)) {
                        return pointerDifference(function.arguments(), env);
                    }
                }
                case "MAXLENGTH" -> {
                    Var target = dataReference(function.arguments().get(0), env, "MAXLENGTH");
                    return FixedValue.binary(BigDecimal.valueOf(target.capacity()), 31, 0);
                }
                default -> {
                }
            }
            PliSyntax.Procedure procedure = program.procedures().get(function.name());
            List<PliSyntax.Expr> callArguments = function.arguments();
            if (function.name().equals("#APPLY")) {
                // 入口の値を返した式を、続く引数で呼ぶ (TAB(1)(3))
                procedure = entryProcedure(value(function.arguments().get(0), env));
                callArguments = function.arguments().subList(1, function.arguments().size());
            } else if (procedure == null && program.generics().containsKey(function.name())) {
                procedure = generic(function.name(), callArguments, env);
            } else if (procedure == null && env.find(function.name()) != null
                    && env.find(function.name()).type == PliSyntax.Type.ENTRY
                    && env.find(function.name()).dimensions == null) {
                procedure = entryProcedure(env.require(function.name()).read(context));
            }
            if (procedure != null) {
                Object result = invoke(procedure, callArguments, env);
                if (result == null) {
                    throw new PliExecutionException("procedure " + procedure.name()
                            + " was used as a function but returned no value");
                }
                return result;
            }
            List<Object> arguments = function.arguments().stream().map(e -> value(e, env)).toList();
            Matcher sha = SHA.matcher(name);
            if (sha.matches()) {
                return sha(sha, arguments);
            }
            // バイトで決まる結果 (COLLATE、UNHEX など) は、'..'X の定数と同じく実行時の
            // コードページで文字に直す。代入や UNSPEC で同じコードページへ戻すので、バイトは保たれる
            Object builtin = switch (name) {
                case "TRIM" -> display(arguments.get(0)).strip();
                // バイトの値 (COLLATE、'..'X) はバイトのまま切る。文字にしてから切ると
                // コードページの往復で長さが変わりうる
                case "SUBSTR" -> arguments.get(0) instanceof PliSyntax.HexString hex
                        ? new PliSyntax.HexString(context.codePage().encode(substring(List.of(
                                context.codePage().decode(hex.bytes()), arguments.get(1),
                                arguments.size() > 2 ? arguments.get(2)
                                        : FixedValue.binary(BigDecimal.valueOf(
                                                hex.bytes().length - number(arguments.get(1))
                                                        .intValue() + 1), 31, 0)))))
                        : substring(arguments);
                case "DATETIME" -> ZonedDateTime.now(context.clock()).format(DATETIME);
                // PACKAGE が無ければ、いちばん外の手続きの名である (LRM "PACKAGENAME")
                case "PACKAGENAME" -> program.packageName() == null
                        ? program.name().toUpperCase(Locale.ROOT) : program.packageName();
                case "MICROSECS" -> dateBuiltin(name, arguments);
                case "CHAR", "CHARACTER" -> display(arguments.get(0));
                // STORAGE / STG は SIZE の別名である (LRM "STORAGE")。上で済ませている
                // 文字列の長さ。固定長の CHAR(n) なら n、VARYING なら今の長さ (LRM "LENGTH")
                case "LENGTH" -> FixedValue.binary(BigDecimal.valueOf(characterLength(arguments.getFirst())), 31, 0);
                // LOW(n) は照合順序のいちばん低い字 (X'00') を n 個、HIGH(n) はいちばん高い字
                // (X'FF') を n 個 (LRM "LOW", "HIGH")
                case "LOW" -> filled((byte) 0x00, arguments);
                case "HIGH" -> filled((byte) 0xFF, arguments);
                case "COLLATE" -> {
                    byte[] collating = new byte[256];
                    for (int i = 0; i < collating.length; i++) {
                        collating[i] = (byte) i;
                    }
                    yield new PliSyntax.HexString(collating);
                }
                case "CENTRE", "CENTER" -> centre(arguments);
                // 結果は x と同じ base・scale・precision を持つ (LRM "ABS")
                case "ABS" -> arguments.get(0) instanceof FixedValue fixed
                        ? (fixed.value().signum() < 0 ? fixed.negate() : fixed)
                        // 複素数の絶対値は実部と虚部の 2 乗の和の正の平方根 (LRM "ABS")
                        : arguments.get(0) instanceof PliSyntax.Complex z
                                ? z.real().multiply(z.real()).add(z.imaginary()
                                        .multiply(z.imaginary()))
                                        .sqrt(java.math.MathContext.DECIMAL64)
                                        .stripTrailingZeros()
                        : number(arguments.get(0)).abs();
                case "REAL" -> complex(arguments.get(0)).real();
                case "IMAG" -> complex(arguments.get(0)).imaginary();
                case "CONJG" -> new PliSyntax.Complex(complex(arguments.get(0)).real(),
                        complex(arguments.get(0)).imaginary().negate());
                case "COMPLEX", "CPLX" -> new PliSyntax.Complex(number(arguments.get(0)),
                        number(arguments.get(1)));
                // x を y 回<b>つなげ足す</b>ので y+1 個になる。y が 0 以下なら x そのもの (LRM "REPEAT")
                case "REPEAT" -> display(arguments.get(0))
                        .repeat(Math.max(0, number(arguments.get(1)).intValue()) + 1);
                default -> moreBuiltins(name, arguments);
            };
            return builtin instanceof PliSyntax.HexString hex
                    ? context.codePage().decode(hex.bytes()) : builtin;
        }

        /**
         * 組み込み関数の続き。意味は Enterprise PL/I Language Reference の各頁に従い、
         * 並びは Zeus (同じ権利者の処理系) の {@code vm/bif.rs} を見て決めた。
         * 実機とは突き合わせていない。
         */
        private Object moreBuiltins(String name, List<Object> arguments) {
            return switch (name) {
                case "CHARGRAPHIC", "CHARG" -> {
                    if (arguments.size() < 1 || arguments.size() > 2
                            || !(arguments.getFirst() instanceof PliGraphic graphic)) {
                        throw new PliExecutionException(name + " requires a GRAPHIC value and an optional length");
                    }
                    Integer length = arguments.size() == 2 ? nonnegativeLength(arguments.get(1)) : null;
                    yield new MixedCharacters(graphic.mixed(length), context.codePage());
                }
                case "CODEPAGE" -> codepage();
                case "WCHARVAL" -> new WideCharacter((char) number(arguments.getFirst()).intValue());
                case "PICSPEC" -> {
                    PliPicture picture = PliPicture.parse(display(arguments.get(1)));
                    String text = display(arguments.get(0));
                    if (picture == null) yield text;
                    yield new NumericPicture(text, picture.precision, picture.scale, picture);
                }
                case "REVERSE" -> new StringBuilder(display(arguments.get(0))).reverse().toString();
                case "XMLSCRUB", "XMLSCRUB16", "XMLCLEAN" -> xmlScrub(name, arguments);
                case "MEMCU12", "MEMCU14", "MEMCU21", "MEMCU24", "MEMCU41", "MEMCU42",
                        "MEMCONVERT" -> convertBuffer(name, arguments);
                // ORDINAL の組み込み関数。2 つ目の引数は翻訳で足した要素の表 (名=値,...) である
                case "ORDINALNAME", "ORDINALSUCC", "ORDINALPRED" -> {
                    int x = number(arguments.get(0)).intValue();
                    List<String[]> members = new ArrayList<>();
                    for (String member : display(arguments.get(1)).split(",")) {
                        members.add(member.split("="));
                    }
                    int k = -1;
                    for (int i = 0; i < members.size(); i++) {
                        if (Integer.parseInt(members.get(i)[1]) == x) k = i;
                    }
                    int target = name.equals("ORDINALSUCC") ? k + 1
                            : name.equals("ORDINALPRED") ? k - 1 : k;
                    if (k < 0 && conformanceEnabled) {
                        throw new Condition("CONFORMANCE", null, 559, null);
                    }
                    if (name.equals("ORDINALNAME")) {
                        yield k < 0 ? "" : members.get(k)[0];
                    }
                    long result = k >= 0 && target >= 0 && target < members.size()
                            ? Long.parseLong(members.get(target)[1])
                            : (long) x + (name.equals("ORDINALSUCC") ? 1 : -1);
                    yield FixedValue.binary(BigDecimal.valueOf(result), 31, 0);
                }
                case "BINVALUE", "BINARYVALUE" -> arguments.get(0) instanceof PointerValue pointer
                        ? FixedValue.binary(BigDecimal.valueOf(pointer.address()), 31, 0)
                        : FixedValue.binary(number(arguments.get(0)).setScale(0,
                                java.math.RoundingMode.DOWN), 31, 0);
                case "UPPERCASE" -> display(arguments.get(0)).toUpperCase(Locale.ROOT);
                case "LOWERCASE" -> display(arguments.get(0)).toLowerCase(Locale.ROOT);
                case "BETWEEN" -> {
                    if (arguments.size() != 3) {
                        throw new PliExecutionException("BETWEEN requires three arguments");
                    }
                    yield between(arguments.get(0), arguments.get(1), arguments.get(2));
                }
                // 見つかった位置 (1 から)。無ければ 0 (LRM "INDEX")
                case "INDEX" -> FixedValue.binary(BigDecimal.valueOf(
                        display(arguments.get(0)).indexOf(display(arguments.get(1))) + 1), 31, 0);
                // 第 2 引数に無い字の最初の位置。全部あれば 0 (LRM "VERIFY")
                case "VERIFY" -> {
                    String text = display(arguments.get(0));
                    String allowed = display(arguments.get(1));
                    int at = 0;
                    for (int i = 0; i < text.length(); i++) {
                        if (allowed.indexOf(text.charAt(i)) < 0) {
                            at = i + 1;
                            break;
                        }
                    }
                    yield FixedValue.binary(BigDecimal.valueOf(at), 31, 0);
                }
                // 第 2 引数の字のどれかの最初の位置 (LRM "SEARCH")
                case "SEARCH" -> {
                    String text = display(arguments.get(0));
                    String wanted = display(arguments.get(1));
                    int at = 0;
                    for (int i = 0; i < text.length(); i++) {
                        if (wanted.indexOf(text.charAt(i)) >= 0) {
                            at = i + 1;
                            break;
                        }
                    }
                    yield FixedValue.binary(BigDecimal.valueOf(at), 31, 0);
                }
                // 重ならない出現の数 (LRM "TALLY")
                case "TALLY" -> {
                    String text = display(arguments.get(0));
                    String part = display(arguments.get(1));
                    int count = 0;
                    for (int at = part.isEmpty() ? -1 : text.indexOf(part); at >= 0;
                         at = text.indexOf(part, at + part.length())) {
                        count++;
                    }
                    yield FixedValue.binary(BigDecimal.valueOf(count), 31, 0);
                }
                // TRANSLATE(x, 置き換える字, 置き換えられる字)。第 3 引数を省けば全 256 字
                case "TRANSLATE" -> {
                    String text = display(arguments.get(0));
                    String to = display(arguments.get(1));
                    String from = arguments.size() > 2 ? display(arguments.get(2)) : null;
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        int at = from == null ? -1 : from.indexOf(c);
                        out.append(at >= 0 ? (at < to.length() ? to.charAt(at) : ' ') : c);
                    }
                    yield out.toString();
                }
                case "COPY" -> display(arguments.get(0))
                        .repeat(Math.max(0, number(arguments.get(1)).intValue()));
                // LEFT(x, n[, 埋める字]) は左に寄せて右を埋める。RIGHT は右に寄せる
                case "LEFT", "RIGHT" -> {
                    String text = display(arguments.get(0));
                    int length = number(arguments.get(1)).intValue();
                    char pad = arguments.size() > 2 ? display(arguments.get(2)).charAt(0) : ' ';
                    String padding = String.valueOf(pad).repeat(Math.max(0, length - text.length()));
                    yield name.equals("LEFT")
                            ? (text.length() >= length ? text.substring(0, length) : text + padding)
                            : (text.length() >= length ? text.substring(text.length() - length)
                                    : padding + text);
                }
                case "MAX", "MIN" -> {
                    Object best = arguments.get(0);
                    for (Object next : arguments.subList(1, arguments.size())) {
                        int order = number(next).compareTo(number(best));
                        if (name.equals("MAX") ? order > 0 : order < 0) {
                            best = next;
                        }
                    }
                    yield best;
                }
                // MOD の結果は第 2 引数と同じ符号を持たない: 0 以上で y より小さい (LRM "MOD")
                case "MOD" -> {
                    BigDecimal x = number(arguments.get(0));
                    BigDecimal y = number(arguments.get(1));
                    BigDecimal r = x.remainder(y);
                    if (r.signum() != 0 && r.signum() != y.signum()) {
                        r = r.add(y);
                    }
                    yield r;
                }
                case "REM" -> number(arguments.get(0)).remainder(number(arguments.get(1)));
                case "SIGN" -> FixedValue.binary(BigDecimal.valueOf(
                        number(arguments.get(0)).signum()), 15, 0);
                case "FLOOR" -> number(arguments.get(0)).setScale(0, java.math.RoundingMode.FLOOR);
                case "CEIL" -> number(arguments.get(0)).setScale(0, java.math.RoundingMode.CEILING);
                case "TRUNC" -> number(arguments.get(0)).setScale(0, java.math.RoundingMode.DOWN);
                // ROUND(x, n) は小数点以下 n 桁へ、半分は 0 から遠いほうへ
                case "ROUND" -> number(arguments.get(0)).setScale(
                        number(arguments.get(1)).intValue(), java.math.RoundingMode.HALF_UP);
                case "SQRT", "SQRTF" -> number(arguments.get(0)).sqrt(java.math.MathContext.DECIMAL64);
                // 浮動小数点の関数。結果は FLOAT であり、16 桁で返す。実機の HFP の桁とは
                // 突き合わせていない
                case "SIN", "COS", "TAN", "ATAN", "LOG", "LOG10", "EXP", "SINH", "COSH",
                     "TANH", "ASIN", "ACOS", "ATAND", "SIND", "COSD", "TAND", "ATANH", "LOG2" -> {
                    double x = number(arguments.get(0)).doubleValue();
                    double r = switch (name) {
                        case "SIN" -> Math.sin(x);
                        case "COS" -> Math.cos(x);
                        case "TAN" -> Math.tan(x);
                        // ATAN(y, x) は y/x の逆正接を、象限を見て返す (LRM "ATAN")
                        case "ATAN" -> arguments.size() > 1
                                ? Math.atan2(x, number(arguments.get(1)).doubleValue())
                                : Math.atan(x);
                        // D の付くものは度で受けて度で返す (LRM "SIND", "ATAND")
                        case "ATAND" -> Math.toDegrees(arguments.size() > 1
                                ? Math.atan2(x, number(arguments.get(1)).doubleValue())
                                : Math.atan(x));
                        case "SIND" -> Math.sin(Math.toRadians(x));
                        case "COSD" -> Math.cos(Math.toRadians(x));
                        case "TAND" -> Math.tan(Math.toRadians(x));
                        case "ATANH" -> 0.5 * Math.log((1 + x) / (1 - x));
                        case "LOG2" -> Math.log(x) / Math.log(2);
                        case "LOG" -> Math.log(x);
                        case "LOG10" -> Math.log10(x);
                        case "EXP" -> Math.exp(x);
                        case "SINH" -> Math.sinh(x);
                        case "COSH" -> Math.cosh(x);
                        case "TANH" -> Math.tanh(x);
                        case "ASIN" -> Math.asin(x);
                        default -> Math.acos(x);
                    };
                    yield new BigDecimal(r, java.math.MathContext.DECIMAL64);
                }
                // 明示した精度で計算する (LRM "ADD", "MULTIPLY", "DIVIDE")
                case "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE" -> {
                    BigDecimal x = number(arguments.get(0));
                    BigDecimal y = number(arguments.get(1));
                    int p = number(arguments.get(2)).intValue();
                    int q = arguments.size() > 3 ? number(arguments.get(3)).intValue() : 0;
                    BigDecimal result = switch (name) {
                        case "ADD" -> x.add(y);
                        case "SUBTRACT" -> x.subtract(y);
                        case "MULTIPLY" -> x.multiply(y);
                        default -> x.divide(y, q, java.math.RoundingMode.DOWN);
                    };
                    yield FixedValue.decimal(result.setScale(q, java.math.RoundingMode.DOWN),
                            p, q);
                }
                case "BIN", "BINARY", "DEC", "DECIMAL", "FIXED" -> {
                    BigDecimal x = number(arguments.get(0));
                    boolean binary = name.startsWith("BIN");
                    int p = arguments.size() > 1 ? number(arguments.get(1)).intValue()
                            : binary ? 31 : 15;
                    int q = arguments.size() > 2 ? number(arguments.get(2)).intValue() : 0;
                    BigDecimal value = x.setScale(Math.max(q, 0), java.math.RoundingMode.DOWN);
                    yield binary ? FixedValue.binary(value, p, q) : FixedValue.decimal(value, p, q);
                }
                // 戻りコード (PLIRETC で決めた値) を返す (LRM "PLIRETV")
                case "PLIRETV" -> FixedValue.binary(BigDecimal.valueOf(context.returnCode()), 31, 0);
                case "ONCODE" -> FixedValue.binary(BigDecimal.valueOf(onCode), 31, 0);
                case "ONSOURCE" -> onSource == null ? "" : onSource;
                // 変換できなかった最初の字 (LRM "ONCHAR")
                case "ONCHAR" -> {
                    String source = repairedSource != null ? repairedSource : onSource;
                    int at = source == null ? -1 : badCharacter(source);
                    yield at < 0 ? " " : String.valueOf(source.charAt(at));
                }
                // NULL は X'FF000000'、SYSNULL は 0 である (Enterprise PL/I Programming Guide
                // "NULL and SYSNULL")。以前はどちらも 0 にしていた
                case "SYSNULL" -> new PointerValue(0);
                case "NULL" -> new PointerValue(NULL_ADDRESS);
                case "NULLENTRY" -> {
                    if (!arguments.isEmpty()) {
                        throw new PliExecutionException("NULLENTRY takes no arguments");
                    }
                    yield new EntryValue(null);
                }
                case "ENTRYADDR" -> {
                    if (arguments.size() != 1 || !(arguments.getFirst() instanceof EntryValue entry)) {
                        throw new PliExecutionException("ENTRYADDR requires an entry value");
                    }
                    yield new PointerValue(entry.name() == null ? 0
                            : AddressSpace.of(context).entryOf(entry.name()));
                }
                case "POINTERVALUE", "PTRVALUE" ->
                        new PointerValue(number(arguments.getFirst()).intValue());
                case "POINTERADD", "PTRADD", "POINTERSUBTRACT", "PTRSUBTRACT" -> {
                    if (!(arguments.get(0) instanceof PointerValue pointer)) {
                        throw new PliExecutionException(name + " needs a POINTER");
                    }
                    // POINTER の値は位置ごとに振った番号であり、番地の算術は効かない (P-150)。
                    // 番号が指す記憶域の中で位置を進め、その位置の番号を求める
                    AddressSpace.Location location = AddressSpace.of(context)
                            .locate(pointer.address());
                    boolean subtract = name.endsWith("SUBTRACT");
                    long displacement = number(arguments.get(1)).longValue();
                    if (subtract) displacement = -displacement;
                    if (location == null && subtract) {
                        yield new PointerValue((int) (pointer.address() + displacement));
                    }
                    long offset = location == null ? -1 : location.offset() + displacement;
                    if (location == null || offset < 0 || offset > location.storage().size()) {
                        throw new PliExecutionException(name + " leaves the storage the POINTER"
                                + " points into; addresses outside it are not modelled (P-150)");
                    }
                    yield new PointerValue(AddressSpace.of(context)
                            .addressOf(location.storage(), (int) offset));
                }
                case "OFFSETVALUE" -> fixedBinary31(number(arguments.getFirst()).intValue());
                case "OFFSETADD" -> fixedBinary31(number(arguments.getFirst()).intValue()
                        + number(arguments.get(1)).intValue());
                case "OFFSETSUBTRACT" -> fixedBinary31(number(arguments.getFirst()).intValue()
                        - number(arguments.get(1)).intValue());
                case "OFFSETDIFF" -> FixedValue.binary(
                        number(arguments.getFirst()).subtract(number(arguments.get(1))), 31, 0);
                case "CS", "CDS" -> compareAndSwap(name, arguments);
                case "EXPONENT" -> FixedValue.binary(
                        BigDecimal.valueOf(hexExponent(number(arguments.getFirst()))), 31, 0);
                case "SCALE" -> number(arguments.getFirst())
                        .multiply(Var.power16(number(arguments.get(1)).intValueExact()));
                case "DAYSTOMICROSECS", "MICROSECSTODAYS", "MICROSECS", "MICROSECSTODATE", "SECS",
                     "MAXDATE", "MINDATE", "STCKTODATE", "STCKETODATE", "JULIANTOSMF", "SMFTOJULIAN",
                     "TIMESTAMP", "UTCDATETIME", "UTCSECS", "UTCMICROSECS" -> dateBuiltin(name, arguments);
                case "SECSTODATE" -> secondsToDate(number(arguments.getFirst()),
                        arguments.size() > 1 ? display(arguments.get(1)) : "YYYYMMDDHHMISS999");
                // DAYS(日付, 型) は 1582 年 10 月 15 日を 1 とする日の番号 (Lilian) である
                case "DAYS", "DAYSTODATE" -> dateBuiltin(name, arguments);
                case "BOOL" -> {
                    String x = bitText(arguments.get(0));
                    String y = bitText(arguments.get(1));
                    String rule = bitText(arguments.get(2));
                    int length = Math.max(x.length(), y.length());
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < length; i++) {
                        int a = i < x.length() ? x.charAt(i) - '0' : 0;
                        int b = i < y.length() ? y.charAt(i) - '0' : 0;
                        out.append(rule.charAt(a * 2 + b));
                    }
                    yield new PliSyntax.BitString(out.toString());
                }
                default -> extraBuiltins(name, arguments);
            };
        }

        /**
         * 組み込み関数のさらに続き (ZPVS の PM109A 以降で使うもの)。意味は Enterprise PL/I
         * Language Reference の各頁に従い、Zeus の {@code vm/bif.rs} を見て並べた。
         * 実機とは突き合わせていない。
         */
        private Object extraBuiltins(String name, List<Object> arguments) {
            return switch (name) {
                // 整数の操作は FIXED BIN(31) の 32 ビットで行う (LRM "IAND", "ISLL")
                case "IAND", "IOR", "IEOR" -> {
                    long acc = number(arguments.get(0)).longValue();
                    for (Object next : arguments.subList(1, arguments.size())) {
                        long x = number(next).longValue();
                        acc = name.equals("IAND") ? acc & x : name.equals("IOR") ? acc | x : acc ^ x;
                    }
                    yield fixedBinary31((int) acc);
                }
                case "INOT" -> fixedBinary31(~number(arguments.get(0)).intValue());
                case "ISLL", "ISRL" -> {
                    int x = number(arguments.get(0)).intValue();
                    int n = Math.max(0, Math.min(32, number(arguments.get(1)).intValue()));
                    int r = n >= 32 ? 0 : name.equals("ISLL") ? x << n : x >>> n;
                    yield fixedBinary31(r);
                }
                case "RAISE2" -> {
                    int n = number(arguments.get(0)).intValue();
                    yield FixedValue.binary(BigDecimal.valueOf(n < 0 || n > 62 ? 0 : 1L << n), 63, 0);
                }
                // 位置 n (省けば末尾) から先頭へ向かって探す (LRM "SEARCHR", "VERIFYR")
                case "SEARCHR", "VERIFYR" -> {
                    String text = display(arguments.get(0));
                    String set = display(arguments.get(1));
                    int start = arguments.size() > 2 ? number(arguments.get(2)).intValue() : text.length();
                    int at = 0;
                    for (int i = Math.min(start, text.length()) - 1; i >= 0; i--) {
                        boolean member = set.indexOf(text.charAt(i)) >= 0;
                        if (member == name.equals("SEARCHR")) {
                            at = i + 1;
                            break;
                        }
                    }
                    yield fixedBinary31(at);
                }
                // CHARVAL と BYTE はコードページの 1 バイトの字 (LRM "CHARVAL", "BYTE")
                case "CHARVAL", "BYTE" -> context.codePage().decode(
                        new byte[] {(byte) Math.max(0, Math.min(255, number(arguments.get(0)).intValue()))});
                case "VALIDDATE" -> new PliSyntax.BitString(validDate(display(arguments.get(0)),
                        arguments.size() > 1 ? display(arguments.get(1)) : "YYYYMMDD") ? "1" : "0");
                case "DAYSTOSECS" -> number(arguments.get(0)).multiply(BigDecimal.valueOf(86_400));
                case "SECSTODAYS" -> fixedBinary31(number(arguments.get(0))
                        .divide(BigDecimal.valueOf(86_400), 0, java.math.RoundingMode.FLOOR).intValue());
                // 曜日。日曜が 1 である (LRM "WEEKDAY")。引数は Lilian の日の番号
                case "WEEKDAY" -> {
                    long day = arguments.isEmpty()
                            ? ZonedDateTime.now(context.clock()).toLocalDate().toEpochDay() - LILIAN_ORIGIN
                            : number(arguments.get(0)).longValue();
                    java.time.DayOfWeek week = java.time.LocalDate.ofEpochDay(day + LILIAN_ORIGIN)
                            .getDayOfWeek();
                    yield fixedBinary31(week.getValue() % 7 + 1);
                }
                // 型を変える関数 (LRM "FIXEDBIN", "FIXEDDEC", "FLOATBIN", "FLOATDEC")。精度を
                // 省けば値はそのまま、精度だけを書けば小数部は切り捨てる
                case "FIXEDBIN", "FIXEDDEC" -> {
                    BigDecimal x = number(arguments.get(0));
                    boolean binary = name.equals("FIXEDBIN");
                    if (arguments.size() == 1) {
                        yield binary ? FixedValue.binary(x, 31, Math.max(0, x.scale()))
                                : FixedValue.decimal(x, 15, Math.max(0, x.scale()));
                    }
                    int p = number(arguments.get(1)).intValue();
                    int q = arguments.size() > 2 ? number(arguments.get(2)).intValue() : 0;
                    BigDecimal value = x.setScale(Math.max(q, 0), java.math.RoundingMode.DOWN);
                    yield binary ? FixedValue.binary(value, p, q) : FixedValue.decimal(value, p, q);
                }
                case "FLOATBIN", "FLOATDEC" -> number(arguments.get(0));
                case "SIGNED", "UNSIGNED" -> FixedValue.binary(number(arguments.get(0)).setScale(0,
                        java.math.RoundingMode.DOWN), arguments.size() > 1
                                ? number(arguments.get(1)).intValue() : 31, 0);
                // 割り切れないときは余りの 1 桁を左に置く (LRM "CENTERRIGHT")
                case "CENTERRIGHT", "CENTRERIGHT" -> {
                    String text = display(arguments.get(0));
                    int width = number(arguments.get(1)).intValue();
                    String pad = arguments.size() > 2 ? display(arguments.get(2)) : " ";
                    if (text.length() >= width) yield text.substring(0, width);
                    int right = (width - text.length()) / 2;
                    yield pad.repeat(width - text.length() - right) + text + pad.repeat(right);
                }
                case "HEXIMAGE8" -> new PliSyntax.HexString(java.util.HexFormat.of().withUpperCase()
                        .formatHex(memoryRead(arguments.get(0), number(arguments.get(1)).intValue()))
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                case "WHIGH", "WLOW" -> new PliSyntax.WideString(String.valueOf(
                        name.equals("WHIGH") ? '\uFFFF' : '\u0000')
                        .repeat(Math.max(0, number(arguments.get(0)).intValue())));
                // SUBTO(x, i, j): i から j まで。j を省けば末尾まで (LRM "SUBTO")
                case "SUBTO" -> {
                    String text = display(arguments.get(0));
                    int from = number(arguments.get(1)).intValue();
                    int to = arguments.size() > 2 ? number(arguments.get(2)).intValue() : text.length();
                    if (from < 1 || from > text.length() + 1 || to > text.length()) {
                        throw new Condition("STRINGRANGE", null, 350, null);
                    }
                    yield to < from ? "" : text.substring(from - 1, to);
                }
                case "GETENV" -> {
                    String key = display(arguments.get(0)).strip();
                    String set = ENVIRONMENT.get(key);
                    yield set != null ? set : java.util.Objects.requireNonNullElse(System.getenv(key), "");
                }
                // 実行単位の環境変数を書く。JVM の環境変数は変えられないので、この処理系の表に置く
                case "PUTENV" -> {
                    String entry = display(arguments.get(0));
                    int equals = entry.indexOf('=');
                    if (equals <= 0) yield false;
                    ENVIRONMENT.put(entry.substring(0, equals).strip(), entry.substring(equals + 1));
                    yield true;
                }
                case "UUID4" -> java.util.UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
                case "ONPROCEDURE", "ONLOC" -> onProcedureName == null ? "" : onProcedureName;
                case "ONFILE" -> onConditionQualifier == null || onConditionName == null
                        || onConditionName.equals("CONDITION") ? "" : onConditionQualifier;
                case "BETWEENEXCLUSIVE", "BETWEENLEFTEXCLUSIVE", "BETWEENRIGHTEXCLUSIVE" -> {
                    int low = compare(arguments.get(0), arguments.get(1));
                    int high = compare(arguments.get(0), arguments.get(2));
                    boolean left = name.equals("BETWEENRIGHTEXCLUSIVE") ? low >= 0 : low > 0;
                    boolean right = name.equals("BETWEENLEFTEXCLUSIVE") ? high <= 0 : high < 0;
                    yield left && right;
                }
                case "INLIST" -> {
                    boolean found = false;
                    for (Object candidate : arguments.subList(1, arguments.size())) {
                        found |= compare(arguments.get(0), candidate) == 0;
                    }
                    yield found;
                }
                case "ICLZ" -> fixedBinary31(Integer.numberOfLeadingZeros(number(arguments.get(0)).intValue()));
                case "ICTZ" -> fixedBinary31(Integer.numberOfTrailingZeros(number(arguments.get(0)).intValue()));
                case "IRLL" -> fixedBinary31(Integer.rotateLeft(number(arguments.get(0)).intValue(),
                        number(arguments.get(1)).intValue()));
                case "IRRL" -> fixedBinary31(Integer.rotateRight(number(arguments.get(0)).intValue(),
                        number(arguments.get(1)).intValue()));
                // 算術右シフト (LRM "LOWER2")。負の数は -∞ の向きに丸まる
                case "LOWER2" -> fixedBinary31(number(arguments.get(0)).intValue()
                        >> Math.max(0, Math.min(31, number(arguments.get(1)).intValue())));
                // バイトごとの 1 のビットの数 (LRM "POPCNT")
                case "POPCNT" -> {
                    int x = number(arguments.get(0)).intValue();
                    int r = 0;
                    for (int k = 0; k < 4; k++) {
                        r |= Integer.bitCount((x >>> (8 * k)) & 0xFF) << (8 * k);
                    }
                    yield fixedBinary31(r);
                }
                // 32 ビットの記憶域を符号つき / 符号なしで読み直す (LRM "ISIGNED", "IUNSIGNED")
                case "ISIGNED" -> fixedBinary31((int) number(arguments.get(0)).longValue());
                case "IUNSIGNED" -> FixedValue.binary(BigDecimal.valueOf(
                        number(arguments.get(0)).intValue() & 0xFFFFFFFFL), 32, 0);
                case "ROUNDAWAYFROMZERO", "ROUNDTOEVEN" -> number(arguments.get(0)).setScale(
                        arguments.size() > 1 ? number(arguments.get(1)).intValue() : 0,
                        name.equals("ROUNDTOEVEN") ? java.math.RoundingMode.HALF_EVEN
                                : java.math.RoundingMode.HALF_UP);
                // 2 桁の年に世紀を補う。窓 w からの 100 年に入る年にする (LRM "Y4DATE")
                case "Y4DATE", "Y4JULIAN", "Y4YEAR" -> {
                    String text = display(arguments.get(0));
                    int window = arguments.size() > 1 ? number(arguments.get(1)).intValue()
                            : java.time.Year.now(context.clock()).getValue() - 50;
                    int yy = Integer.parseInt(text.substring(0, 2));
                    int year = window - window % 100 + yy;
                    if (year < window) year += 100;
                    yield String.format("%04d", year) + text.substring(2);
                }
                // x の字が z の k 番目なら、y の 2k-1 と 2k の 2 字に置き換える (LRM "REPLACEBY2")
                case "REPLACEBY2" -> {
                    String text = display(arguments.get(0));
                    String to = display(arguments.get(1));
                    String from = display(arguments.get(2));
                    StringBuilder out = new StringBuilder();
                    for (char c : text.toCharArray()) {
                        int k = from.indexOf(c);
                        out.append(k < 0 ? String.valueOf(c) : to.substring(2 * k, 2 * k + 2));
                    }
                    yield out.toString();
                }
                // 記憶域の 16 進の像。区切りの字は 4 バイトごとに入れる (LRM "HEXIMAGE")
                case "HEXIMAGE" -> {
                    byte[] bytes = memoryRead(arguments.get(0), number(arguments.get(1)).intValue());
                    String separator = arguments.size() > 2 ? display(arguments.get(2)) : "";
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < bytes.length; i++) {
                        if (i > 0 && i % 4 == 0) out.append(separator);
                        out.append(String.format("%02X", bytes[i] & 0xFF));
                    }
                    yield out.toString();
                }
                case "COMPARE" -> {
                    int n = number(arguments.get(2)).intValue();
                    yield fixedBinary31(Integer.signum(java.util.Arrays.compareUnsigned(
                            memoryRead(arguments.get(0), n), memoryRead(arguments.get(1), n))));
                }
                // 最初に違うバイトの位置 (1 から)。同じなら 0 (LRM "WHEREDIFF")
                case "WHEREDIFF" -> {
                    int n = number(arguments.get(2)).intValue();
                    int at = java.util.Arrays.mismatch(memoryRead(arguments.get(0), n),
                            memoryRead(arguments.get(1), n));
                    yield fixedBinary31(at + 1);
                }
                case "ERF", "ERFC", "GAMMA", "LOGGAMMA" -> {
                    double x = number(arguments.get(0)).doubleValue();
                    double r = switch (name) {
                        case "ERF" -> erf(x);
                        case "ERFC" -> 1 - erf(x);
                        case "GAMMA" -> Math.exp(logGamma(x)) * (x < 0 && Math.floor(-x) % 2 == 0 ? -1 : 1);
                        default -> logGamma(x);
                    };
                    yield new BigDecimal(r, java.math.MathContext.DECIMAL64);
                }
                case "ONCONDID" -> fixedBinary31(onConditionName == null ? 0 : conditionId(onConditionName));
                case "ONCONDCOND" -> onConditionName == null || !onConditionName.equals("CONDITION")
                        ? "" : onConditionQualifier;
                // 文字列の関数 (LRM "COLLAPSE", "SQUEEZE")
                case "COLLAPSE", "SQUEEZE" -> {
                    String text = display(arguments.get(0));
                    String set = arguments.size() > 1 ? display(arguments.get(1)) : " ";
                    String squeezed = squeeze(text, set);
                    yield name.equals("COLLAPSE") ? strip(squeezed, set) : squeezed;
                }
                case "INDEXR" -> {
                    String text = display(arguments.get(0));
                    String part = display(arguments.get(1));
                    int from = arguments.size() > 2 ? number(arguments.get(2)).intValue() : text.length();
                    yield fixedBinary31(part.isEmpty() ? 0 : text.lastIndexOf(part, from - 1) + 1);
                }
                // REPLACE(x, y, z[, i[, n]]): i から、最大 n 回 (省けば 1、0 なら全部) (LRM "REPLACE")
                case "REPLACE" -> {
                    String text = display(arguments.get(0));
                    String from = display(arguments.get(1));
                    String to = display(arguments.get(2));
                    int start = arguments.size() > 3 ? number(arguments.get(3)).intValue() - 1 : 0;
                    int limit = arguments.size() > 4 ? number(arguments.get(4)).intValue() : 1;
                    StringBuilder out = new StringBuilder(text.substring(0, Math.min(start, text.length())));
                    int at = start;
                    int done = 0;
                    while (at < text.length()) {
                        if (!from.isEmpty() && (limit == 0 || done < limit) && text.startsWith(from, at)) {
                            out.append(to);
                            at += from.length();
                            done++;
                        } else {
                            out.append(text.charAt(at++));
                        }
                    }
                    yield out.toString();
                }
                // y の字を落とす。n より前の字は残す (LRM "SCRUBOUT")
                case "SCRUBOUT" -> {
                    String text = display(arguments.get(0));
                    String set = display(arguments.get(1));
                    int from = arguments.size() > 2 ? number(arguments.get(2)).intValue() - 1 : 0;
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < text.length(); i++) {
                        if (i < from || set.indexOf(text.charAt(i)) < 0) out.append(text.charAt(i));
                    }
                    yield out.toString();
                }
                case "UNHEX" -> {
                    String text = display(arguments.get(0));
                    if (arguments.size() > 1) {
                        for (char c : display(arguments.get(1)).toCharArray()) {
                            text = text.replace(String.valueOf(c), "");
                        }
                    }
                    yield new PliSyntax.HexString(java.util.HexFormat.of().parseHex(text));
                }
                case "HEX8" -> new PliSyntax.HexString(java.util.HexFormat.of().withUpperCase()
                        .formatHex(characterBytes(arguments.get(0)))
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                case "MEMINDEX" -> {
                    byte[] buffer = memoryRead(arguments.get(0), number(arguments.get(1)).intValue());
                    byte[] part = characterBytes(arguments.get(2));
                    int at = 0;
                    for (int i = 0; part.length > 0 && i + part.length <= buffer.length; i++) {
                        if (java.util.Arrays.equals(buffer, i, i + part.length, part, 0, part.length)) {
                            at = i + 1;
                            break;
                        }
                    }
                    yield fixedBinary31(at);
                }
                case "MEMSEARCH", "MEMSEARCHR", "MEMVERIFY", "MEMVERIFYR" -> {
                    byte[] buffer = memoryRead(arguments.get(0), number(arguments.get(1)).intValue());
                    byte[] set = characterBytes(arguments.get(2));
                    boolean search = name.startsWith("MEMSEARCH");
                    boolean reverse = name.endsWith("R");
                    int at = 0;
                    for (int k = 0; k < buffer.length; k++) {
                        int i = reverse ? buffer.length - 1 - k : k;
                        boolean member = false;
                        for (byte b : set) member |= b == buffer[i];
                        if (member == search) {
                            at = i + 1;
                            break;
                        }
                    }
                    yield fixedBinary31(at);
                }
                // 書き先と元を番地と長さで受ける関数。書いた長さを返す。書き先の番地が 0 なら
                // 要る長さを、短ければ -1 を返す (LRM "MEMCOLLAPSE" ほか)
                case "MEMCOLLAPSE", "MEMSQUEEZE", "MEMREPLACE", "WSREPLACE", "WSCOLLAPSE",
                     "HEXENCODE", "HEXENCODE8", "HEXDECODE", "HEXDECODE8", "BASE64ENCODE",
                     "BASE64ENCODE8", "BASE64DECODE", "BASE64DECODE8", "WSREPLACE16", "WSCOLLAPSE16",
                     "BASE64ENCODE16", "BASE64DECODE16" -> {
                    byte[] source = memoryRead(arguments.get(2), number(arguments.get(3)).intValue());
                    byte[] result = bufferFunction(name, source, arguments);
                    yield bufferResult(arguments.get(0), number(arguments.get(1)).intValue(), result);
                }
                default -> throw new PliExecutionException("PL/I built-in is not supported: " + name);
            };
        }

        /** PUTENV で置いた環境変数。実行単位をまたいで残る (プロセスの環境と同じ)。 */
        private static final Map<String, String> ENVIRONMENT = new java.util.concurrent.ConcurrentHashMap<>();

        private String currentProcedure() {
            return scope.isEmpty() ? program.name().toUpperCase(Locale.ROOT)
                    : scope.substring(scope.lastIndexOf('/') + 1);
        }

        /** POLY(a, x): a の要素を係数とする x の多項式 (LRM "POLY")。添字の下限によらず先頭から。 */
        private Object polynomial(List<PliSyntax.Expr> arguments, Env env) {
            Var array = dataReference(arguments.get(0), env, "POLY");
            if (array.dimensions == null) {
                throw new PliExecutionException("POLY needs an array: " + array.name);
            }
            BigDecimal x = number(value(arguments.get(1), env));
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal power = BigDecimal.ONE;
            for (Var element : array.elements) {
                sum = sum.add(number(element.read(context)).multiply(power));
                power = power.multiply(x);
            }
            return sum;
        }

        /** VALID(x): 記憶域が型に合う値を持つか (LRM "VALID")。PICTURE と FIXED DEC を見る。 */
        private Object valid(Var variable) {
            try {
                switch (variable.type) {
                    case DECIMAL -> {
                        // 桁は 0〜9、右端の半バイトは符号 (A〜F) でなければならない
                        byte[] packed = variable.view.toByteArray();
                        for (int i = 0; i < packed.length; i++) {
                            int high = (packed[i] >> 4) & 0x0F;
                            int low = packed[i] & 0x0F;
                            boolean last = i == packed.length - 1;
                            if (high > 9 || (last ? low < 10 : low > 9)) {
                                return false;
                            }
                        }
                    }
                    case PICTURE -> {
                        String text = context.codePage().decode(variable.view.toByteArray());
                        new NumericPicture(text, variable.precision, variable.scale,
                                variable.picture).fixed();
                    }
                    default -> throw new PliExecutionException("VALID supports FIXED DECIMAL and"
                            + " numeric PICTURE yet: " + variable.name);
                }
                return true;
            } catch (PliExecutionException unsupported) {
                if (unsupported.getMessage().startsWith("VALID supports")) throw unsupported;
                return false;
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        /** 書き先へ結果を書き、書いた長さを返す。番地が 0 なら要る長さ、短ければ -1。 */
        private Object bufferResult(Object target, int capacity, byte[] result) {
            if (target instanceof PointerValue pointer && pointer.address() == 0) {
                return FixedValue.binary(BigDecimal.valueOf(result.length), 63, 0);
            }
            if (result.length > capacity) {
                return FixedValue.binary(BigDecimal.valueOf(-1), 63, 0);
            }
            memoryWrite(target, result);
            return FixedValue.binary(BigDecimal.valueOf(result.length), 63, 0);
        }

        private byte[] bufferFunction(String name, byte[] source, List<Object> arguments) {
            var page = context.codePage();
            java.nio.charset.Charset ascii = java.nio.charset.StandardCharsets.US_ASCII;
            return switch (name) {
                case "MEMCOLLAPSE", "MEMSQUEEZE" -> {
                    String set = display(arguments.get(4));
                    String squeezed = squeeze(page.decode(source), set);
                    yield page.encode(name.equals("MEMCOLLAPSE") ? strip(squeezed, set) : squeezed);
                }
                case "MEMREPLACE" -> {
                    byte[] from = memoryRead(arguments.get(4), number(arguments.get(5)).intValue());
                    byte[] to = memoryRead(arguments.get(6), number(arguments.get(7)).intValue());
                    int start = arguments.size() > 8 ? number(arguments.get(8)).intValue() - 1 : 0;
                    int limit = arguments.size() > 9 ? number(arguments.get(9)).intValue() : 1;
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    out.write(source, 0, Math.min(start, source.length));
                    int done = 0;
                    for (int at = start; at < source.length; ) {
                        if (from.length > 0 && (limit == 0 || done < limit)
                                && at + from.length <= source.length
                                && java.util.Arrays.equals(source, at, at + from.length, from, 0, from.length)) {
                            out.writeBytes(to);
                            at += from.length;
                            done++;
                        } else {
                            out.write(source[at++]);
                        }
                    }
                    yield out.toByteArray();
                }
                // UTF-16 の空白類 (水平タブ・改行・垂直タブ・書式送り・復帰・NEL) を空白にする
                case "WSREPLACE16", "WSCOLLAPSE16" -> {
                    String text = new String(source, java.nio.charset.StandardCharsets.UTF_16BE)
                            .replaceAll("[\\t\\n\\u000B\\f\\r\\u0085]", " ");
                    if (name.equals("WSCOLLAPSE16")) text = strip(squeeze(text, " "), " ");
                    yield text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
                }
                case "BASE64ENCODE16" -> java.util.Base64.getEncoder().encodeToString(source)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
                case "BASE64DECODE16" -> java.util.Base64.getDecoder().decode(
                        new String(source, java.nio.charset.StandardCharsets.UTF_16BE));
                case "WSREPLACE", "WSCOLLAPSE" -> {
                    // 空白類 (水平タブ・改行・復帰・書式送り・垂直タブ・NL) を空白にする
                    byte[] replaced = source.clone();
                    byte[] whitespace = {0x05, 0x0B, 0x0C, 0x0D, 0x15, 0x25};
                    for (int i = 0; i < replaced.length; i++) {
                        for (byte w : whitespace) {
                            if (replaced[i] == w) replaced[i] = page.space();
                        }
                    }
                    yield name.equals("WSREPLACE") ? replaced
                            : page.encode(strip(squeeze(page.decode(replaced), " "), " "));
                }
                case "HEXENCODE", "HEXENCODE8" -> {
                    String hex = java.util.HexFormat.of().withUpperCase().formatHex(source);
                    yield name.endsWith("8") ? hex.getBytes(ascii) : page.encode(hex);
                }
                case "HEXDECODE", "HEXDECODE8" -> java.util.HexFormat.of().parseHex(
                        name.endsWith("8") ? new String(source, ascii) : page.decode(source));
                case "BASE64ENCODE", "BASE64ENCODE8" -> {
                    String text = java.util.Base64.getEncoder().encodeToString(source);
                    yield name.endsWith("8") ? text.getBytes(ascii) : page.encode(text);
                }
                default -> java.util.Base64.getDecoder().decode(
                        name.endsWith("8") ? new String(source, ascii) : page.decode(source));
            };
        }

        /** set の字の並びを 1 字にまとめる。 */
        private static String squeeze(String text, String set) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (set.indexOf(c) >= 0 && i > 0 && set.indexOf(text.charAt(i - 1)) >= 0) continue;
                out.append(c);
            }
            return out.toString();
        }

        /** 両端の set の字を落とす。 */
        private static String strip(String text, String set) {
            int from = 0;
            int to = text.length();
            while (from < to && set.indexOf(text.charAt(from)) >= 0) from++;
            while (to > from && set.indexOf(text.charAt(to - 1)) >= 0) to--;
            return text.substring(from, to);
        }

        /** 文字の値のバイト。'..'X ならそのバイト、そうでなければコードページの字。 */
        private byte[] characterBytes(Object value) {
            if (value instanceof PliSyntax.HexString hex) return hex.bytes();
            if (value instanceof MixedCharacters mixed) return mixed.bytes();
            return context.codePage().encode(display(value));
        }

        /** POINTER が指す記憶域の n バイト。 */
        private byte[] memoryRead(Object pointer, int length) {
            AddressSpace.Location at = locatePointer(pointer, length);
            return java.util.Arrays.copyOfRange(at.storage().array(), at.offset(), at.offset() + length);
        }

        private void memoryWrite(Object pointer, byte[] bytes) {
            AddressSpace.Location at = locatePointer(pointer, bytes.length);
            System.arraycopy(bytes, 0, at.storage().array(), at.offset(), bytes.length);
        }

        private AddressSpace.Location locatePointer(Object pointer, int length) {
            if (!(pointer instanceof PointerValue p)) {
                throw new PliExecutionException("a POINTER is required: " + pointer);
            }
            AddressSpace.Location at = AddressSpace.of(context).locate(p.address());
            if (at == null || length < 0 || at.offset() + length > at.storage().size()) {
                throw new PliExecutionException("the POINTER does not address " + length
                        + " bytes of storage; addresses outside a storage are not modelled (P-150)");
            }
            return at;
        }

        /** 誤差関数。級数で求める (|x| が小さいとき) か、1 - 連分数の erfc で求める。 */
        private static double erf(double x) {
            double a = Math.abs(x);
            double result;
            if (a < 3) {
                double term = a;
                double sum = a;
                for (int n = 1; n < 200 && Math.abs(term) > 1e-20; n++) {
                    term *= -a * a / n;
                    sum += term / (2 * n + 1);
                }
                result = 2 / Math.sqrt(Math.PI) * sum;
            } else {
                // Lentz の連分数で erfc を求める
                double f = a;
                double c = a;
                double d = 0;
                for (int k = 1; k < 200; k++) {
                    double an = k / 2.0;
                    d = a + an * d;
                    d = d == 0 ? 1e-300 : d;
                    c = a + an / c;
                    c = c == 0 ? 1e-300 : c;
                    d = 1 / d;
                    double delta = c * d;
                    f *= delta;
                    if (Math.abs(delta - 1) < 1e-16) break;
                }
                result = 1 - Math.exp(-a * a) / Math.sqrt(Math.PI) / f;
            }
            return x < 0 ? -result : result;
        }

        /** ガンマ関数の絶対値の対数 (Lanczos の近似、g = 7)。 */
        private static double logGamma(double x) {
            if (x < 0.5) {
                return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
            }
            double[] g = {0.99999999999980993, 676.5203681218851, -1259.1392167224028,
                    771.32342877765313, -176.61502916214059, 12.507343278686905,
                    -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
            double y = x - 1;
            double a = g[0];
            double t = y + 7.5;
            for (int i = 1; i < 9; i++) a += g[i] / (y + i);
            return 0.5 * Math.log(2 * Math.PI) + (y + 0.5) * Math.log(t) - t + Math.log(a);
        }

        /** ONCONDID の値 (LRM "ONCONDID")。番号は Zeus と同じにした。 */
        private static int conditionId(String condition) {
            return switch (condition) {
                case "AREA" -> 1;
                case "ATTENTION" -> 2;
                case "CONDITION" -> 3;
                case "CONVERSION" -> 4;
                case "ENDFILE" -> 5;
                case "ENDPAGE" -> 6;
                case "ERROR" -> 7;
                case "FINISH" -> 8;
                case "FIXEDOVERFLOW" -> 9;
                case "INVALIDOP" -> 10;
                case "KEY" -> 11;
                case "NAME" -> 12;
                case "OVERFLOW" -> 13;
                case "RECORD" -> 14;
                case "SIZE" -> 15;
                case "STORAGE" -> 16;
                case "STRINGRANGE" -> 17;
                case "STRINGSIZE" -> 18;
                case "SUBSCRIPTRANGE" -> 19;
                case "TRANSMIT" -> 20;
                case "UNDEFINEDFILE" -> 21;
                case "UNDERFLOW" -> 22;
                case "ZERODIVIDE" -> 23;
                case "ASSERTION" -> 24;
                default -> 0;
            };
        }

        /**
         * 変数の属性から決まる値 (LRM "MAXVAL", "MINVAL", "PLACES", "PRECVAL", "SCALEVAL",
         * "SUCC", "PRED", "LOCATION")。
         */
        private Object attributeFunction(String name, List<PliSyntax.Expr> arguments, Env env) {
            Var variable = dataReference(arguments.get(0), env, name);
            boolean floating = variable.type == PliSyntax.Type.FLOAT;
            int digits = !floating ? 0 : variable.scale == 1
                    ? (variable.precision <= 21 ? 6 : variable.precision <= 53 ? 14 : 28)
                    : (variable.precision <= 6 ? 6 : variable.precision <= 16 ? 14 : 28);
            BigDecimal sixteen = BigDecimal.valueOf(16);
            return switch (name) {
                case "MAXVAL", "MINVAL" -> {
                    boolean max = name.equals("MAXVAL");
                    if (variable.type == PliSyntax.Type.BINARY) {
                        BigDecimal two = BigDecimal.valueOf(2);
                        int p = variable.precision;
                        yield FixedValue.binary(variable.unsigned
                                ? (max ? two.pow(p).subtract(BigDecimal.ONE) : BigDecimal.ZERO)
                                : (max ? two.pow(p).subtract(BigDecimal.ONE) : two.pow(p).negate()),
                                63, 0);
                    }
                    if (variable.type == PliSyntax.Type.DECIMAL) {
                        BigDecimal limit = BigDecimal.TEN.pow(variable.precision).subtract(BigDecimal.ONE)
                                .movePointLeft(variable.scale);
                        yield FixedValue.decimal(max ? limit : limit.negate(), variable.precision,
                                variable.scale);
                    }
                    throw new PliExecutionException(name + " needs a FIXED variable: " + variable.name);
                }
                case "PLACES" -> fixedBinary31(digits);
                case "PRECVAL" -> fixedBinary31(variable.precision);
                case "SCALEVAL" -> fixedBinary31(floating ? 0 : variable.scale);
                case "SUCC", "PRED" -> {
                    BigDecimal x = number(variable.read(context));
                    if (!floating || x.signum() == 0) {
                        throw new PliExecutionException(name + " of zero or a non-FLOAT value is not"
                                + " supported yet: " + variable.name);
                    }
                    BigDecimal a = x.abs();
                    int e = 0;
                    while (a.compareTo(Var.power16(e)) >= 0) e++;
                    while (a.compareTo(Var.power16(e - 1)) < 0) e--;
                    boolean away = name.equals("SUCC") == (x.signum() > 0);
                    BigDecimal ulp = Var.power16(e - digits);
                    if (!away && a.compareTo(Var.power16(e - 1)) == 0) {
                        ulp = Var.power16(e - 1 - digits);
                    }
                    BigDecimal result = away ? a.add(ulp) : a.subtract(ulp);
                    yield x.signum() < 0 ? result.negate() : result;
                }
                default -> fixedBinary31(variable.view.offset());
            };
        }

        /** 日付がその型で正しいか。YYYY・YY・MM・DD・DDD を読む。 */
        private static boolean validDate(String text, String pattern) {
            if (text.length() != pattern.length()) {
                return false;
            }
            try {
                int y = pattern.indexOf("YYYY");
                int year = y >= 0 ? Integer.parseInt(text.substring(y, y + 4)) : 2000;
                int ddd = pattern.indexOf("DDD");
                if (ddd >= 0) {
                    int day = Integer.parseInt(text.substring(ddd, ddd + 3));
                    return day >= 1 && day <= java.time.Year.of(year).length();
                }
                int m = pattern.indexOf("MM");
                int d = pattern.indexOf("DD");
                java.time.LocalDate.of(year, m >= 0 ? Integer.parseInt(text.substring(m, m + 2)) : 1,
                        d >= 0 ? Integer.parseInt(text.substring(d, d + 2)) : 1);
                return true;
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        /** PROD・ALL・ANY。配列の要素の積、ビット列の論理積・論理和 (LRM "PROD", "ALL", "ANY")。 */
        private Object arrayReduction(String name, List<PliSyntax.Expr> arguments, Env env) {
            Var array = dataReference(arguments.get(0), env, name);
            if (array.dimensions == null) {
                throw new PliExecutionException(name + " needs an array: " + array.name);
            }
            if (name.equals("PROD")) {
                Object product = null;
                for (Var element : array.elements) {
                    Object value = element.read(context);
                    product = product == null ? value : arithmetic("*", product, value,
                            new FixedValue.Arithmetic(options, false, false));
                }
                return product;
            }
            String result = null;
            for (Var element : array.elements) {
                String bits = bitText(element.read(context));
                if (result == null) {
                    result = bits;
                    continue;
                }
                boolean all = name.equals("ALL");
                result = bitText(bitwise(result, bits, (x, y) -> all
                        ? (x == '1' && y == '1' ? '1' : '0')
                        : (x == '1' || y == '1' ? '1' : '0')));
            }
            return new PliSyntax.BitString(result == null ? "" : result);
        }

        /**
         * 16 進浮動小数点の模型 (LRM "HUGE", "TINY", "EPSILON", "MAXEXP", "MINEXP", "RADIX")。
         * 基数 16、指数 -64〜63、小数部は短形式 6 桁・長形式 14 桁・拡張 28 桁の 16 進である。
         */
        private Object floatModel(String name, List<PliSyntax.Expr> arguments, Env env) {
            int digits = 14;
            if (!arguments.isEmpty() && arguments.get(0) instanceof PliSyntax.Reference reference
                    && env.contains(reference.name())) {
                Var variable = env.require(reference.name());
                if (variable.type == PliSyntax.Type.FLOAT) {
                    boolean binary = variable.scale == 1;
                    int p = variable.precision;
                    digits = binary ? (p <= 21 ? 6 : p <= 53 ? 14 : 28) : (p <= 6 ? 6 : p <= 16 ? 14 : 28);
                }
            }
            BigDecimal sixteen = BigDecimal.valueOf(16);
            return switch (name) {
                case "HUGE" -> BigDecimal.ONE.subtract(BigDecimal.ONE.divide(sixteen.pow(digits)))
                        .multiply(sixteen.pow(63));
                case "TINY" -> BigDecimal.ONE.divide(sixteen.pow(65));
                case "EPSILON" -> BigDecimal.ONE.divide(sixteen.pow(digits - 1));
                case "MAXEXP" -> fixedBinary31(63);
                case "MINEXP" -> fixedBinary31(-64);
                default -> fixedBinary31(16);
            };
        }

        /** POINTERDIFF(p, q): 同じ記憶域の中の 2 つの位置の差 (LRM "POINTERDIFF")。 */
        private Object pointerDifference(List<PliSyntax.Expr> arguments, Env env) {
            Object p = value(arguments.get(0), env);
            Object q = value(arguments.get(1), env);
            if (!(p instanceof PointerValue left) || !(q instanceof PointerValue right)) {
                throw new PliExecutionException("POINTERDIFF needs two pointers");
            }
            AddressSpace.Location a = AddressSpace.of(context).locate(left.address());
            AddressSpace.Location b = AddressSpace.of(context).locate(right.address());
            if (a == null || b == null || a.storage() != b.storage()) {
                throw new PliExecutionException("POINTERDIFF needs two pointers into the same"
                        + " storage; addresses across storages are not modelled (P-150)");
            }
            return fixedBinary31(a.offset() - b.offset());
        }

        private FixedValue codepage() {
            String digits = context.codePage().name().replaceAll("[^0-9]", "");
            int ccsid = options.ccsid() != null ? options.ccsid()
                    : digits.isEmpty() ? 367 : Integer.parseInt(digits);
            return FixedValue.binary(BigDecimal.valueOf(ccsid), 31, 0);
        }

        private Object mixedGraphic(PliSyntax.Function function, Env env) {
            String name = function.name();
            List<PliSyntax.Expr> args = function.arguments();
            boolean graphic = name.equals("GRAPHIC");
            int minimum = graphic ? 1 : 2;
            if (args.size() < minimum || args.size() > minimum + 1) {
                throw new PliExecutionException(name + " received an invalid number of arguments");
            }
            PliSyntax.Expr source = args.getFirst();
            Object value = source instanceof PliSyntax.Literal literal
                    ? literal.value() : value(source, env);
            byte[] bytes = value instanceof PliSyntax.HexString hex ? hex.bytes()
                    : value instanceof MixedCharacters mixed ? mixed.bytes()
                    : context.codePage().encode(display(value));
            if (graphic) {
                Integer length = args.size() == 2 ? nonnegativeLength(value(args.get(1), env)) : null;
                return PliGraphic.fromMixed(bytes, length, context.codePage());
            }
            String options = display(value(args.get(1), env)).toUpperCase(Locale.ROOT);
            int length = args.size() == 3 ? nonnegativeLength(value(args.get(2), env)) : bytes.length;
            return new MixedCharacters(PliGraphic.mixedPrefix(bytes, options.contains("S")
                    && !options.contains("V"), length, context.codePage()), context.codePage());
        }

        private static int nonnegativeLength(Object value) {
            return Math.max(0, number(value).intValueExact());
        }

        private static int characterLength(Object value) {
            return switch (value) {
                case PliSyntax.HexString hex -> hex.bytes().length;
                case PliGraphic graphic -> graphic.bytes().length / 2;
                case MixedCharacters mixed -> mixed.bytes().length;
                default -> display(value).length();
            };
        }

        private FixedValue rank(PliSyntax.Expr expression, Env env) {
            Var variable = null;
            if (expression instanceof PliSyntax.Reference reference) {
                variable = env.find(reference.name());
            } else if (expression instanceof PliSyntax.Function subscript
                    && arrayNamed(subscript.name(), env) != null) {
                variable = dataReference(subscript, env, "RANK");
            }
            if (variable != null && variable.type == PliSyntax.Type.WIDECHAR
                    && variable.dimensions == null) {
                return FixedValue.binary(BigDecimal.valueOf(
                        wideUnit(variable.view.toByteArray(), 0)), 31, 0);
            }
            Object value = variable != null && variable.dimensions == null ? variable.read(context)
                    : expression instanceof PliSyntax.Literal literal ? literal.value() : value(expression, env);
            int first;
            if (value instanceof WideCharacter wide) {
                first = wide.unit();
            } else {
                byte[] bytes = value instanceof PliSyntax.HexString hex
                        ? hex.bytes() : context.codePage().encode(display(value));
                first = bytes.length == 0 ? 0 : bytes[0] & 0xFF;
            }
            return FixedValue.binary(BigDecimal.valueOf(first), 31, 0);
        }

        /** 数の字の並びのうち、数にならない最初の字の位置。無ければ -1。 */
        private static int badCharacter(String text) {
            String stripped = text.strip();
            int offset = text.indexOf(stripped.isEmpty() ? " " : stripped);
            boolean point = false;
            for (int i = 0; i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (Character.isDigit(c) || ((c == '+' || c == '-') && i == 0)) {
                    continue;
                }
                if (c == '.' && !point) {
                    point = true;
                    continue;
                }
                return offset + i;
            }
            return -1;
        }

        /** 1582 年 10 月 14 日。DAYS はこの次の日を 1 とする。 */
        private static final long LILIAN_ORIGIN =
                java.time.LocalDate.of(1582, 10, 14).toEpochDay();

        private static FixedValue fixedBinary31(int value) {
            return FixedValue.binary(BigDecimal.valueOf(value), 31, 0);
        }

        private FixedValue compareAndSwap(String name, List<Object> arguments) {
            if (arguments.size() != 3) {
                throw new PliExecutionException(name + " requires two pointers and a value");
            }
            int width = name.equals("CS") ? 4 : 8;
            DataView first = pointerBuffer(arguments.get(0), width, name);
            DataView second = pointerBuffer(arguments.get(1), width, name);
            synchronized (AddressSpace.of(context)) {
                byte[] original = first.toByteArray();
                if (!java.util.Arrays.equals(original, second.toByteArray())) {
                    second.setBytes(original);
                    return fixedBinary31(1);
                }
                long replacement = number(arguments.get(2)).longValue();
                var bytes = java.nio.ByteBuffer.allocate(width);
                if (width == Integer.BYTES) bytes.putInt((int) replacement);
                else bytes.putLong(replacement);
                first.setBytes(bytes.array());
                return fixedBinary31(0);
            }
        }

        private static int hexExponent(BigDecimal value) {
            if (value.signum() == 0) return 0;
            BigDecimal fraction = value.abs();
            BigDecimal radix = BigDecimal.valueOf(16);
            int exponent = 0;
            while (fraction.compareTo(BigDecimal.ONE) >= 0) {
                exponent++;
                fraction = fraction.divide(radix);
            }
            BigDecimal minimum = new BigDecimal("0.0625");
            while (fraction.compareTo(minimum) < 0) {
                exponent--;
                fraction = fraction.multiply(radix);
            }
            return exponent;
        }

        private static String secondsToDate(BigDecimal seconds, String pattern) {
            long micros = seconds.movePointRight(6).setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
            long wholeSeconds = Math.floorDiv(micros, 1_000_000);
            java.time.LocalDate date = java.time.LocalDate.ofEpochDay(
                    LILIAN_ORIGIN + Math.floorDiv(wholeSeconds, 86_400));
            java.time.LocalTime time = java.time.LocalTime.ofSecondOfDay(
                    Math.floorMod(wholeSeconds, 86_400));
            String fraction = String.format(Locale.ROOT, "%06d", Math.floorMod(micros, 1_000_000));
            var matcher = SECONDS_DATE_TOKENS.matcher(pattern.stripTrailing());
            StringBuilder text = new StringBuilder();
            boolean suppressLeadingZeros = false;
            int year = Math.floorMod(date.getYear(), 100);
            while (matcher.find()) {
                String token = matcher.group();
                suppressLeadingZeros |= token.startsWith("Z");
                String month = date.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
                String value = switch (token) {
                    case "YYYY" -> String.format(Locale.ROOT, "%04d", date.getYear());
                    case "YY" -> String.format(Locale.ROOT, "%02d", year);
                    case "ZY" -> Integer.toString(year);
                    case "MMM" -> month.toUpperCase(Locale.ROOT);
                    case "Mmm" -> month;
                    case "MM" -> String.format(Locale.ROOT, "%02d", date.getMonthValue());
                    case "ZM" -> Integer.toString(date.getMonthValue());
                    case "DDD" -> String.format(Locale.ROOT, "%03d", date.getDayOfYear());
                    case "DD" -> String.format(Locale.ROOT, "%02d", date.getDayOfMonth());
                    case "ZD" -> Integer.toString(date.getDayOfMonth());
                    case "HH" -> String.format(Locale.ROOT, "%02d", time.getHour());
                    case "MI" -> String.format(Locale.ROOT, "%02d", time.getMinute());
                    case "SS" -> String.format(Locale.ROOT, "%02d", time.getSecond());
                    case "999999" -> fraction;
                    case "999" -> fraction.substring(0, 3);
                    default -> token;
                };
                text.append(value);
            }
            if (suppressLeadingZeros && text.length() < 8) text.append(" ".repeat(8 - text.length()));
            return text.toString();
        }


        private static final String TIMESTAMP_PATTERN = "YYYY-MM-DD-HH.MI.SS.999999";
        private static final String SECONDS_PATTERN = "YYYYMMDDHHMISS999";
        private static final long MICROS_PER_DAY = 86_400_000_000L;
        /** 窓を書かなければ 1950 年から 100 年とする (翻訳オプション WINDOW の既定)。 */
        private static final int DEFAULT_WINDOW = 1950;

        private static String pattern(List<Object> arguments, int at, String fallback) {
            return arguments.size() > at ? display(arguments.get(at)).stripTrailing() : fallback;
        }

        private static int window(List<Object> arguments, int at) {
            return arguments.size() > at ? number(arguments.get(at)).intValue() : DEFAULT_WINDOW;
        }

        /**
         * 型 (LRM Table 65) で書いた日時を、Lilian の起点 (1582 年 10 月 14 日 0 時) からの
         * マイクロ秒にする。YY は窓 w からの 100 年に入る年にする。
         */
        private static long lilianMicros(String text, String pattern, int window) {
            var matcher = SECONDS_DATE_TOKENS.matcher(pattern);
            int at = 0;
            int year = 1582;
            int month = 1;
            int day = 1;
            int dayOfYear = -1;
            int hour = 0;
            int minute = 0;
            int second = 0;
            int micros = 0;
            try {
                while (matcher.find()) {
                    String token = matcher.group();
                    int width = switch (token) {
                        case "YYYY" -> 4;
                        case "999999" -> 6;
                        case "MMM", "Mmm", "DDD", "999" -> 3;
                        case "YY", "MM", "DD", "HH", "MI", "SS" -> 2;
                        case "ZY", "ZM", "ZD" -> throw new PliExecutionException(
                                "the pattern element " + token + " cannot be read yet: " + pattern);
                        default -> 1;
                    };
                    String part = text.substring(at, at + width);
                    at += width;
                    switch (token) {
                        case "YYYY" -> year = Integer.parseInt(part);
                        case "YY" -> {
                            year = window - window % 100 + Integer.parseInt(part);
                            if (year < window) year += 100;
                        }
                        case "MMM", "Mmm" -> month = java.time.Month.from(
                                java.time.format.DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
                                        .parse(part.charAt(0) + part.substring(1).toLowerCase(Locale.ROOT)))
                                .getValue();
                        case "MM" -> month = Integer.parseInt(part);
                        case "DDD" -> dayOfYear = Integer.parseInt(part);
                        case "DD" -> day = Integer.parseInt(part);
                        case "HH" -> hour = Integer.parseInt(part);
                        case "MI" -> minute = Integer.parseInt(part);
                        case "SS" -> second = Integer.parseInt(part);
                        case "999999" -> micros = Integer.parseInt(part);
                        case "999" -> micros = Integer.parseInt(part) * 1000;
                        default -> { }
                    }
                }
                java.time.LocalDate date = dayOfYear > 0 ? java.time.LocalDate.ofYearDay(year, dayOfYear)
                        : java.time.LocalDate.of(year, month, day);
                long days = date.toEpochDay() - LILIAN_ORIGIN;
                return days * MICROS_PER_DAY
                        + ((hour * 60L + minute) * 60 + second) * 1_000_000L + micros;
            } catch (PliExecutionException unsupported) {
                throw unsupported;
            } catch (RuntimeException invalid) {
                throw new PliExecutionException("'" + text + "' is not a date of the pattern "
                        + pattern);
            }
        }

        /** Lilian のマイクロ秒を型で書く。 */
        private static String microsToDate(long micros, String pattern) {
            return secondsToDate(BigDecimal.valueOf(micros).movePointLeft(6), pattern);
        }

        private long nowMicros(boolean utc) {
            java.time.LocalDateTime now = utc
                    ? java.time.LocalDateTime.ofInstant(context.clock().instant(), java.time.ZoneOffset.UTC)
                    : java.time.LocalDateTime.now(context.clock());
            return (now.toLocalDate().toEpochDay() - LILIAN_ORIGIN) * MICROS_PER_DAY
                    + now.toLocalTime().toNanoOfDay() / 1000;
        }

        /** STCK の値 (1900 年 1 月 1 日からの 2 の -12 乗マイクロ秒) を Lilian のマイクロ秒にする。 */
        private static long stckToMicros(long tod) {
            return (tod >>> 12) + (java.time.LocalDate.of(1900, 1, 1).toEpochDay() - LILIAN_ORIGIN)
                    * MICROS_PER_DAY;
        }

        private static long microsToStck(long micros) {
            return (micros - (java.time.LocalDate.of(1900, 1, 1).toEpochDay() - LILIAN_ORIGIN)
                    * MICROS_PER_DAY) << 12;
        }

        /** 日付と時刻の関数 (LRM "DAYS", "SECS", "MICROSECS" ほか)。 */
        private Object dateBuiltin(String name, List<Object> arguments) {
            return switch (name) {
                case "DAYS" -> fixedBinary31((int) Math.floorDiv(arguments.isEmpty() ? nowMicros(false)
                        : lilianMicros(display(arguments.get(0)), pattern(arguments, 1, SECONDS_PATTERN),
                                window(arguments, 2)), MICROS_PER_DAY));
                case "DAYSTODATE" -> microsToDate(number(arguments.get(0)).longValue() * MICROS_PER_DAY,
                        pattern(arguments, 1, SECONDS_PATTERN));
                case "SECS" -> BigDecimal.valueOf(arguments.isEmpty() ? nowMicros(false)
                        : lilianMicros(display(arguments.get(0)), pattern(arguments, 1, SECONDS_PATTERN),
                                window(arguments, 2))).movePointLeft(6);
                case "MICROSECS" -> FixedValue.binary(BigDecimal.valueOf(arguments.isEmpty()
                        ? nowMicros(false) : lilianMicros(display(arguments.get(0)),
                                pattern(arguments, 1, TIMESTAMP_PATTERN), window(arguments, 2))), 63, 0);
                case "MICROSECSTODATE" -> microsToDate(number(arguments.get(0)).longValue(),
                        pattern(arguments, 1, TIMESTAMP_PATTERN));
                case "DAYSTOMICROSECS" -> FixedValue.binary(BigDecimal.valueOf(
                        number(arguments.get(0)).longValue() * MICROS_PER_DAY), 63, 0);
                case "MICROSECSTODAYS" -> fixedBinary31((int) Math.floorDiv(
                        number(arguments.get(0)).longValue(), MICROS_PER_DAY));
                // NONULLDATE のいちばん早い日は Lilian の 0 日 (1582 年 10 月 14 日)
                case "MAXDATE" -> microsToDate(lilianMicros("99991231235959999999",
                        "YYYYMMDDHHMISS999999", DEFAULT_WINDOW), pattern(arguments, 0, TIMESTAMP_PATTERN));
                case "MINDATE" -> microsToDate(0, pattern(arguments, 0, TIMESTAMP_PATTERN));
                case "STCKTODATE" -> microsToDate(stckToMicros(number(arguments.get(0)).toBigInteger()
                        .longValue()), pattern(arguments, 1, TIMESTAMP_PATTERN));
                case "STCKETODATE" -> {
                    byte[] bytes = characterBytes(arguments.get(0));
                    long tod = java.nio.ByteBuffer.wrap(bytes, 1, 8).getLong();
                    yield microsToDate(stckToMicros(tod), pattern(arguments, 1, TIMESTAMP_PATTERN));
                }
                // SMF の日付は X'0cyydddF' (c は 20xx なら 1)
                case "JULIANTOSMF" -> {
                    String julian = display(arguments.get(0));
                    int year = Integer.parseInt(julian.substring(0, 4));
                    String digits = String.format("0%d%02d%sF", (year - 1900) / 100, year % 100,
                            julian.substring(4, 7));
                    yield new PliSyntax.HexString(java.util.HexFormat.of().parseHex(digits));
                }
                case "SMFTOJULIAN" -> {
                    String digits = java.util.HexFormat.of().withUpperCase()
                            .formatHex(characterBytes(arguments.get(0)));
                    int year = 1900 + Character.digit(digits.charAt(1), 10) * 100
                            + Integer.parseInt(digits.substring(2, 4));
                    yield String.format("%04d", year) + digits.substring(4, 7);
                }
                case "TIMESTAMP" -> microsToDate(nowMicros(false), TIMESTAMP_PATTERN);
                case "UTCDATETIME" -> microsToDate(nowMicros(true), SECONDS_PATTERN);
                case "UTCSECS" -> BigDecimal.valueOf(nowMicros(true)).movePointLeft(6);
                case "UTCMICROSECS" -> FixedValue.binary(BigDecimal.valueOf(nowMicros(true)), 63, 0);
                default -> throw new PliExecutionException("PL/I built-in is not supported: " + name);
            };
        }

        /**
         * PLISTCK の族。引数へ時計の値を入れ、条件コード 0 を返す。E の付くものは 16 バイトの
         * 拡張形式 (頭の 1 バイトは時代の番号で 0)。LOCAL は地方時、ほかは UTC である。
         */
        private Object storeClock(String name, List<PliSyntax.Expr> arguments, Env env) {
            Var target = dataReference(arguments.get(0), env, name);
            long tod = microsToStck(nowMicros(!name.contains("LOCAL")));
            byte[] bytes = new byte[target.view.length()];
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            if (name.startsWith("PLISTCKE")) {
                buffer.put((byte) 0).putLong(tod);
            } else {
                buffer.putLong(tod);
            }
            target.view.setBytes(bytes);
            return fixedBinary31(0);
        }

        private static final java.util.regex.Pattern SECONDS_DATE_TOKENS =
                java.util.regex.Pattern.compile("YYYY|YY|ZY|MMM|Mmm|MM|ZM|DDD|DD|ZD|HH|MI|SS|999999|999|.",
                        java.util.regex.Pattern.DOTALL);

        /** 型の YYYY / MM / DD を引いて日付にする。持たない型は断る。 */
        private static long lilian(String date, String pattern) {
            int y = pattern.indexOf("YYYY");
            int m = pattern.indexOf("MM");
            int d = pattern.indexOf("DD");
            if (y < 0 || m < 0 || d < 0) {
                throw new PliExecutionException("DAYS supports only patterns with YYYY, MM and DD: "
                        + pattern);
            }
            java.time.LocalDate value = java.time.LocalDate.of(
                    Integer.parseInt(date.substring(y, y + 4)),
                    Integer.parseInt(date.substring(m, m + 2)),
                    Integer.parseInt(date.substring(d, d + 2)));
            return value.toEpochDay() - LILIAN_ORIGIN;
        }

        private static String dateOf(long lilian, String pattern) {
            java.time.LocalDate value = java.time.LocalDate.ofEpochDay(lilian + LILIAN_ORIGIN);
            return pattern.replace("YYYY", String.format("%04d", value.getYear()))
                    .replace("MM", String.format("%02d", value.getMonthValue()))
                    .replace("DD", String.format("%02d", value.getDayOfMonth()));
        }

        /** UNSPEC と HEX の引数の記憶域。変数なら記憶域、値なら値の内部の形。 */
        private byte[] storageOf(PliSyntax.Function function, Env env) {
            PliSyntax.Expr argument = function.arguments().get(0);
            if (argument instanceof PliSyntax.Reference reference && env.contains(reference.name())) {
                return env.require(reference.name()).logicalBytes();
            }
            if (argument instanceof PliSyntax.Function element
                    && arrayNamed(element.name(), env) != null) {
                return element(arrayNamed(element.name(), env), element.arguments(), env)
                        .view.toByteArray();
            }
            Object value = value(argument, env);
            if (value instanceof PliSyntax.BitString bits) {
                return bytesOfBits(bits.bits());
            }
            if (value instanceof PliSyntax.HexString hex) {
                return hex.bytes();
            }
            if (value instanceof WideCharacter wide) {
                return new byte[] {(byte) (wide.unit() >>> 8), (byte) wide.unit()};
            }
            if (value instanceof PliSyntax.WideString wide) {
                return wide.text().getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
            }
            if (value instanceof PliGraphic graphic) return graphic.bytes();
            if (value instanceof MixedCharacters mixed) return mixed.bytes();
            return context.codePage().encode(display(value));
        }

        // ハッシュ入力は文字へ変換せず、POINTER が指す記憶域のバイトを渡す。
        private final Map<Integer, java.security.MessageDigest> digests = new HashMap<>();
        private int nextDigestToken = -1;

        private Object sha(Matcher function, List<Object> arguments) {
            String family = function.group(1);
            String operation = function.group(2);
            String width = function.group(3);
            if ((width == null) != family.equals("1")) {
                throw new PliExecutionException("invalid SHA algorithm: " + function.group());
            }
            String algorithm = switch (family) {
                case "1" -> "SHA-1";
                case "2" -> "SHA-" + width;
                default -> "SHA3-" + width;
            };
            int arity = switch (operation) {
                case "INIT" -> 0;
                case "DIGEST" -> 2;
                default -> 3;
            };
            if (arguments.size() != arity) {
                throw new PliExecutionException(function.group() + " needs " + arity + " arguments");
            }
            if (operation.equals("INIT")) {
                int token = nextDigestToken--;
                digests.put(token, newDigest(algorithm));
                return new PointerValue(token);
            }
            if (operation.equals("DIGEST")) {
                return new PliSyntax.HexString(newDigest(algorithm).digest(digestBytes(
                        arguments.get(0), arguments.get(1))));
            }
            int token = arguments.getFirst() instanceof PointerValue pointer ? pointer.address() : 0;
            java.security.MessageDigest digest = digests.get(token);
            if (digest == null || !digest.getAlgorithm().equals(algorithm)) {
                throw new PliExecutionException("invalid SHA token for " + algorithm);
            }
            digest.update(digestBytes(arguments.get(1), arguments.get(2)));
            if (operation.equals("UPDATE")) {
                return new PointerValue(token);
            }
            digests.remove(token);
            return new PliSyntax.HexString(digest.digest());
        }

        private static java.security.MessageDigest newDigest(String algorithm) {
            try {
                return java.security.MessageDigest.getInstance(algorithm);
            } catch (java.security.NoSuchAlgorithmException unsupported) {
                throw new PliExecutionException("missing Java digest algorithm: " + algorithm);
            }
        }

        private byte[] digestBytes(Object address, Object size) {
            int length = number(size).intValueExact();
            if (!(address instanceof PointerValue pointer) || length < 0) {
                throw new PliExecutionException("SHA requires a pointer and a nonnegative length");
            }
            if (length == 0) {
                return new byte[0];
            }
            AddressSpace.Location location = AddressSpace.of(context).locate(pointer.address());
            if (location == null || length > location.storage().size() - location.offset()) {
                throw new PliExecutionException("SHA buffer exceeds the available storage");
            }
            return location.storage().view(location.offset(), length).toByteArray();
        }

        private FixedValue xmlChar(List<PliSyntax.Expr> arguments, Env env) {
            if (arguments.size() != 3) {
                throw new PliExecutionException("XMLCHAR requires three arguments");
            }
            Var source = dataReference(arguments.get(0), env, "XMLCHAR");
            Object target = value(arguments.get(1), env);
            int capacity = number(value(arguments.get(2), env)).intValueExact();
            StringBuilder text = new StringBuilder();
            appendXml(source, text);
            byte[] bytes = context.codePage().encode(text.toString());
            int length = Math.min(bytes.length, Math.max(0, capacity));
            pointerBuffer(target, length, "XMLCHAR").setBytes(java.util.Arrays.copyOf(bytes, length));
            return FixedValue.binary(BigDecimal.valueOf(bytes.length), 31, 0);
        }

        private void appendXml(Var variable, StringBuilder text) {
            if (variable.name.startsWith("*")) {
                return;
            }
            if (variable.dimensions != null) {
                for (Var element : variable.elements) {
                    appendXml(element, text);
                }
                return;
            }
            String tag = variable.name.substring(variable.name.lastIndexOf('.') + 1)
                    .toUpperCase(Locale.ROOT);
            text.append('<').append(tag).append('>');
            if (variable.type == PliSyntax.Type.GROUP) {
                for (Var member : variable.members) {
                    appendXml(member, text);
                }
            } else {
                text.append(display(variable.read(context)).strip());
            }
            text.append("</").append(tag).append('>');
        }

        private FixedValue xmlScrub(String name, List<Object> arguments) {
            if (arguments.size() != 4 || !(arguments.get(0) instanceof PointerValue target)) {
                throw new PliExecutionException(name + " requires two pointers and two lengths");
            }
            int capacity = Math.max(0, number(arguments.get(1)).intValueExact());
            int length = Math.max(0, number(arguments.get(3)).intValueExact());
            byte[] source = pointerBuffer(arguments.get(2), length, name).toByteArray();
            byte[] output = name.equals("XMLSCRUB") ? scrubEbcdicXml(source) : scrubWideXml(source);
            int result = output.length;
            if (target.address() != 0) {
                if (output.length > capacity) {
                    result = -1;
                } else {
                    pointerBuffer(target, output.length, name).setBytes(output);
                }
            }
            return FixedValue.binary(BigDecimal.valueOf(result), 31, 0);
        }

        private DataView pointerBuffer(Object address, int length, String name) {
            AddressSpace.Location location = address instanceof PointerValue pointer
                    ? AddressSpace.of(context).locate(pointer.address()) : null;
            if (location == null || length > location.storage().size() - location.offset()) {
                throw new PliExecutionException(name + " buffer exceeds the available storage");
            }
            return location.storage().view(location.offset(), length);
        }

        private FixedValue convertBuffer(String name, List<Object> arguments) {
            boolean codePages = name.equals("MEMCONVERT");
            if (arguments.size() != (codePages ? 6 : 4)
                    || !(arguments.getFirst() instanceof PointerValue)
                    || !(arguments.get(codePages ? 3 : 2) instanceof PointerValue)) {
                throw new PliExecutionException(name + " requires pointers and buffer lengths"
                        + (codePages ? " with source and target code pages" : ""));
            }
            int capacity = Math.max(0, number(arguments.get(1)).intValueExact());
            int length = Math.max(0, number(arguments.get(codePages ? 4 : 3)).intValueExact());
            if (length == 0 || (codePages && capacity == 0)) {
                return FixedValue.binary(BigDecimal.ZERO, 63, 0);
            }
            byte[] input = pointerBuffer(arguments.get(codePages ? 3 : 2), length, name).toByteArray();
            byte[] output;
            if (codePages) {
                var from = bufferCodePage(arguments.get(5));
                var to = bufferCodePage(arguments.get(2));
                output = to.encode(from.decode(input));
                if (output.length > capacity) {
                    throw new PliExecutionException(name + ": target buffer is too small");
                }
            } else {
                try {
                    output = transcodeUnicode(input, name.charAt(5), name.charAt(6));
                } catch (java.nio.charset.CharacterCodingException invalid) {
                    return FixedValue.binary(BigDecimal.valueOf(-1), 63, 0);
                }
                if (output.length > capacity) {
                    return FixedValue.binary(BigDecimal.valueOf(-1), 63, 0);
                }
            }
            pointerBuffer(arguments.getFirst(), output.length, name).setBytes(output);
            return FixedValue.binary(BigDecimal.valueOf(output.length), 63, 0);
        }

        private static dev.cobolonjava.runtime.codepage.CodePage bufferCodePage(Object ccsid) {
            String name = number(ccsid).toBigIntegerExact().toString();
            try {
                return dev.cobolonjava.runtime.codepage.CodePages.forName(name);
            } catch (java.nio.charset.UnsupportedCharsetException unsupported) {
                throw new PliExecutionException("MEMCONVERT: unsupported code page " + name);
            }
        }

        private static byte[] transcodeUnicode(byte[] input, char from, char to)
                throws java.nio.charset.CharacterCodingException {
            java.nio.CharBuffer text;
            if (from == '4') {
                if (input.length % Integer.BYTES != 0) {
                    throw new java.nio.charset.MalformedInputException(input.length % Integer.BYTES);
                }
                StringBuilder decoded = new StringBuilder();
                var bytes = java.nio.ByteBuffer.wrap(input);
                while (bytes.hasRemaining()) {
                    int codePoint = bytes.getInt();
                    if (!Character.isValidCodePoint(codePoint)
                            || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                        throw new java.nio.charset.MalformedInputException(Integer.BYTES);
                    }
                    decoded.appendCodePoint(codePoint);
                }
                text = java.nio.CharBuffer.wrap(decoded);
            } else {
                var charset = from == '1' ? java.nio.charset.StandardCharsets.UTF_8
                        : java.nio.charset.StandardCharsets.UTF_16BE;
                text = charset.newDecoder().decode(java.nio.ByteBuffer.wrap(input));
            }
            if (to == '4') {
                int[] codePoints = text.codePoints().toArray();
                var result = java.nio.ByteBuffer.allocate(Math.multiplyExact(codePoints.length, Integer.BYTES));
                for (int codePoint : codePoints) {
                    result.putInt(codePoint);
                }
                return result.array();
            }
            var charset = to == '1' ? java.nio.charset.StandardCharsets.UTF_8
                    : java.nio.charset.StandardCharsets.UTF_16BE;
            var encoded = charset.newEncoder().encode(text);
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        }

        private static int utf8Size(byte[] data) {
            int first = data.length == 0 ? 0x80 : data[0] & 0xFF;
            if (first <= 0x7F) return 1;
            if (first >= 0xC2 && first <= 0xDF) return 2;
            if (first >= 0xE0 && first <= 0xEF) return 3;
            if (first >= 0xF0 && first <= 0xF4) return 4;
            return 0;
        }

        private byte[] scrubEbcdicXml(byte[] source) {
            var page = context.codePage();
            var result = new java.io.ByteArrayOutputStream(source.length);
            for (byte unit : source) {
                int code = unit & 0xFF;
                String decoded = page.decode(new byte[] {unit});
                String replacement = code == 0x0D ? "&#xD;"
                        : decoded.length() == 1 ? xmlEntity(decoded.charAt(0)) : null;
                if (replacement != null) {
                    result.writeBytes(page.encode(replacement));
                } else {
                    result.write(code < 0x40 && code != 0x05 && code != 0x15 && code != 0x25
                            ? page.space() : unit);
                }
            }
            return result.toByteArray();
        }

        private static byte[] scrubWideXml(byte[] source) {
            StringBuilder result = new StringBuilder();
            for (int offset = 0; offset < source.length; offset += 2) {
                char unit = wideUnit(source, offset);
                String replacement = xmlEntity(unit);
                if (replacement != null) {
                    result.append(replacement);
                } else if (Character.isHighSurrogate(unit) && offset + 2 < source.length
                        && Character.isLowSurrogate(wideUnit(source, offset + 2))) {
                    result.append(unit).append(wideUnit(source, offset + 2));
                    offset += 2;
                } else {
                    result.append(Character.isSurrogate(unit) ? ' ' : unit);
                }
            }
            return result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        }

        private static char wideUnit(byte[] source, int offset) {
            return (char) ((source[offset] & 0xFF) << 8
                    | (offset + 1 < source.length ? source[offset + 1] & 0xFF : 0));
        }

        private static String xmlEntity(char unit) {
            return switch (unit) {
                case '\r' -> "&#xD;";
                case '"' -> "&quot;";
                case '\'' -> "&apos;";
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                default -> null;
            };
        }

        private static String bitsOf(byte[] bytes) {
            StringBuilder out = new StringBuilder(bytes.length * 8);
            for (byte b : bytes) {
                for (int i = 7; i >= 0; i--) {
                    out.append((b >> i & 1) == 1 ? '1' : '0');
                }
            }
            return out.toString();
        }

        private static byte[] bytesOfBits(String bits) {
            byte[] out = new byte[(bits.length() + 7) / 8];
            for (int i = 0; i < bits.length(); i++) {
                if (bits.charAt(i) == '1') {
                    out[i / 8] |= (byte) (0x80 >> (i % 8));
                }
            }
            return out;
        }

        /** HEX(x): 記憶域 (ビット列なら 4 ビットずつ) を 16 進の字にする (LRM "HEX")。 */
        private boolean allCompare(List<PliSyntax.Expr> arguments, Env env) {
            if (arguments.size() < 2 || arguments.size() > 3) {
                throw new PliExecutionException("ALLCOMPARE takes two or three arguments");
            }
            Var left = dataReference(arguments.get(0), env, "ALLCOMPARE");
            Var right = dataReference(arguments.get(1), env, "ALLCOMPARE");
            if (!similarScalarStructure(left, right) || left.type != PliSyntax.Type.GROUP) {
                throw new PliExecutionException("ALLCOMPARE requires similar structures of scalars");
            }
            String relation = arguments.size() == 2 ? "EQ"
                    : display(value(arguments.get(2), env)).toUpperCase(Locale.ROOT);
            List<Var> a = elements(left);
            List<Var> b = elements(right);
            for (int i = 0; i < a.size(); i++) {
                int result = arrayCompare(a.get(i), b.get(i).read(context), null, env);
                boolean matched = switch (relation) {
                    case "EQ" -> result == 0;
                    case "NE" -> result != 0;
                    case "LT" -> result < 0;
                    case "LE" -> result <= 0;
                    case "GT" -> result > 0;
                    case "GE" -> result >= 0;
                    default -> throw new PliExecutionException("invalid ALLCOMPARE relation: " + relation);
                };
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        private static boolean similarScalarStructure(Var left, Var right) {
            if (left.dimensions != null || right.dimensions != null
                    || left.type != right.type || left.members.size() != right.members.size()) {
                return false;
            }
            for (int i = 0; i < left.members.size(); i++) {
                if (!similarScalarStructure(left.members.get(i), right.members.get(i))) {
                    return false;
                }
            }
            return true;
        }

        private Object hexOf(PliSyntax.Function function, Env env) {
            byte[] bytes = storageOf(function, env);
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) {
                out.append(String.format("%02X", b & 0xFF));
            }
            return out.toString();
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
            if (left instanceof PliSyntax.Complex || right instanceof PliSyntax.Complex) {
                // 複素数の演算。結果の精度の規則は持たず、値を切らずに運ぶ (実機と突き合わせていない)
                PliSyntax.Complex x = complex(left);
                PliSyntax.Complex y = complex(right);
                BigDecimal a = x.real(), b = x.imaginary(), c = y.real(), d = y.imaginary();
                return switch (operator) {
                    case "+" -> new PliSyntax.Complex(a.add(c), b.add(d));
                    case "-" -> new PliSyntax.Complex(a.subtract(c), b.subtract(d));
                    case "*" -> new PliSyntax.Complex(a.multiply(c).subtract(b.multiply(d)),
                            a.multiply(d).add(b.multiply(c)));
                    default -> {
                        BigDecimal norm = c.multiply(c).add(d.multiply(d));
                        if (norm.signum() == 0) {
                            throw new Condition("ZERODIVIDE", null, 320, null);
                        }
                        java.math.MathContext mc = java.math.MathContext.DECIMAL128;
                        yield new PliSyntax.Complex(
                                a.multiply(c).add(b.multiply(d)).divide(norm, mc),
                                b.multiply(c).subtract(a.multiply(d)).divide(norm, mc));
                    }
                };
            }
            if (left instanceof FixedValue a && right instanceof FixedValue b) {
                FixedValue result = switch (operator) {
                    case "+" -> a.add(b, rules);
                    case "-" -> a.subtract(b, rules);
                    case "*" -> a.multiply(b, rules);
                    default -> a.divide(b, rules);
                };
                if (!result.binary()
                        && result.value().setScale(result.scale(), java.math.RoundingMode.DOWN)
                                .precision() > result.precision()) {
                    throw new Condition("FIXEDOVERFLOW", null, 310, null);
                }
                return result;
            }
            BigDecimal a = number(left);
            BigDecimal b = number(right);
            if (rules != null && rules.options().dfp()) {
                // FLOAT(DFP): 10 進の IEEE の長形式 (16 桁、最も近い値へ、同じなら偶数へ) で計算する
                MathContext dfp = MathContext.DECIMAL64;
                return switch (operator) {
                    case "+" -> a.add(b, dfp);
                    case "-" -> a.subtract(b, dfp);
                    case "*" -> a.multiply(b, dfp);
                    default -> {
                        if (b.signum() == 0) throw new Condition("ZERODIVIDE", null, 320, null);
                        yield a.divide(b, dfp);
                    }
                };
            }
            return switch (operator) {
                case "+" -> a.add(b);
                case "-" -> a.subtract(b);
                case "*" -> a.multiply(b);
                default -> a.divide(b, MathContext.DECIMAL128);
            };
        }

        /**
         * べき乗。指数が整数なら掛け算を重ねて正確に出し、負なら逆数にする。整数でなければ
         * 浮動小数点で近似する (LRM "Exponentiation")。結果の属性 (FIXED / FLOAT) は持たない。
         */
        private static Object power(BigDecimal base, BigDecimal exponent) {
            if (exponent.stripTrailingZeros().scale() <= 0) {
                int whole = exponent.intValueExact();
                if (whole >= 0) {
                    return base.pow(whole);
                }
                if (base.signum() == 0) {
                    throw new Condition("ZERODIVIDE", null, 320, null);
                }
                return BigDecimal.ONE.divide(base.pow(-whole), MathContext.DECIMAL64);
            }
            if (base.signum() < 0) {
                throw new PliExecutionException("ERROR: a negative number raised to a"
                        + " non-integer power");
            }
            return new BigDecimal(Math.pow(base.doubleValue(), exponent.doubleValue()),
                    MathContext.DECIMAL64);
        }

        private String filled(byte value, List<Object> arguments) {
            int count = number(arguments.get(0)).intValue();
            if (count < 0) {
                throw new PliExecutionException("the length must not be negative: " + count);
            }
            byte[] bytes = new byte[count];
            java.util.Arrays.fill(bytes, value);
            return context.codePage().decode(bytes);
        }

        private Object size(PliSyntax.Function function, Env env) {
            if (function.arguments().size() != 1) {
                throw new PliExecutionException("SIZE requires one data reference");
            }
            // SIZE は FIXED BIN(31) を返す。配列なら全体、要素なら要素の大きさ
            return FixedValue.binary(BigDecimal.valueOf(
                    dataReference(function.arguments().get(0), env, "SIZE").view.length()), 31, 0);
        }

        private Object address(PliSyntax.Function function, Env env) {
            if (function.arguments().size() != 1) {
                throw new PliExecutionException("ADDR requires one data reference");
            }
            return new PointerValue(address(
                    dataReference(function.arguments().get(0), env, "ADDR").view));
        }

        /** 変数の参照。名か、配列の要素 {@code A(I)} である。 */
        private Var dataReference(PliSyntax.Expr expression, Env env, String user) {
            if (expression instanceof PliSyntax.Reference reference) {
                return env.require(reference.name());
            }
            if (expression instanceof PliSyntax.Function function) {
                Var array = arrayNamed(function.name(), env);
                if (array != null) {
                    return element(array, function.arguments(), env);
                }
            }
            throw new PliExecutionException(user + " requires a data reference");
        }

        /** {@code name} が宣言した配列なら、その変数。そうでなければ {@code null}。 */
        private static Var arrayNamed(String name, Env env) {
            if (!env.contains(name)) {
                return null;
            }
            Var variable = env.require(name);
            return variable.dimensions != null ? variable : null;
        }

        /**
         * 配列の要素。添字が上下限の外なら止める。実機は SUBSCRIPTRANGE が既定で無効で、外の
         * 記憶域を黙って読み書きする。近い値を黙って返すより断るほうを採る (COBOL の SSRANGE と同じ)。
         */
        private Var element(Var array, List<PliSyntax.Expr> subscripts, Env env) {
            if (subscripts.size() != array.dimensions.size()) {
                throw new PliExecutionException(array.name + " has " + array.dimensions.size()
                        + " dimension(s), but " + subscripts.size() + " subscript(s) were given");
            }
            int index = 0;
            for (int d = 0; d < subscripts.size(); d++) {
                PliSyntax.Bound bound = array.dimensions.get(d);
                BigDecimal value = number(value(subscripts.get(d), env));
                int subscript = value.setScale(0, java.math.RoundingMode.DOWN).intValue();
                if (subscript < bound.low() || subscript > bound.high()) {
                    throw new Condition("SUBSCRIPTRANGE", null, 520, null);
                }
                index = index * bound.extent() + (subscript - bound.low());
            }
            return array.elements.get(index);
        }

        /**
         * 配列の断面への代入 {@code A(*,2) = 値} (LRM "Cross sections of arrays")。* の次元は
         * すべての添字を、そうでない次元は書いた添字だけを取る。値はスカラーだけを持つ。
         */
        private void crossSection(Var array, PliSyntax.Assign assignment, Env env) {
            List<PliSyntax.Expr> subscripts = assignment.subscripts();
            if (subscripts.size() != array.dimensions.size()) {
                throw new PliExecutionException(array.name + " has " + array.dimensions.size()
                        + " dimension(s), but " + subscripts.size() + " subscript(s) were given");
            }
            if (containsArray(assignment.value(), env)) {
                throw new PliExecutionException("assigning an array expression to a cross"
                        + " section is not supported yet: " + array.name);
            }
            Integer[] fixed = new Integer[subscripts.size()];
            for (int d = 0; d < subscripts.size(); d++) {
                if (!(subscripts.get(d) instanceof PliSyntax.Literal literal
                        && literal.value() == PliSyntax.Asterisk.STAR)) {
                    fixed[d] = number(value(subscripts.get(d), env)).intValue();
                    PliSyntax.Bound bound = array.dimensions.get(d);
                    if (fixed[d] < bound.low() || fixed[d] > bound.high()) {
                        throw new PliExecutionException("SUBSCRIPTRANGE: subscript " + fixed[d]
                                + " of " + array.name);
                    }
                }
            }
            Object value = value(assignment.value(), env);
            int rank = array.dimensions.size();
            int[] index = new int[rank];
            for (int d = 0; d < rank; d++) index[d] = array.dimensions.get(d).low();
            for (Var element : array.elements) {
                boolean selected = true;
                for (int d = 0; d < rank; d++) {
                    if (fixed[d] != null && fixed[d] != index[d]) selected = false;
                }
                if (selected) {
                    element.write(value, context);
                }
                for (int d = rank - 1; d >= 0; d--) {
                    if (++index[d] <= array.dimensions.get(d).high()) break;
                    index[d] = array.dimensions.get(d).low();
                }
            }
        }

        /**
         * 擬似変数 {@code UNSPEC(x) = ビット列} (LRM "UNSPEC pseudovariable")。値を x の記憶域の
         * ビットの長さに右を 0 で埋めるか切って、記憶域へそのまま書く。
         */
        private void unspecPseudovariable(PliSyntax.Assign assignment, Env env) {
            if (assignment.subscripts().size() != 1) {
                throw new PliExecutionException("UNSPEC pseudovariable needs one argument");
            }
            Var target = dataReference(assignment.subscripts().get(0), env, "UNSPEC");
            if (target.bitShift != 0 || target.dimensions != null) {
                throw new PliExecutionException("UNSPEC pseudovariable on an unaligned bit or an"
                        + " array is not supported yet: " + target.name);
            }
            String bits = bitText(value(assignment.value(), env));
            int length = target.view.length() * 8;
            bits = bits.length() >= length ? bits.substring(0, length)
                    : bits + "0".repeat(length - bits.length());
            byte[] bytes = new byte[target.view.length()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
            }
            target.view.setBytes(bytes);
        }

        /**
         * {@code Y = X, BY NAME;}。同じ名の要素どうしを、名の要素がともに構造なら入れ子で
         * 代入する (LRM "BY NAME option")。名の合わない要素は変えない。
         */
        private void assignByName(Var target, Var source) {
            if (target.type != PliSyntax.Type.GROUP || source.type != PliSyntax.Type.GROUP) {
                throw new PliExecutionException("BY NAME needs structures: " + target.name
                        + ", " + source.name);
            }
            for (Var member : target.members) {
                for (Var from : source.members) {
                    if (!from.name.equals(member.name) || member.name.equals("*")) {
                        continue;
                    }
                    boolean group = member.type == PliSyntax.Type.GROUP;
                    if (group != (from.type == PliSyntax.Type.GROUP)) {
                        continue;
                    }
                    if (group) {
                        assignByName(member, from);
                    } else if (member.dimensions != null || from.dimensions != null) {
                        throw new PliExecutionException("BY NAME with array members is not"
                                + " supported yet: " + member.name);
                    } else {
                        member.write(from.read(context), context);
                    }
                }
            }
        }

        /**
         * 擬似変数 {@code SUBSTR(x, i, n) = 値} (LRM "SUBSTR pseudovariable")。x の i 字目から n 字を、
         * 値を n 字に切るか空白で埋めたもので置き換える。x の長さは変わらない。
         */
        private void substrPseudovariable(PliSyntax.Assign assignment, Env env) {
            List<PliSyntax.Expr> arguments = assignment.subscripts();
            if (arguments.size() < 2 || arguments.size() > 3) {
                throw new PliExecutionException("SUBSTR pseudovariable needs 2 or 3 arguments");
            }
            Var target = dataReference(arguments.get(0), env, "SUBSTR");
            String current = display(target.read(context));
            int start = number(value(arguments.get(1), env)).intValue();
            int length = arguments.size() > 2 ? number(value(arguments.get(2), env)).intValue()
                    : current.length() - start + 1;
            if (start < 1 || length < 0 || start - 1 + length > current.length()) {
                throw new PliExecutionException("STRINGRANGE: SUBSTR(" + target.name + ", "
                        + start + ", " + length + ") is outside the string");
            }
            String piece = display(value(assignment.value(), env));
            piece = piece.length() >= length ? piece.substring(0, length)
                    : piece + " ".repeat(length - piece.length());
            target.write(current.substring(0, start - 1) + piece
                    + current.substring(start - 1 + length), context);
        }

        private static String substring(List<Object> arguments) {
            String source = display(arguments.get(0));
            int start = number(arguments.get(1)).intValue() - 1;
            int length = arguments.size() > 2 ? number(arguments.get(2)).intValue()
                    : source.length() - start;
            if (start < 0 || start > source.length() || length < 0
                    || length > source.length() - start) {
                throw new Condition("STRINGRANGE", null, 350, null);
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

        private boolean validValue(List<PliSyntax.Expr> arguments, Env env) {
            if (arguments.isEmpty() || arguments.size() > 2) {
                throw new PliExecutionException("VALIDVALUE requires a data reference and an optional value");
            }
            Var target = dataReference(arguments.getFirst(), env, "VALIDVALUE");
            PliSyntax.ValueSet set = target.valueSet;
            if (set == null || target.dimensions != null || target.type == PliSyntax.Type.GROUP) {
                throw new PliExecutionException("VALIDVALUE requires a scalar with VALUELIST or VALUERANGE");
            }
            Var converted = new Var(target.name, target.type, target.precision, target.scale,
                    Storage.allocate(target.view.length()).whole(), target.bitShift);
            converted.varying = target.varying;
            converted.unsigned = target.unsigned;
            converted.picture = target.picture;
            converted.complex = target.complex;
            Object sought = arguments.size() == 1 ? target.read(context)
                    : convertValue(converted, arguments.get(1), env);
            List<PliSyntax.Expr> expressions = set.expressions();
            if (set.range()) {
                return between(sought, convertValue(converted, expressions.get(0), env),
                        convertValue(converted, expressions.get(1), env));
            }
            for (PliSyntax.Expr expression : expressions) {
                if (compare(sought, convertValue(converted, expression, env)) == 0) return true;
            }
            return false;
        }

        private Object convertValue(Var target, PliSyntax.Expr expression, Env env) {
            target.write(value(expression, env), context);
            return target.read(context);
        }

        private static boolean between(Object sought, Object low, Object high) {
            return compare(sought, low) >= 0 && compare(sought, high) <= 0;
        }

        private static int compare(Object left, Object right) {
            if (left instanceof PliSyntax.Complex || right instanceof PliSyntax.Complex) {
                // 複素数は等しいかどうかだけを比べられる。等しくなければ 0 でない値を返す
                PliSyntax.Complex x = complex(left);
                PliSyntax.Complex y = complex(right);
                return x.real().compareTo(y.real()) == 0
                        && x.imaginary().compareTo(y.imaginary()) == 0 ? 0 : 1;
            }
            if (numeric(left) || numeric(right)) {
                return number(left).compareTo(number(right));
            }
            if (left instanceof PointerValue a && right instanceof PointerValue b) {
                return Integer.compare(a.address(), b.address());
            }
            if (left instanceof EntryValue || right instanceof EntryValue) {
                return java.util.Objects.equals(left, right) ? 0 : 1;
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
                    || value instanceof NumericPicture || value instanceof PliSyntax.Complex;
        }

        private static PliSyntax.Complex complex(Object value) {
            return value instanceof PliSyntax.Complex z ? z
                    : new PliSyntax.Complex(number(value), BigDecimal.ZERO);
        }

        private static BigDecimal number(Object value) {
            if (value instanceof FixedValue fixed) return fixed.value();
            // 複素数を実数にすると虚部は捨てられる (LRM "Mode conversion")
            if (value instanceof PliSyntax.Complex z) return z.real();
            if (value instanceof NumericPicture picture) return picture.fixed().value();
            // ビット列を数にすると、符号なしの 2 進の値になる (LRM "Target: Coded arithmetic", BIT)
            if (value instanceof PliSyntax.BitString bits) {
                return new BigDecimal(new java.math.BigInteger(bits.bits(), 2));
            }
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof Number numeric) return new BigDecimal(numeric.toString());
            if (value instanceof Boolean bool) return bool ? BigDecimal.ONE : BigDecimal.ZERO;
            String original = display(value);
            String repaired = REPAIRED.get().get(original);
            String text = (repaired != null ? repaired : original).strip();
            if (text.isEmpty()) {
                // 空の文字列と空白だけの文字列は 0 になり、CONVERSION は起きない (LRM "Target: Coded
                // arithmetic", CHARACTER)
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                // 字を数にできない。CONVERSION の条件である (ONCODE 600 番台)
                throw new Condition("CONVERSION", null, 612, repaired != null ? repaired : original);
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
            if (value instanceof WideCharacter wide) return Character.toString(wide.unit());
            if (value instanceof PliSyntax.WideString wide) return wide.text();
            if (value instanceof PliGraphic graphic) return graphic.characters();
            if (value instanceof MixedCharacters mixed) return mixed.page().decode(mixed.bytes());
            // ビット列から文字への変換は '1' と '0' になる
            if (value instanceof Boolean bit) return bit ? "1" : "0";
            if (value instanceof PliSyntax.BitString bits) return bits.bits();
            if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
            return value.toString();
        }

        /** 変数の大きさ。POINTER も 4 byte の値を持つ。 */
        private static int byteLength(PliSyntax.Decl declaration) {
            // 配列は要素の間隔の要素の数倍。UNALIGNED のビット列の配列はビット単位で詰まる
            return (StructureMapping.totalBits(declaration,
                    StructureMapping.unaligned(List.of(declaration), 0)) + 7) / 8;
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
        /** 内部手続きに渡された引数。変数 ({@link Var}) か、式の値。名は大文字。 */
        private final Map<String, Object> arguments = new HashMap<>();

        /** on-unit を探す鎖 (呼んだ側)。名を探す鎖 ({@link #parent}、宣言した場所) とは別である。 */
        private final Env caller;

        Env(Env parent, ProgramContext context) {
            this(parent, parent, context);
        }

        Env(Env parent, Env caller, ProgramContext context) {
            this.parent = parent;
            this.caller = caller;
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
            if (value.unsupported != null) {
                throw new PliExecutionException(value.unsupported + " is not supported yet: "
                        + value.name);
            }
            if (value.unallocated) {
                throw new PliExecutionException("CONTROLLED variable " + value.name
                        + " is not allocated");
            }
            if (value.unboundOn != null) {
                throw new PliExecutionException("BASED on " + value.unboundOn
                        + ", which is a null pointer: " + name);
            }
            return value;
        }

        void onEndFile(String file, List<PliSyntax.Stmt> handler) {
            endFileHandlers.put(file.toUpperCase(Locale.ROOT), handler);
        }

        /** 条件の on-unit と、それを確立した環境。SYSTEM なら handler は空で system が真。 */
        record Handler(List<PliSyntax.Stmt> statements, Env established, boolean system) {
        }

        private final Map<String, Handler> handlers = new HashMap<>();

        void establish(String key, Handler handler) {
            handlers.put(key, handler);
        }

        void revert(String key) {
            handlers.remove(key);
        }

        /** 呼んだ側へたどって on-unit を探す (動的な有効範囲。LRM "Establishing an ON-unit")。 */
        Handler handler(String key) {
            Handler own = handlers.get(key);
            return own != null ? own : caller == null ? null : caller.handler(key);
        }

        List<PliSyntax.Stmt> endFile(String file) {
            List<PliSyntax.Stmt> own = endFileHandlers.get(file.toUpperCase(Locale.ROOT));
            return own != null ? own : caller == null ? null : caller.endFile(file);
        }

        Var find(String name) {
            String normalized = name.toUpperCase(Locale.ROOT);
            Var own = variables.get(normalized);
            return own != null ? own : parent == null ? null : parent.find(normalized);
        }
    }

    /**
     * CONTROLLED の変数の世代 (LRM "CONTROLLED storage")。ALLOCATE するたびに宣言の環境で
     * 宣言し直し、置き換えた束縛をここへ積む。FREE で 1 つ戻す。積んだ数が ALLOCATION である。
     */
    private static final class Generations {
        final List<PliSyntax.Decl> chunk;
        final Env owner;
        final java.util.ArrayDeque<Map<String, Var>> saved = new java.util.ArrayDeque<>();

        Generations(List<PliSyntax.Decl> chunk, Env owner) {
            this.chunk = List.copyOf(chunk);
            this.owner = owner;
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
        /** NULL の POINTER に重ねた BASED の変数なら、その POINTER の名。参照すると止まる。 */
        String unboundOn;
        /** {@code CHAR(n) VARYING} なら真。記憶域の頭の半語が今の長さ、n は {@link #precision}。 */
        boolean varying;
        /** {@code FIXED BIN UNSIGNED} なら真。記憶域を符号なしで読む。 */
        boolean unsigned;
        /** LITTLEENDIAN なら真。2 進の整数と VARYING の長さを逆のバイト順で置く。 */
        boolean littleEndian;
        /** FLOAT の形式 ("IEEE"、"DFP"、"HEXADEC"、null は HEXADEC)。 */
        String floatFormat;

        /** 2 進の整数の記憶域を、大きい桁が先の順で読む。 */
        private byte[] bigEndianBytes() {
            byte[] bytes = view.toByteArray();
            return littleEndian ? reversed(bytes) : bytes;
        }

        private static byte[] reversed(byte[] bytes) {
            byte[] out = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) out[i] = bytes[bytes.length - 1 - i];
            return out;
        }

        /** HEX と UNSPEC が見る記憶域。LITTLEENDIAN の整数は大きい桁が先の形にする (LRM "HEX")。 */
        byte[] logicalBytes() {
            return littleEndian && type == PliSyntax.Type.BINARY ? bigEndianBytes() : view.toByteArray();
        }

        private BigDecimal floatRead() {
            if ("IEEE".equals(floatFormat)) return PliFloat.decodeBinary(view.toByteArray());
            if ("DFP".equals(floatFormat)) return PliFloat.decodeDecimal(view.toByteArray());
            return hfpDecode();
        }

        private void floatWrite(BigDecimal value) {
            if ("IEEE".equals(floatFormat)) {
                view.setBytes(PliFloat.encodeBinary(value, view.length()));
            } else if ("DFP".equals(floatFormat)) {
                view.setBytes(PliFloat.encodeDecimal(value, view.length()));
            } else {
                hfpEncode(value);
            }
        }
        /** 配列なら次元ごとの上下限、スカラーなら {@code null}。 */
        List<PliSyntax.Bound> dimensions;
        /** 配列の要素。行の順。 */
        final List<Var> elements = new ArrayList<>();
        /** CONTROLLED なら、その世代の積み重ね。 */
        Generations generations;
        /** COMPLEX なら真。記憶域の前半が実部、後半が虚部である。 */
        boolean complex;
        /** 数の PICTURE なら、その指定。 */
        PliPicture picture;
        PliSyntax.ValueSet valueSet;
        /** まだ持たない型の属性で宣言した名なら、その属性。参照すると止まる。 */
        String unsupported;
        /** CONTROLLED をまだ ALLOCATE していない印。参照すると止まる。 */
        boolean unallocated;

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

        /** 複素数の変数の実部 (0) か虚部 (1) を、同じ型の実数の変数として見る。 */
        private Var part(int index) {
            int half = view.length() / 2;
            Var part = new Var(name, type, precision, scale, view.subView(index * half, half));
            part.unsigned = unsigned;
            return part;
        }

        Object read(ProgramContext context) {
            if (complex) {
                return new PliSyntax.Complex(Executor.number(part(0).read(context)),
                        Executor.number(part(1).read(context)));
            }
            if (type == PliSyntax.Type.CHAR && varying) {
                // 頭の半語が今の長さ (LRM Table 39)。宣言の長さを超える値は壊れた記憶域である
                int length = currentLength();
                return context.codePage().decode(view.subView(2, length).toByteArray());
            }
            return switch (type) {
                case CHAR, GROUP -> context.codePage().decode(view.toByteArray());
                // ビット列は 8 ビットごとに 1 byte、左詰め (LRM Table 39)。値は '0' と '1' の並び
                case BIT -> new PliSyntax.BitString(bits());
                // 数の PICTURE は、出力では字をそのまま送り (LRM "For numeric character values, the
                // character value is transmitted")、演算では FIXED DEC(p,q) として扱う
                case PICTURE -> new NumericPicture(
                        context.codePage().decode(view.toByteArray()), precision, scale, picture);
                // FIXED BIN(p,q) の記憶域は 2 の補数の整数で、値はそれを 2 の q 乗で割ったもの
                case BINARY -> FixedValue.binary(new BigDecimal(
                        unsigned ? new java.math.BigInteger(1, bigEndianBytes())
                                : new java.math.BigInteger(bigEndianBytes()))
                        .divide(BigDecimal.valueOf(2).pow(Math.max(0, scale)))
                        .multiply(BigDecimal.valueOf(2).pow(Math.max(0, -scale))),
                        precision, scale);
                case DECIMAL -> FixedValue.decimal(PackedDecimal.decode(view.toByteArray(), scale,
                        NumProcMode.PFD).toBigDecimal(), precision, scale);
                case POINTER -> new PointerValue(view.length() < 4 ? 0
                        : java.nio.ByteBuffer.wrap(view.toByteArray(), 0, 4).getInt());
                case FLOAT -> floatRead();
                case WIDECHAR -> new String(view.toByteArray(),
                        java.nio.charset.StandardCharsets.UTF_16BE);
                case GRAPHIC -> new PliGraphic(view.toByteArray(), context.codePage());
                case OFFSET -> FixedValue.binary(BigDecimal.valueOf(
                        java.nio.ByteBuffer.wrap(view.toByteArray(), 0, 4).getInt()), 31, 0);
                case AREA -> new AreaImage(view.toByteArray());
                // 入口の変数。頭の 4 byte が AddressSpace の入口の番号である
                case ENTRY -> {
                    int number = view.length() < 4 ? 0
                            : java.nio.ByteBuffer.wrap(view.toByteArray(), 0, 4).getInt();
                    String entry = number == 0 ? null
                            : AddressSpace.of(context).entryNamed(number);
                    yield new EntryValue(entry);
                }
                case FILE -> "";
            };
        }

        /**
         * FLOAT の値を 16 進浮動小数 (HFP) で書く。頭の byte は符号と 64 を足した 16 の指数、
         * 残りは 16 進の小数である。入らない下の桁は切り捨てる。Enterprise PL/I の既定は
         * DEFAULT(HEXADEC) である。IEEE (DEFAULT(IEEE)) と拡張精度 (16 byte) はまだ持たない。
         * 実機と突き合わせていない。
         */
        private void hfpEncode(BigDecimal value) {
            if (view.length() != 4 && view.length() != 8) {
                throw new PliExecutionException("extended FLOAT is not supported yet: " + name);
            }
            byte[] bytes = new byte[view.length()];
            if (value.signum() != 0) {
                int digits = (view.length() - 1) * 2;
                BigDecimal a = value.abs();
                BigDecimal sixteen = BigDecimal.valueOf(16);
                int exponent = 0;
                while (a.compareTo(power16(exponent)) >= 0) exponent++;
                while (a.compareTo(power16(exponent - 1)) < 0) exponent--;
                java.math.BigInteger fraction = a.multiply(power16(digits - exponent))
                        .setScale(0, java.math.RoundingMode.DOWN).toBigInteger();
                if (exponent + 64 < 0) {
                    throw new Condition("UNDERFLOW", null, 330, null, () -> view.setBytes(bytes));
                }
                if (exponent + 64 > 127) {
                    throw new Condition("OVERFLOW", null, 300, null);
                }
                bytes[0] = (byte) ((value.signum() < 0 ? 0x80 : 0) | (exponent + 64));
                byte[] raw = fraction.toByteArray();
                for (int i = 0; i < raw.length && i < bytes.length - 1; i++) {
                    bytes[bytes.length - 1 - i] = raw[raw.length - 1 - i];
                }
            }
            view.setBytes(bytes);
        }

        private static BigDecimal power16(int exponent) {
            BigDecimal power = BigDecimal.valueOf(16).pow(Math.abs(exponent));
            return exponent >= 0 ? power : BigDecimal.ONE.divide(power);
        }

        private BigDecimal hfpDecode() {
            if (view.length() != 4 && view.length() != 8) {
                throw new PliExecutionException("extended FLOAT is not supported yet: " + name);
            }
            byte[] bytes = view.toByteArray();
            java.math.BigInteger fraction = new java.math.BigInteger(1,
                    java.util.Arrays.copyOfRange(bytes, 1, bytes.length));
            if (fraction.signum() == 0) {
                return BigDecimal.ZERO;
            }
            int exponent = (bytes[0] & 0x7F) - 64;
            BigDecimal value = new BigDecimal(fraction)
                    .multiply(power16(exponent - (bytes.length - 1) * 2));
            return (bytes[0] & 0x80) != 0 ? value.negate() : value;
        }

        /** AREA の次の空きの位置 (AREA の頭から)。管理域が 0 なら空である。 */
        int areaUsed() {
            int used = java.nio.ByteBuffer.wrap(view.toByteArray(), 0, 4).getInt();
            return used == 0 ? AREA_HEADER : used;
        }

        void areaUse(int used) {
            byte[] bytes = java.nio.ByteBuffer.allocate(4).putInt(used).array();
            for (int i = 0; i < 4; i++) view.set(i, bytes[i]);
        }

        /** VARYING の今の長さ。 */
        int currentLength() {
            int length = littleEndian ? ((view.get(1) & 0xFF) << 8) | (view.get(0) & 0xFF)
                    : ((view.get(0) & 0xFF) << 8) | (view.get(1) & 0xFF);
            if (length > precision) {
                throw new PliExecutionException("the current length " + length + " of " + name
                        + " exceeds its maximum length " + precision);
            }
            return length;
        }

        /** 文字として入る字の数。VARYING なら宣言の最大の長さ。 */
        int capacity() {
            return type == PliSyntax.Type.CHAR && varying ? precision : view.length();
        }

        void write(Object value, ProgramContext context) {
            if (complex) {
                // 実数を入れると虚部は 0 になる
                PliSyntax.Complex z = value instanceof PliSyntax.Complex c ? c
                        : new PliSyntax.Complex(Executor.number(value), BigDecimal.ZERO);
                part(0).write(z.real(), context);
                part(1).write(z.imaginary(), context);
                return;
            }
            if (type == PliSyntax.Type.AREA) {
                // AREA への代入は、割り当てた部分と管理域を写す (LRM "Area assignment")。
                // EMPTY() は空にする
                if (value == EMPTY_AREA) {
                    view.fill((byte) 0);
                    return;
                }
                if (!(value instanceof AreaImage image)) {
                    throw new PliExecutionException("only an AREA or EMPTY() can be assigned to"
                            + " the AREA " + name);
                }
                int used = java.nio.ByteBuffer.wrap(image.bytes(), 0, 4).getInt();
                int extent = used == 0 ? AREA_HEADER : used;
                if (extent > view.length()) {
                    throw new Condition("AREA", null, 360, null);
                }
                view.fill((byte) 0);
                for (int i = 0; i < extent; i++) view.set(i, image.bytes()[i]);
                return;
            }
            if (type == PliSyntax.Type.FLOAT) {
                floatWrite(Executor.number(value));
                return;
            }
            if (type == PliSyntax.Type.WIDECHAR) {
                // UTF-16 (big endian) で置き、足りない右を U+0020 で埋める (LRM "WIDECHAR")
                String text = Executor.display(value);
                byte[] bytes = new byte[view.length()];
                for (int i = 0; i < bytes.length / 2; i++) {
                    char c = i < text.length() ? text.charAt(i) : ' ';
                    bytes[2 * i] = (byte) (c >> 8);
                    bytes[2 * i + 1] = (byte) c;
                }
                view.setBytes(bytes);
                return;
            }
            if (type == PliSyntax.Type.GRAPHIC) {
                if (value instanceof PliGraphic graphic) {
                    view.setBytes(graphic.fit(view.length()));
                    return;
                }
                // SBCS の字は X'42' を前に付けた DBCS の字に、空白は X'4040' に直す
                // (LRM "Converting to GRAPHIC")。SBCS に無い字 (漢字) はまだ持たない
                String graphic = Executor.display(value);
                byte[] single = context.codePage().encode(graphic);
                if (!context.codePage().decode(single).equals(graphic)) {
                    throw new PliExecutionException("GRAPHIC data that is not in the SBCS code"
                            + " page is not supported yet: " + name);
                }
                byte[] bytes = new byte[view.length()];
                for (int i = 0; i < bytes.length / 2; i++) {
                    byte b = i < single.length ? single[i] : context.codePage().space();
                    boolean space = b == context.codePage().space();
                    bytes[2 * i] = (byte) (space ? 0x40 : 0x42);
                    bytes[2 * i + 1] = space ? (byte) 0x40 : b;
                }
                view.setBytes(bytes);
                return;
            }
            if (type == PliSyntax.Type.OFFSET) {
                byte[] bytes = java.nio.ByteBuffer.allocate(4)
                        .putInt(Executor.number(value).intValue()).array();
                for (int i = 0; i < 4; i++) view.set(i, bytes[i]);
                return;
            }
            if (type == PliSyntax.Type.CHAR && varying) {
                // 代入した値の長さが今の長さになる。最大を超えた右は落ちる (STRINGSIZE は既定で
                // 無効。LRM "Assignment to character strings")
                byte[] encoded = switch (value) {
                    case PliSyntax.HexString hex -> hex.bytes();
                    case MixedCharacters mixed -> mixed.bytes();
                    default -> context.codePage().encode(Executor.display(value));
                };
                if (STRINGSIZE_ENABLED.get() && encoded.length > precision) {
                    throw new Condition("STRINGSIZE", null, 150, null,
                            () -> writeVarying(encoded));
                }
                writeVarying(encoded);
                return;
            }
            switch (type) {
                case CHAR -> {
                    if (value instanceof PliSyntax.HexString hex) {
                        writeCharacters(hex.bytes(), context);
                    } else if (value instanceof MixedCharacters mixed) {
                        writeCharacters(mixed.bytes(), context);
                    } else {
                        writeText(Executor.display(value), context);
                    }
                }
                case BIT -> {
                    boolean numeric = Executor.numeric(value) && !(value instanceof String);
                    String text = numeric ? arithmeticToBits(value) : Executor.display(value);
                    if (!numeric && STRINGSIZE_ENABLED.get() && text.length() > precision) {
                        throw new Condition("STRINGSIZE", null, 150, null, () -> writeBits(text));
                    }
                    writeBits(text);
                }
                case PICTURE -> writeText(picture(value), context);
                case GROUP -> {
                    if (Executor.numeric(value) && Executor.number(value).signum() == 0) {
                        view.fill((byte) 0);
                    } else {
                        writeText(Executor.display(value), context);
                    }
                }
                case BINARY -> writeBinary(Executor.number(value));
                case DECIMAL -> {
                    BigDecimal number = Executor.number(value);
                    if (SIZE_ENABLED.get() && number.setScale(Math.max(scale, 0),
                            java.math.RoundingMode.DOWN).precision() - Math.max(scale, 0)
                                    > precision - scale && number.abs().compareTo(
                                    BigDecimal.TEN.pow(precision - scale)) >= 0) {
                        // 整数部が入りきらない。SIZE を有効にしていれば条件になる
                        throw new Condition("SIZE", null, 340, null);
                    }
                    view.setBytes(PackedDecimal.encode(
                            Decimal.parse(number.toPlainString()), precision, scale, true));
                }
                // POINTER へは POINTER の値 (ADDR や別の POINTER) だけが入る (LRM "Non-computational targets")
                case POINTER -> {
                    if (!(value instanceof PointerValue pointer)) {
                        throw new PliExecutionException("only a pointer can be assigned to "
                                + name + ": " + value);
                    }
                    view.setBytes(java.nio.ByteBuffer.allocate(4).putInt(pointer.address()).array());
                }
                case ENTRY -> {
                    if (!(value instanceof EntryValue entry) || view.length() < 4) {
                        throw new PliExecutionException("only an entry can be assigned to the"
                                + " ENTRY variable " + name);
                    }
                    view.setBytes(java.nio.ByteBuffer.allocate(view.length())
                            .putInt(entry.name() == null ? 0
                                    : AddressSpace.of(context).entryOf(entry.name())).array());
                }
                case FILE -> throw new PliExecutionException(
                        "assignment to " + type + " is not supported: " + name);
            }
        }

        void sqlShape(int[] shape, int offset) {
            switch (type) {
                case CHAR, BIT, PICTURE -> {
                    if (varying) {
                        // VARCHAR の host variable はまだ持たない。固定長として渡すと長さの半語が
                        // 字として DB に入る
                        throw new PliExecutionException("a VARYING string cannot be an SQL host"
                                + " variable yet: " + name);
                    }
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
            view.setBytes(littleEndian ? reversed(bytes) : bytes);
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
         * 算術の値をビット列にする (LRM "Arithmetic to bit-string conversion")。整数部の絶対値を
         * 2 進にし、長さは FIXED BINARY にしたときの精度である。10 進の p 桁は
         * {@code ceil(p * 3.32)} ビット (31 まで) になる。{@code B = 5} は FIXED DEC(1) なので
         * 4 ビットの '0101' であり、BIT(8) へは右を 0 で埋めて '01010000' になる。
         */
        private static String arithmeticToBits(Object value) {
            BigDecimal number = Executor.number(value).abs().setScale(0, java.math.RoundingMode.DOWN);
            int bits;
            if (value instanceof FixedValue fixed) {
                bits = fixed.binary() ? fixed.precision()
                        : Math.min((int) Math.ceil((fixed.precision() - fixed.scale()) * 3.32), 31);
            } else {
                bits = Math.max(1, number.toBigInteger().bitLength());
            }
            String text = number.toBigInteger().toString(2);
            if (text.length() > bits) {
                text = text.substring(text.length() - bits);
            }
            return "0".repeat(bits - text.length()) + text;
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
            if (picture != null) {
                BigDecimal number = Executor.number(value);
                if (number.signum() < 0 && !picture.signed()) {
                    throw new PliExecutionException("a negative value cannot be stored in the"
                            + " unsigned PICTURE of " + name + " yet: " + number);
                }
                PliPicture.Edited edited = picture.isFloat() ? picture.editFloat(number)
                        : picture.edit(number);
                if (edited.lost()) {
                    throw new PliExecutionException("SIZE: " + number + " does not fit the"
                            + " PICTURE of " + name);
                }
                return edited.text();
            }
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
            writeCharacters(context.codePage().encode(value), context);
        }

        private void writeCharacters(byte[] encoded, ProgramContext context) {
            if (type == PliSyntax.Type.CHAR && STRINGSIZE_ENABLED.get()
                    && encoded.length > view.length()) {
                throw new Condition("STRINGSIZE", null, 150, null,
                        () -> storeCharacters(encoded, context));
            }
            storeCharacters(encoded, context);
        }

        private void writeVarying(byte[] encoded) {
            int length = Math.min(encoded.length, precision);
            view.set(littleEndian ? 1 : 0, (byte) (length >> 8));
            view.set(littleEndian ? 0 : 1, (byte) length);
            for (int i = 0; i < length; i++) view.set(2 + i, encoded[i]);
        }

        private void storeCharacters(byte[] encoded, ProgramContext context) {
            view.fill(context.codePage().space());
            int length = Math.min(encoded.length, view.length());
            for (int i = 0; i < length; i++) view.set(i, encoded[i]);
        }
    }

    /**
     * POINTER の値。記憶域の位置に AddressSpace が振った番号で、0 は何も指さない (P-150)。
     */
    /**
     * STATIC の記憶域。実行単位 (特殊レジスタの置き場で見分ける。AddressSpace と同じ) ごとに、
     * 「プログラム名.手続きの道.変数名」から宣言の束縛への表。
     */
    private static final Map<Object, Map<String, Map<String, Var>>> STATICS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** RELEASE したプログラムの STATIC を捨てる。 */
    private static void releaseStatics(ProgramContext context, String program) {
        Map<String, Map<String, Var>> statics = STATICS.get(context.registers());
        if (statics != null) {
            String prefix = program.toUpperCase(Locale.ROOT) + ".";
            statics.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    /** 入口の値 (内部手続きの名)。 */
    private record EntryValue(String name) {
    }

    /** AREA の管理域の大きさ。割り当ては管理域の後ろから始まる。 */
    static final int AREA_HEADER = 16;

    /** AREA の値 (記憶域の写し)。AREA への代入に使う。 */
    private record AreaImage(byte[] bytes) {
    }

    /** EMPTY() の値。 */
    private static final Object EMPTY_AREA = new Object();

    /** NULL() の値 X'FF000000'。 */
    private static final int NULL_ADDRESS = 0xFF000000;

    private record PointerValue(int address) {
    }

    /**
     * 数の PICTURE の値。字の形を持ち、演算に使うときだけ FIXED DEC(p,q) に直す。出力は字を
     * 送るだけなので、数字でない字が入っていても書ける。
     */
    private record WideCharacter(char unit) {
    }

    private record MixedCharacters(byte[] bytes, dev.cobolonjava.runtime.codepage.CodePage page) {
    }

    private record NumericPicture(String text, int precision, int scale, PliPicture picture) {
        FixedValue fixed() {
            if (picture == null) {
                return FixedValue.decimal(Executor.number(text).movePointLeft(scale), precision,
                        scale);
            }
            BigDecimal value;
            try {
                value = picture.value(text);
            } catch (UnsupportedOperationException unsupported) {
                throw new PliExecutionException(unsupported.getMessage());
            }
            if (value == null) {
                // 像が PICTURE に合わない。実機では CONVERSION になる
                throw new Condition("CONVERSION", null, 600, text);
            }
            return FixedValue.decimal(value, precision, scale);
        }
    }

    private static final class ReturnSignal extends RuntimeException {
        static final ReturnSignal INSTANCE = new ReturnSignal();

        private ReturnSignal() {
            super(null, null, false, false);
        }
    }

    private static final class StopSignal extends RuntimeException {
        static final StopSignal INSTANCE = new StopSignal();

        private StopSignal() {
            super(null, null, false, false);
        }
    }

    /** LEAVE (出る) と ITERATE (次の繰り返しへ)。 */
    private static final class LoopSignal extends RuntimeException {
        final boolean leave;
        final String label;

        LoopSignal(boolean leave, String label) {
            super(null, null, false, false);
            this.leave = leave;
            this.label = label;
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
