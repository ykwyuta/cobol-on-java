package dev.cobolonjava.compiler.codegen;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.semantic.Condition;
import dev.cobolonjava.compiler.semantic.DataCategory;
import dev.cobolonjava.compiler.semantic.DataItem;
import dev.cobolonjava.compiler.semantic.DataReference;
import dev.cobolonjava.compiler.semantic.DataSection;
import dev.cobolonjava.compiler.semantic.Expression;
import dev.cobolonjava.compiler.semantic.IntermediateDigits;
import dev.cobolonjava.compiler.semantic.InitialImage;
import dev.cobolonjava.compiler.semantic.InitializeImage;
import dev.cobolonjava.compiler.semantic.LiteralValue;
import dev.cobolonjava.compiler.semantic.MoveRules;
import dev.cobolonjava.compiler.semantic.Operand;
import dev.cobolonjava.compiler.semantic.ProcedureBuilder;
import dev.cobolonjava.compiler.semantic.SpecialNames;
import dev.cobolonjava.compiler.semantic.Statement;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.decimal.DecimalDivideException;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.picture.PictureParser;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import dev.cobolonjava.runtime.verb.InspectScan;
import dev.cobolonjava.runtime.verb.Region;
import dev.cobolonjava.runtime.verb.StringVerb;
import dev.cobolonjava.runtime.verb.UnstringVerb;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 文の並びから JVM バイトコードを生成する (方針 ARC-3, ARC-7)。
 *
 * <p>生成するクラスは {@link CobolProgram} を実装し、記憶域の位置を決めて
 * {@link Ops} を呼ぶだけである。<b>意味論はランタイムが持つ</b>ため、
 * 生成コードの誤りと意味論の誤りを切り分けられる。V2 で裏付けを取ったのは
 * ランタイムであり、その経路をそのまま通す。
 *
 * <h2>翻訳時に決まるものは定数フィールドに置く</h2>
 * <p>PICTURE の解析、{@code NumericItem} の組み立て、文字定数の符号化は
 * <b>いずれも翻訳時に決まる</b>。静的初期化子で 1 度だけ作り、
 * 手続きの中では参照するだけにする。バイト列はクラスファイルの文字列定数から
 * {@link ProgramSupport#bytes} で復元する — ISO-8859-1 が 0〜255 をそのまま
 * 写すので、任意のバイト列を文字列 1 個で運べる。
 *
 * <h2>位置が実行時に決まる参照はまだ生成しない</h2>
 * <p>データ項目で書いた添字は実行時に位置が決まる。いまは誤りとして報告する
 * (暫定判断 P-027)。
 */
public final class ProgramGenerator {

    private static final String DATA_VIEW = Type.getInternalName(DataView.class);
    private static final String OPS = Type.getInternalName(Ops.class);
    private static final String SUPPORT = Type.getInternalName(ProgramSupport.class);
    private static final String STORAGE = Type.getInternalName(Storage.class);
    private static final String CODE_PAGE = Type.getDescriptor(CodePage.class);
    private static final String NUMERIC_ITEM = Type.getDescriptor(NumericItem.class);
    private static final String PICTURE = Type.getDescriptor(Picture.class);
    private static final String DECIMAL = Type.getDescriptor(Decimal.class);
    private static final String CLAUSE = Type.getDescriptor(InspectScan.Clause.class);
    private static final String REGION = Type.getDescriptor(Region.class);

    private final String className;
    private final CodePage codePage;
    /** {@code SSRANGE} が効いているか。効いていれば添字と部分参照の位置を実行時に検査する。 */
    private final boolean rangeChecks;
    private final SpecialNames specialNames;
    /** PICTURE の通貨記号。{@code CURRENCY SIGN IS} で差し替えられる。 */
    private char currency = SpecialNames.DEFAULT_CURRENCY;
    /** {@code PROCEDURE DIVISION USING} に並べた 01 レベル。連絡節の位置決めに使う。 */
    private List<DataItem> parameters = List.of();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /** 静的初期化子で作る定数。綴りから field 名を引く。 */
    private final Map<String, Constant> constants = new LinkedHashMap<>();

    /**
     * 局所変数の 0 番は {@code this}、1 番は記憶域、2 番は実行時の入口、3 番は引数の並びである。
     */
    private static final int FIRST_FREE_LOCAL = 4;

    /** 記憶域・文脈・引数。手続き部を実行するメソッドはどれもこの 3 つを持ち回る。 */
    private static final String FRAME =
            "L" + Type.getInternalName(Storage.class) + ";"
                    + Type.getDescriptor(ProgramContext.class)
                    + "[" + Type.getDescriptor(DataView.class);
    /** 段落のメソッド。次にどこへ行くかを返す。 */
    private static final String PARAGRAPH_DESCRIPTOR = "(" + FRAME + ")I";
    /** {@code dispatch(段落の番号, 記憶域, 文脈, 引数)}。 */
    private static final String DISPATCH_DESCRIPTOR = "(I" + FRAME + ")I";
    /** {@code performRange(最初, 最後, 記憶域, 文脈, 引数)}。 */
    private static final String PERFORM_DESCRIPTOR = "(II" + FRAME + ")V";
    /** 引数の並びが入る局所変数。0 が this、1 が記憶域、2 が文脈である。 */
    private static final int ARGUMENTS_LOCAL = 3;
    private static final String RUN_DESCRIPTOR = "(" + FRAME + ")V";

    private ClassWriter writer;
    private String internal;
    private List<String> paragraphNames = new ArrayList<>();
    private int nextLocal = FIRST_FREE_LOCAL;
    private MethodVisitor run;
    private MethodVisitor clinit;
    private byte[] initialStorageBytes;

    private ProgramGenerator(String className, CodePage codePage, CompilerOptions options,
                             SpecialNames specialNames) {
        this.className = className;
        this.codePage = codePage;
        this.rangeChecks = options.subscriptRangeChecks();
        this.specialNames = specialNames;
    }

    private record Constant(String name, String descriptor, Runnable emit) {
    }

    /**
     * 生成の結果。
     *
     * @param className   生成したクラスの名前
     * @param classFile   クラスファイルの中身
     * @param diagnostics 見つかった誤り。空なら成功
     */
    public record Result(String className, byte[] classFile, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }
    }

    /** プログラムを生成する。 */
    public static Result generate(String programName, ProcedureBuilder.Result procedure,
                                  InitialImage.Result image) {
        return generate(programName, procedure, image, CompilerOptions.NONE,
                SpecialNames.standard());
    }

    /** 翻訳時オプションを指定してプログラムを生成する。 */
    public static Result generate(String programName, ProcedureBuilder.Result procedure,
                                  InitialImage.Result image, CompilerOptions options) {
        return generate(programName, procedure, image, options, SpecialNames.standard());
    }

    /** 翻訳時オプションと環境部の指定を与えてプログラムを生成する。 */
    public static Result generate(String programName, ProcedureBuilder.Result procedure,
                                  InitialImage.Result image, CompilerOptions options,
                                  SpecialNames specialNames) {
        return generate(programName, procedure, image, CodePages.DEFAULT, options, specialNames);
    }

    /** コードページまで指定してプログラムを生成する。 */
    public static Result generate(String programName, ProcedureBuilder.Result procedure,
                                  InitialImage.Result image, CodePage codePage,
                                  CompilerOptions options, SpecialNames specialNames) {
        return new ProgramGenerator(classNameOf(programName), codePage, options, specialNames)
                .emit(procedure, image);
    }

    /** COBOL のプログラム名を Java のクラス名にする。ハイフンは下線に読み替える。 */
    public static String classNameOf(String programName) {
        // 規則はランタイムに置いてある。CALL で名前から探すのはそちらであり、
        // 両者がずれれば呼び先が見つからない
        return ProgramSupport.classNameOf(programName);
    }

    private Result emit(ProcedureBuilder.Result procedure, InitialImage.Result image) {
        parameters = procedure.parameters();
        currency = specialNames.currency();
        writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        internal = className.replace('.', '/');
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internal, null, "java/lang/Object",
                new String[] {Type.getInternalName(CobolProgram.class)});

        emitConstructor(writer, internal);
        emitInitialStorage(image.storage());
        List<List<Runnable>> paragraphs = planParagraphs(procedure);
        if (!diagnostics.isEmpty()) {
            return new Result(className, null, List.copyOf(diagnostics));
        }
        emitRun(paragraphs.size());
        emitMain();
        if (!paragraphs.isEmpty()) {
            emitDispatch(paragraphs.size());
            emitPerformMethod(paragraphs.size());
        }
        for (int i = 0; i < paragraphs.size(); i++) {
            emitParagraph(i, paragraphs.get(i));
        }
        emitStaticInitializer();
        writer.visitEnd();
        return new Result(className, writer.toByteArray(), List.of());
    }

    private static void emitConstructor(ClassWriter writer, String internal) {
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    /** 初期イメージは複製して返す。実行のたびに書き換えられるためである。 */
    private void emitInitialStorage(byte[] storage) {
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "INITIAL", "[B", null, null).visitEnd();
        constants.put("\0initial", new Constant("INITIAL", "[B", null));

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "initialStorage", "()[B",
                null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, internal, "INITIAL", "[B");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[B", "clone", "()Ljava/lang/Object;", false);
        method.visitTypeInsn(Opcodes.CHECKCAST, "[B");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        initialStorageBytes = storage;
    }

    // ---- 手続き ----

    /**
     * 文の並びを、あとで書き出す命令の並びへ変えつつ、必要な定数を登録する。
     * 誤りが見つかったらクラスファイルは作らない。
     */
    /**
     * 段落ごとに命令の並びを作る。
     *
     * <p>段落を別のメソッドにするのは {@code PERFORM} のためである。素直に流れるときは
     * 上から順に呼び、{@code PERFORM P} は P のメソッドだけを呼ぶ。
     * <b>{@code PERFORM} は次の段落へ流れ込まない</b>という規則が、これで自然に出る。
     */
    private List<List<Runnable>> planParagraphs(ProcedureBuilder.Result procedure) {
        paragraphNames = new ArrayList<>();
        for (ProcedureBuilder.Paragraph paragraph : procedure.paragraphs()) {
            paragraphNames.add(paragraph.name());
        }
        List<List<Runnable>> planned = new ArrayList<>();
        for (ProcedureBuilder.Paragraph paragraph : procedure.paragraphs()) {
            nextLocal = FIRST_FREE_LOCAL;
            planned.add(planStatements(paragraph.statements()));
        }
        return planned;
    }

    private List<Runnable> planStatements(List<Statement> statements) {
        List<Runnable> body = new ArrayList<>();
        for (Statement statement : statements) {
            if (statement instanceof Statement.Sequence sequence) {
                // 意味解析で展開された文の並び。そのまま並べて出す
                body.addAll(planStatements(sequence.statements()));
            } else if (statement instanceof Statement.Move move) {
                planMove(move, body);
            } else if (statement instanceof Statement.Arithmetic arithmetic) {
                planArithmetic(arithmetic, body);
            } else if (statement instanceof Statement.ArithmeticGroup group) {
                planArithmeticGroup(group, body);
            } else if (statement instanceof Statement.DivideRemainder divide) {
                planDivideRemainder(divide, body);
            } else if (statement instanceof Statement.Compute compute) {
                planCompute(compute, body);
            } else if (statement instanceof Statement.If branch) {
                planIf(branch, body);
            } else if (statement instanceof Statement.Perform perform) {
                planPerform(perform, body);
            } else if (statement instanceof Statement.Display display) {
                planDisplay(display, body);
            } else if (statement instanceof Statement.StringStatement text) {
                planString(text, body);
            } else if (statement instanceof Statement.Unstring unstring) {
                planUnstring(unstring, body);
            } else if (statement instanceof Statement.Inspect inspect) {
                planInspect(inspect, body);
            } else if (statement instanceof Statement.Stop stop) {
                // STOP RUN は実行そのものを終え、GOBACK は呼んだ側へ戻る
                String name = stop.wholeRun() ? "stopRun" : "programReturn";
                body.add(() -> run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, name, "()V", false));
            } else if (statement instanceof Statement.Search search) {
                planSearch(search, body);
            } else if (statement instanceof Statement.SearchAll searchAll) {
                planSearchAll(searchAll, body);
            } else if (statement instanceof Statement.Accept accept) {
                planAccept(accept, body);
            } else if (statement instanceof Statement.Initialize initialize) {
                planInitialize(initialize, body);
            } else if (statement instanceof Statement.Call call) {
                planCall(call, body);
            } else if (statement instanceof Statement.Cancel cancel) {
                planCancel(cancel, body);
            } else if (statement instanceof Statement.GoTo goTo) {
                planGoTo(goTo, body);
            } else if (statement instanceof Statement.Continue) {
                // 何もしない文である
                continue;
            } else {
                report(statement.origin(), "statement is not supported by the generator yet");
            }
        }
        return body;
    }

    /**
     * {@code DISPLAY} を組み立てる。
     *
     * <p>被演算子は並べて出し、行を改めるのは<b>最後の 1 個だけ</b>である。
     * 途中で改めると、1 つの {@code DISPLAY} が複数行になってしまう。
     */
    private void planDisplay(Statement.Display statement, List<Runnable> body) {
        List<Runnable> parts = new ArrayList<>();
        for (Operand operand : statement.operands()) {
            Runnable bytes = planDisplayBytes(operand, statement.origin());
            if (bytes == null) {
                return;
            }
            parts.add(bytes);
        }
        boolean toError = statement.upon() == SpecialNames.FunctionName.SYSERR;
        body.add(() -> {
            for (int i = 0; i < parts.size(); i++) {
                parts.get(i).run();
                run.visitVarInsn(Opcodes.ALOAD, 2);
                boolean last = i == parts.size() - 1;
                run.visitInsn(last && statement.advancing() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                run.visitInsn(toError ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "display",
                        "([B" + Type.getDescriptor(ProgramContext.class) + "ZZ)V", false);
            }
        });
    }

    /**
     * {@code DISPLAY} が出すバイト列。
     *
     * <p>{@code COMP} や {@code COMP-3} の項目をそのまま出しても読めないため、
     * 同じ桁数・同じ小数部の {@code DISPLAY} 項目として符号化し直す。
     */
    private Runnable planDisplayBytes(Operand operand, Origin origin) {
        if (operand instanceof Operand.Reference reference
                && needsDisplayConversion(reference.reference())) {
            Runnable value = planSourceDecimal(operand, origin);
            DataItem item = reference.reference().item();
            String shape = displayShapeConstant(item, origin);
            if (value == null || shape == null) {
                return null;
            }
            return () -> {
                value.run();
                run.visitFieldInsn(Opcodes.GETSTATIC, internal, shape, NUMERIC_ITEM);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "displayForm",
                        "(" + DECIMAL + NUMERIC_ITEM + ")[B", false);
            };
        }
        int length = operand instanceof Operand.Reference reference
                ? reference.reference().constantLength().orElse(0)
                : 0;
        return planSourceBytes(operand, origin, length);
    }

    /** 記憶域の形のままでは読めない項目かどうか。 */
    private static boolean needsDisplayConversion(DataReference reference) {
        if (reference.refMod() != null || !DataCategory.of(reference).isNumeric()) {
            return false;
        }
        Usage usage = reference.item().usage();
        return usage != null && usage != Usage.DISPLAY;
    }

    /** 同じ桁数・同じ小数部の {@code DISPLAY} 項目。表示の形へ直すために使う。 */
    private String displayShapeConstant(DataItem item, Origin origin) {
        if (item.picture() == null) {
            report(origin, "DISPLAY of a floating-point item is not supported yet");
            return null;
        }
        String key = "S:" + item.picture().source();
        return constants.computeIfAbsent(key, k -> {
            String name = "S" + constants.size();
            return new Constant(name, NUMERIC_ITEM, () -> {
                clinit.visitLdcInsn(item.picture().source());
                clinit.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(Usage.class),
                        Usage.DISPLAY.name(), Type.getDescriptor(Usage.class));
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(NumericItem.class), "of",
                        "(Ljava/lang/String;" + Type.getDescriptor(Usage.class) + ")"
                                + NUMERIC_ITEM, false);
            });
        }).name();
    }

    // ---- STRING / UNSTRING ----

    /**
     * {@code STRING} を組み立てる。
     *
     * <p>結果は<b>あふれたかどうか</b>と<b>次に書く位置</b>を持つ。どちらも実行してみないと
     * 分からないため、結果を局所変数へ取ってから使う。
     */
    private void planString(Statement.StringStatement statement, List<Runnable> body) {
        Runnable offset = planAddress(statement.target(), statement.origin());
        OptionalInt length = lengthOf(statement.target(), statement.origin());
        if (offset == null || length.isEmpty()) {
            return;
        }
        Runnable pointer = planPointerValue(statement.pointer(), statement.origin());
        if (pointer == null) {
            return;
        }

        List<Runnable> sources = new ArrayList<>();
        for (Statement.StringStatement.StringSource source : statement.sources()) {
            for (Operand value : source.values()) {
                Runnable planned = planStringSource(value, source.delimiter(), statement.origin());
                if (planned == null) {
                    return;
                }
                sources.add(planned);
            }
        }

        int result = nextLocal++;
        Runnable storePointer = planStoreResultInt(statement.pointer(), result,
                Type.getInternalName(StringVerb.Result.class), "pointer", statement.origin());
        if (storePointer == null) {
            return;
        }
        List<Runnable> onOverflow = planStatements(overflowOf(statement.overflow(), true));
        List<Runnable> otherwise = planStatements(overflowOf(statement.overflow(), false));

        body.add(() -> {
            offset.run();
            push(length.getAsInt());
            pointer.run();
            emitArray(sources, Type.getInternalName(StringVerb.Source.class));
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "string",
                    "(L" + STORAGE + ";III[" + Type.getDescriptor(StringVerb.Source.class) + ")"
                            + Type.getDescriptor(StringVerb.Result.class), false);
            run.visitVarInsn(Opcodes.ASTORE, result);
            storePointer.run();
            emitOverflowBranch(statement.overflow(), result,
                    Type.getInternalName(StringVerb.Result.class), onOverflow, otherwise);
        });
    }

    private Runnable planStringSource(Operand value, Operand delimiter, Origin origin) {
        Runnable bytes = planInspectBytes(value, origin);
        if (bytes == null) {
            return null;
        }
        if (delimiter == null) {
            return () -> {
                bytes.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(StringVerb.Source.class), "bySize",
                        "([B)" + Type.getDescriptor(StringVerb.Source.class), false);
            };
        }
        Runnable delimiterBytes = planInspectBytes(delimiter, origin);
        if (delimiterBytes == null) {
            return null;
        }
        return () -> {
            bytes.run();
            delimiterBytes.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(StringVerb.Source.class), "delimitedBy",
                    "([B[B)" + Type.getDescriptor(StringVerb.Source.class), false);
        };
    }

    private void planUnstring(Statement.Unstring statement, List<Runnable> body) {
        Runnable offset = planAddress(statement.source(), statement.origin());
        OptionalInt length = lengthOf(statement.source(), statement.origin());
        if (offset == null || length.isEmpty()) {
            return;
        }
        Runnable pointer = planPointerValue(statement.pointer(), statement.origin());
        if (pointer == null) {
            return;
        }

        List<Runnable> delimiters = new ArrayList<>();
        for (Statement.Unstring.UnstringDelimiter delimiter : statement.delimiters()) {
            Runnable bytes = planInspectBytes(delimiter.value(), statement.origin());
            if (bytes == null) {
                return;
            }
            String factory = delimiter.all() ? "all" : "of";
            delimiters.add(() -> {
                bytes.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(UnstringVerb.Delimiter.class), factory,
                        "([B)" + Type.getDescriptor(UnstringVerb.Delimiter.class), false);
            });
        }

        List<Runnable> fields = new ArrayList<>();
        for (Statement.Unstring.UnstringTarget target : statement.targets()) {
            OptionalInt fieldLength = lengthOf(target.field(), statement.origin());
            if (fieldLength.isEmpty()) {
                return;
            }
            int size = fieldLength.getAsInt();
            fields.add(() -> {
                push(size);
                run.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(UnstringVerb.Field.class), "of",
                        "(I)" + Type.getDescriptor(UnstringVerb.Field.class), false);
            });
        }

        int result = nextLocal++;
        List<Runnable> stores = new ArrayList<>();
        for (int i = 0; i < statement.targets().size(); i++) {
            Runnable store = planUnstringTarget(statement.targets().get(i), i, result,
                    statement.origin());
            if (store == null) {
                return;
            }
            stores.add(store);
        }
        String resultType = Type.getInternalName(UnstringVerb.Result.class);
        Runnable storePointer = planStoreResultInt(statement.pointer(), result, resultType,
                "pointer", statement.origin());
        Runnable storeTallying = planStoreResultInt(statement.tallying(), result, resultType,
                "tallying", statement.origin());
        if (storePointer == null || storeTallying == null) {
            return;
        }
        List<Runnable> onOverflow = planStatements(overflowOf(statement.overflow(), true));
        List<Runnable> otherwise = planStatements(overflowOf(statement.overflow(), false));

        body.add(() -> {
            offset.run();
            push(length.getAsInt());
            pointer.run();
            emitArray(delimiters, Type.getInternalName(UnstringVerb.Delimiter.class));
            emitArray(fields, Type.getInternalName(UnstringVerb.Field.class));
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "unstring",
                    "(L" + STORAGE + ";III[" + Type.getDescriptor(UnstringVerb.Delimiter.class)
                            + "[" + Type.getDescriptor(UnstringVerb.Field.class) + CODE_PAGE + ")"
                            + Type.getDescriptor(UnstringVerb.Result.class), false);
            run.visitVarInsn(Opcodes.ASTORE, result);
            stores.forEach(Runnable::run);
            storePointer.run();
            storeTallying.run();
            emitOverflowBranch(statement.overflow(), result, resultType, onOverflow, otherwise);
        });
    }

    private Runnable planUnstringTarget(Statement.Unstring.UnstringTarget target, int index,
                                        int result, Origin origin) {
        Runnable fieldOffset = planAddress(target.field(), origin);
        OptionalInt fieldLength = lengthOf(target.field(), origin);
        if (fieldOffset == null || fieldLength.isEmpty()) {
            return null;
        }
        String resultType = Type.getInternalName(UnstringVerb.Result.class);

        Runnable delimiter = null;
        if (target.delimiter() != null) {
            Runnable at = planAddress(target.delimiter(), origin);
            OptionalInt size = lengthOf(target.delimiter(), origin);
            if (at == null || size.isEmpty()) {
                return null;
            }
            boolean justified = target.delimiter().item().justified();
            delimiter = () -> {
                run.visitVarInsn(Opcodes.ALOAD, result);
                push(index);
                at.run();
                push(size.getAsInt());
                run.visitInsn(justified ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeUnstringDelimiter",
                        "(L" + resultType + ";IL" + STORAGE + ";IIZ" + CODE_PAGE + ")V", false);
            };
        }

        Runnable count = null;
        if (target.count() != null) {
            Runnable at = planAddress(target.count(), origin);
            String field = numericItemConstant(target.count().item(), origin);
            if (at == null || field == null) {
                return null;
            }
            count = () -> {
                run.visitVarInsn(Opcodes.ALOAD, result);
                push(index);
                run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
                at.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeUnstringCount",
                        "(L" + resultType + ";I" + NUMERIC_ITEM + "L" + STORAGE + ";I)V", false);
            };
        }

        Runnable writeDelimiter = delimiter;
        Runnable writeCount = count;
        return () -> {
            run.visitVarInsn(Opcodes.ALOAD, result);
            push(index);
            fieldOffset.run();
            push(fieldLength.getAsInt());
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeUnstringField",
                    "(L" + resultType + ";IL" + STORAGE + ";II)V", false);
            if (writeDelimiter != null) {
                writeDelimiter.run();
            }
            if (writeCount != null) {
                writeCount.run();
            }
        };
    }

    /** {@code WITH POINTER} の現在値。指定がなければ 1 から書き始める。 */
    private Runnable planPointerValue(DataReference pointer, Origin origin) {
        if (pointer == null) {
            return () -> push(1);
        }
        Runnable value = planSourceDecimal(new Operand.Reference(pointer), origin);
        if (value == null) {
            return null;
        }
        return () -> {
            value.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
        };
    }

    /** 実行結果の整数を項目へ書き戻す命令。書き戻す先がなければ何もしない。 */
    private Runnable planStoreResultInt(DataReference target, int result, String resultType,
                                        String accessor, Origin origin) {
        if (target == null) {
            return () -> { };
        }
        Runnable offset = planAddress(target, origin);
        String field = numericItemConstant(target.item(), origin);
        if (offset == null || field == null) {
            return null;
        }
        return () -> {
            run.visitVarInsn(Opcodes.ALOAD, result);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, resultType, accessor, "()I", false);
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            offset.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeInteger",
                    "(I" + NUMERIC_ITEM + "L" + STORAGE + ";I)V", false);
        };
    }

    private static List<Statement> overflowOf(Statement.Overflow overflow, boolean onOverflow) {
        if (overflow == null) {
            return List.of();
        }
        return onOverflow ? overflow.onOverflow() : overflow.otherwise();
    }

    private void emitOverflowBranch(Statement.Overflow overflow, int result, String resultType,
                                    List<Runnable> onOverflow, List<Runnable> otherwise) {
        if (overflow == null) {
            return;
        }
        Label noOverflow = new Label();
        Label end = new Label();
        run.visitVarInsn(Opcodes.ALOAD, result);
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, resultType, "overflow", "()Z", false);
        run.visitJumpInsn(Opcodes.IFEQ, noOverflow);
        onOverflow.forEach(Runnable::run);
        run.visitJumpInsn(Opcodes.GOTO, end);
        run.visitLabel(noOverflow);
        otherwise.forEach(Runnable::run);
        run.visitLabel(end);
    }

    /**
     * {@code SEARCH} を組み立てる (要件 FR-066)。
     *
     * <p>形は素直な繰り返しである。<b>指標を初期化しない</b>のが要で、どこから見はじめるかは
     * 直前の {@code SET} が決める。すでに範囲の外なら一度も見ずに {@code AT END} へ行く。
     *
     * <pre>
     * 先頭:  指標 &gt; 回数 ならば 終わり へ
     *        条件1 が成り立てば 当たり1 へ
     *        条件2 が成り立てば 当たり2 へ
     *        指標 = 指標 + 1
     *        先頭 へ
     * 終わり: AT END の文
     *        出口 へ
     * 当たり1: その文 ; 出口 へ
     * 当たり2: その文 ; 出口 へ
     * 出口:
     * </pre>
     *
     * <p>当たったところで<b>繰り返しから抜ける</b>。{@code PERFORM} の形では書けないのは
     * ここであり、飛び先を直に置いている。
     */
    private void planSearch(Statement.Search statement, List<Runnable> body) {
        Runnable step = planIndexStep(statement.index(), statement.origin());
        if (step == null) {
            return;
        }
        Runnable stepVarying = null;
        if (statement.varying() != null) {
            stepVarying = planIndexStep(statement.varying(), statement.origin());
            if (stepVarying == null) {
                return;
            }
        }
        Runnable limit = planSourceDecimal(new Operand.Reference(statement.index()),
                statement.origin());
        if (limit == null) {
            return;
        }

        List<Runnable> atEnd = planStatements(statement.atEnd());
        List<List<Runnable>> matched = new ArrayList<>();
        for (Statement.Search.When when : statement.whens()) {
            matched.add(planStatements(when.statements()));
        }
        Runnable advance = stepVarying;
        body.add(() -> {
            Label top = new Label();
            Label exhausted = new Label();
            Label done = new Label();
            Label[] hit = new Label[statement.whens().size()];
            for (int i = 0; i < hit.length; i++) {
                hit[i] = new Label();
            }

            run.visitLabel(top);
            // 指標が回数を超えていたら、そこで終わりである
            limit.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
            push(statement.occurs());
            run.visitJumpInsn(Opcodes.IF_ICMPGT, exhausted);
            for (int i = 0; i < hit.length; i++) {
                emitCondition(statement.whens().get(i).condition(), hit[i], true);
            }
            step.run();
            if (advance != null) {
                advance.run();
            }
            run.visitJumpInsn(Opcodes.GOTO, top);

            run.visitLabel(exhausted);
            atEnd.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, done);
            for (int i = 0; i < hit.length; i++) {
                run.visitLabel(hit[i]);
                matched.get(i).forEach(Runnable::run);
                run.visitJumpInsn(Opcodes.GOTO, done);
            }
            run.visitLabel(done);
        });
    }

    /**
     * {@code SEARCH ALL} を組み立てる (要件 FR-066)。
     *
     * <p>2 分探索である。<b>指標は使う側が用意しなくてよい</b>。探索そのものが範囲を
     * 狭めながら指標を決める。
     *
     * <pre>
     * 下 = 1 ; 上 = 回数
     * 先頭:   下 &gt; 上 ならば 終わり へ
     *         指標 = (下 + 上) / 2
     *         鍵1 を比べる。小さければ 手前 へ、大きければ 奥 へ
     *         鍵2 を比べる。… (すべて等しければ落ちる)
     *         当たりの文 ; 出口 へ
     * 手前:   上 = 指標 - 1 ; 先頭 へ
     * 奥:     下 = 指標 + 1 ; 先頭 へ
     * 終わり: AT END の文
     * 出口:
     * </pre>
     *
     * <p>「手前」と「奥」のどちらへ行くかは<b>鍵の向きで入れ替わる</b>。昇順なら鍵が
     * 小さいときに奥を、降順なら手前を見る。捨てる半分が逆になる。
     */
    private void planSearchAll(Statement.SearchAll statement, List<Runnable> body) {
        Runnable store = planStoreIndex(statement.index(), statement.origin());
        if (store == null) {
            return;
        }
        List<Runnable> comparisons = new ArrayList<>();
        for (Statement.SearchAll.KeyTest key : statement.keys()) {
            Runnable comparison = planComparison(key.test());
            if (comparison == null) {
                return;
            }
            comparisons.add(comparison);
        }
        List<Runnable> atEnd = planStatements(statement.atEnd());
        List<Runnable> matched = planStatements(statement.whenStatements());

        int low = nextLocal++;
        int high = nextLocal++;
        int compared = nextLocal++;
        body.add(() -> {
            Label top = new Label();
            Label lower = new Label();
            Label upper = new Label();
            Label exhausted = new Label();
            Label done = new Label();

            run.visitInsn(Opcodes.ICONST_1);
            run.visitVarInsn(Opcodes.ISTORE, low);
            push(statement.occurs());
            run.visitVarInsn(Opcodes.ISTORE, high);

            run.visitLabel(top);
            run.visitVarInsn(Opcodes.ILOAD, low);
            run.visitVarInsn(Opcodes.ILOAD, high);
            run.visitJumpInsn(Opcodes.IF_ICMPGT, exhausted);
            // 指標を真ん中へ置く。鍵の添字がこれを読む
            run.visitVarInsn(Opcodes.ILOAD, low);
            run.visitVarInsn(Opcodes.ILOAD, high);
            run.visitInsn(Opcodes.IADD);
            run.visitInsn(Opcodes.ICONST_2);
            run.visitInsn(Opcodes.IDIV);
            store.run();

            for (int i = 0; i < comparisons.size(); i++) {
                boolean ascending = statement.keys().get(i).ascending();
                // 比較の値は局所変数へ取る。飛び先ごとに作用対象の深さが変わらないようにする
                comparisons.get(i).run();
                run.visitVarInsn(Opcodes.ISTORE, compared);
                run.visitVarInsn(Opcodes.ILOAD, compared);
                run.visitJumpInsn(Opcodes.IFLT, ascending ? upper : lower);
                run.visitVarInsn(Opcodes.ILOAD, compared);
                run.visitJumpInsn(Opcodes.IFGT, ascending ? lower : upper);
            }
            matched.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, done);

            // 前半分を捨てる
            run.visitLabel(upper);
            emitHalf(low, high, true);
            run.visitJumpInsn(Opcodes.GOTO, top);
            // 後ろ半分を捨てる
            run.visitLabel(lower);
            emitHalf(low, high, false);
            run.visitJumpInsn(Opcodes.GOTO, top);

            run.visitLabel(exhausted);
            atEnd.forEach(Runnable::run);
            run.visitLabel(done);
        });
    }

    /** 範囲を半分に狭める。{@code toUpper} なら真ん中の次から、そうでなければ手前まで。 */
    private void emitHalf(int low, int high, boolean toUpper) {
        run.visitVarInsn(Opcodes.ILOAD, low);
        run.visitVarInsn(Opcodes.ILOAD, high);
        run.visitInsn(Opcodes.IADD);
        run.visitInsn(Opcodes.ICONST_2);
        run.visitInsn(Opcodes.IDIV);
        run.visitInsn(Opcodes.ICONST_1);
        if (toUpper) {
            run.visitInsn(Opcodes.IADD);
            run.visitVarInsn(Opcodes.ISTORE, low);
        } else {
            run.visitInsn(Opcodes.ISUB);
            run.visitVarInsn(Opcodes.ISTORE, high);
        }
    }

    /** 積んである {@code int} を指標へ書き込む命令。 */
    private Runnable planStoreIndex(DataReference index, Origin origin) {
        Runnable address = planAddress(index, origin);
        String field = numericItemConstant(index.item(), origin);
        if (address == null || field == null) {
            return null;
        }
        return () -> {
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            address.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeInteger",
                    "(I" + NUMERIC_ITEM + "L" + STORAGE + ";I)V", false);
        };
    }

    /** 指標を 1 進める命令。 */
    private Runnable planIndexStep(DataReference index, Origin origin) {
        Runnable current = planSourceDecimal(new Operand.Reference(index), origin);
        if (current == null) {
            return null;
        }
        String one = decimalConstant(Decimal.of(1, 0));
        return planStore(index, () -> {
            current.run();
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, one, DECIMAL);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "add",
                    "(" + DECIMAL + DECIMAL + ")" + DECIMAL, false);
        }, origin);
    }

    /**
     * {@code ACCEPT} を組み立てる (要件 FR-060、テスト時の固定は FR-204)。
     *
     * <p>送出側は<b>符号なし整数の表示形式のバイト列</b>である。日付でも端末からの入力でも
     * 同じ形なので、違うのは値を作る呼び出しだけである。詰め方は普通の転記と同じ規則で決まる。
     */
    private void planAccept(Statement.Accept statement, List<Runnable> body) {
        Runnable address = planAddress(statement.target(), statement.origin());
        OptionalInt length = lengthOf(statement.target(), statement.origin());
        if (address == null || length.isEmpty()) {
            return;
        }
        String register = statement.register();
        Runnable source = statement.source() == Statement.Accept.Source.REGISTER
                ? () -> {
                    run.visitVarInsn(Opcodes.ALOAD, 2);
                    run.visitLdcInsn(register);
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "register",
                            "(" + Type.getDescriptor(ProgramContext.class)
                                    + "Ljava/lang/String;)[B", false);
                }
                : () -> {
                    run.visitVarInsn(Opcodes.ALOAD, 2);
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "acceptLine",
                            "(" + Type.getDescriptor(ProgramContext.class) + ")[B", false);
                };

        if (statement.kind() == MoveRules.Kind.ALPHANUMERIC) {
            boolean justified = statement.target().item().justified();
            body.add(() -> {
                source.run();
                address.run();
                push(length.getAsInt());
                run.visitInsn(justified ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "moveAlphanumeric",
                        "([BL" + STORAGE + ";IIZ" + CODE_PAGE + ")V", false);
            });
            return;
        }
        String field = numericItemConstant(statement.target().item(), statement.origin());
        if (field == null) {
            return;
        }
        body.add(() -> {
            source.run();
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "asInteger",
                    "([B" + CODE_PAGE + ")" + DECIMAL, false);
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            address.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "moveNumeric",
                    "(" + DECIMAL + NUMERIC_ITEM + "L" + STORAGE + ";I)V", false);
        });
    }

    /**
     * {@code INITIALIZE} を組み立てる (要件 FR-060)。
     *
     * <p>入る値は翻訳時に決まるので、<b>書き込むバイト列も決まる</b>。基本項目ごとの転記へ
     * 展開せず、連続する部分ごとにまとめて 1 回で書く。表の反復の数だけ転記が並ぶのを
     * 避けるためである。
     *
     * <p>{@code FILLER} と {@code REDEFINES} で重ねた項目は初期化しない。だから
     * 一括で塗り潰すのではなく、書く場所だけを選んで書いている。
     */
    private void planInitialize(Statement.Initialize statement, List<Runnable> body) {
        Runnable address = planAddress(statement.target(), statement.origin());
        if (address == null) {
            return;
        }
        InitializeImage.Result image = InitializeImage.build(statement.target().item(),
                statement.withFiller(), statement.replacing(), codePage);
        diagnostics.addAll(image.diagnostics());
        if (!image.succeeded()) {
            return;
        }
        if (image.runs().isEmpty()) {
            // 何も書き込まない INITIALIZE は書き間違いである
            report(statement.origin(), "INITIALIZE has nothing to initialize");
            return;
        }
        for (InitializeImage.Run runSpec : image.runs()) {
            String field = bytesConstant(runSpec.bytes());
            int at = runSpec.offset();
            int length = runSpec.bytes().length;
            body.add(() -> {
                run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, "[B");
                address.run();
                if (at != 0) {
                    push(at);
                    run.visitInsn(Opcodes.IADD);
                }
                push(length);
                run.visitInsn(Opcodes.ICONST_0);
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "moveAlphanumeric",
                        "([BL" + STORAGE + ";IIZ" + CODE_PAGE + ")V", false);
            });
        }
    }

    // ---- CALL ----

    /**
     * {@code CALL} を組み立てる (要件 FR-080, FR-081)。
     *
     * <p>やることは<b>引数の並びを作って渡す</b>だけである。呼び先を探すのも、作業場所を
     * 呼び出しをまたいで持ち続けるのも、{@code GOBACK} を受け止めるのもランタイムの仕事である
     * (方針 ARC-7)。
     *
     * <p>呼び先を探すクラスローダは<b>呼ぶ側のもの</b>を渡す。生成クラスは同じところに
     * 置かれるためであり、これがないと試験のように独自のローダで読み込んだ場合に見つからない。
     */
    private void planCall(Statement.Call statement, List<Runnable> body) {
        Runnable target = planCallTarget(statement.target(), statement.origin());
        if (target == null) {
            return;
        }
        List<Runnable> arguments = new ArrayList<>();
        for (Statement.Call.Argument argument : statement.arguments()) {
            Runnable planned = planCallArgument(argument, statement.origin());
            if (planned == null) {
                return;
            }
            arguments.add(planned);
        }
        String descriptor = "(" + Type.getDescriptor(ProgramContext.class)
                + (statement.target() instanceof Operand.Literal
                        ? "Ljava/lang/String;" : "[B")
                + "Ljava/lang/ClassLoader;[" + Type.getDescriptor(DataView.class) + ")V";

        Runnable invoke = () -> {
            run.visitVarInsn(Opcodes.ALOAD, 2);
            target.run();
            emitClassLoader();
            emitArray(arguments, DATA_VIEW);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "call", descriptor, false);
        };

        if (statement.exception() == null) {
            body.add(invoke);
            return;
        }
        planCheckedCall(statement, invoke, body);
    }

    /**
     * {@code ON EXCEPTION} つきの {@code CALL}。
     *
     * <p>呼び先が見つからないことを条件として受け止める。ランタイムが投げる
     * {@code ProgramNotFoundException} を旗へ読み替える。{@code COMPUTE} の 0 除算と同じ形である。
     */
    private void planCheckedCall(Statement.Call statement, Runnable invoke, List<Runnable> body) {
        int flag = nextLocal++;
        List<Runnable> onException = planStatements(statement.exception().onOverflow());
        List<Runnable> otherwise = planStatements(statement.exception().otherwise());

        body.add(() -> {
            run.visitInsn(Opcodes.ICONST_0);
            run.visitVarInsn(Opcodes.ISTORE, flag);

            Label start = new Label();
            Label caught = new Label();
            Label handler = new Label();
            Label called = new Label();
            run.visitTryCatchBlock(start, caught, handler,
                    Type.getInternalName(ProgramNotFoundException.class));
            run.visitLabel(start);
            invoke.run();
            run.visitLabel(caught);
            run.visitJumpInsn(Opcodes.GOTO, called);
            run.visitLabel(handler);
            run.visitInsn(Opcodes.POP);
            run.visitInsn(Opcodes.ICONST_1);
            run.visitVarInsn(Opcodes.ISTORE, flag);
            run.visitLabel(called);

            Label noException = new Label();
            Label end = new Label();
            run.visitVarInsn(Opcodes.ILOAD, flag);
            run.visitJumpInsn(Opcodes.IFEQ, noException);
            onException.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(noException);
            otherwise.forEach(Runnable::run);
            run.visitLabel(end);
        });
    }

    /**
     * 呼び先の名前を積む。
     *
     * <p>文字定数なら<b>翻訳時に文字列として決まる</b>。データ項目ならバイト列を積み、
     * 名前へ直すのはランタイムに任せる。実行時のコードページを知っているのはそちらである。
     */
    private Runnable planCallTarget(Operand target, Origin origin) {
        if (target instanceof Operand.Literal literal) {
            if (!(literal.value() instanceof LiteralValue.Text text)) {
                report(origin, "a program name must be an alphanumeric literal");
                return null;
            }
            String name = text.text().trim();
            return () -> run.visitLdcInsn(name);
        }
        DataReference reference = ((Operand.Reference) target).reference();
        OptionalInt length = lengthOf(reference, origin);
        Runnable address = planAddress(reference, origin);
        if (address == null || length.isEmpty()) {
            return null;
        }
        return () -> {
            address.run();
            push(length.getAsInt());
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "read",
                    "(L" + STORAGE + ";II)[B", false);
        };
    }

    /**
     * 引数 1 個を積む。
     *
     * <p>{@code BY REFERENCE} は<b>呼ぶ側の領域そのもの</b>を渡す。{@code BY CONTENT} は
     * 写しを渡す。この違いが、呼ばれた側の書き換えが呼ぶ側に届くかどうかを決める。
     */
    private Runnable planCallArgument(Statement.Call.Argument argument, Origin origin) {
        if (argument.value() instanceof Operand.Literal literal) {
            if (literal.value() instanceof LiteralValue.Figure
                    || literal.value() instanceof LiteralValue.Repeated) {
                // 図形定数は「項目いっぱいまで埋める」ものであり、渡す先の長さが決まらない
                report(origin, "a figurative constant cannot be passed as a CALL argument");
                return null;
            }
            byte[] bytes = literalBytes(literal.value(), 0);
            String field = bytesConstant(bytes);
            return () -> {
                run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, "[B");
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "byContent",
                        "([B)" + Type.getDescriptor(DataView.class), false);
            };
        }
        DataReference reference = ((Operand.Reference) argument.value()).reference();
        OptionalInt length = lengthOf(reference, origin);
        Runnable address = planAddress(reference, origin);
        if (address == null || length.isEmpty()) {
            return null;
        }
        String name = argument.byContent() ? "byContent" : "byReference";
        return () -> {
            address.run();
            push(length.getAsInt());
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, name,
                    "(L" + STORAGE + ";II)" + Type.getDescriptor(DataView.class), false);
        };
    }

    /** {@code CANCEL}。次に呼ばれたときの作業場所を初期状態へ戻す。 */
    private void planCancel(Statement.Cancel statement, List<Runnable> body) {
        for (Operand target : statement.targets()) {
            Runnable name = planCallTarget(target, statement.origin());
            if (name == null) {
                return;
            }
            String descriptor = "(" + Type.getDescriptor(ProgramContext.class)
                    + (target instanceof Operand.Literal ? "Ljava/lang/String;" : "[B") + ")V";
            body.add(() -> {
                run.visitVarInsn(Opcodes.ALOAD, 2);
                name.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "cancel", descriptor, false);
            });
        }
    }

    /** 呼ぶ側のクラスを読み込んだクラスローダを積む。 */
    private void emitClassLoader() {
        run.visitLdcInsn(Type.getObjectType(internal));
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                "()Ljava/lang/ClassLoader;", false);
    }

    private void emitArray(List<Runnable> elements, String type) {
        push(elements.size());
        run.visitTypeInsn(Opcodes.ANEWARRAY, type);
        for (int i = 0; i < elements.size(); i++) {
            run.visitInsn(Opcodes.DUP);
            push(i);
            elements.get(i).run();
            run.visitInsn(Opcodes.AASTORE);
        }
    }

    // ---- INSPECT ----

    /**
     * {@code INSPECT} を組み立てる。
     *
     * <p>句は<b>書かれた順のまま</b>配列にしてランタイムへ渡す。単一の走査で位置ごとに
     * 順に試されるため、並べ替えると結果が変わる。
     *
     * <p>ただし {@code TALLYING} と {@code REPLACING} を同じ文に書いた場合、
     * COBOL は<b>2 つの文を書いたのと同じ</b>に扱う。したがって走査も別々に行う。
     * ひとつの走査にまとめると、数える句が位置を取ってしまい置き換えが起きない。
     */
    private void planInspect(Statement.Inspect statement, List<Runnable> body) {
        Runnable offset = planAddress(statement.target(), statement.origin());
        OptionalInt length = lengthOf(statement.target(), statement.origin());
        if (offset == null || length.isEmpty()) {
            return;
        }
        int size = length.getAsInt();

        if (statement.converting() != null) {
            planConverting(statement, offset, size, body);
            return;
        }

        List<Statement.Inspect.InspectClause> tallying = statement.clauses().stream()
                .filter(c -> c.counter() != null).toList();
        List<Statement.Inspect.InspectClause> replacing = statement.clauses().stream()
                .filter(c -> c.to() != null).toList();

        List<Runnable> tallyClauses = planInspectClauses(tallying, statement.origin());
        List<Runnable> replaceClauses = planInspectClauses(replacing, statement.origin());
        if (tallyClauses == null || replaceClauses == null) {
            return;
        }

        List<Runnable> counters = new ArrayList<>();
        int array = tallying.isEmpty() ? -1 : nextLocal++;
        for (int i = 0; i < tallying.size(); i++) {
            Runnable add = planTallyAdd(tallying.get(i), i, array, statement.origin());
            if (add == null) {
                return;
            }
            counters.add(add);
        }

        body.add(() -> {
            if (!tallyClauses.isEmpty()) {
                offset.run();
                push(size);
                emitClauseArray(tallyClauses);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "tally",
                        "(L" + STORAGE + ";II[" + CLAUSE + ")[I", false);
                run.visitVarInsn(Opcodes.ASTORE, array);
                counters.forEach(Runnable::run);
            }
            if (!replaceClauses.isEmpty()) {
                offset.run();
                push(size);
                emitClauseArray(replaceClauses);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "replace",
                        "(L" + STORAGE + ";II[" + CLAUSE + ")V", false);
            }
        });
    }

    private List<Runnable> planInspectClauses(List<Statement.Inspect.InspectClause> clauses,
                                              Origin origin) {
        List<Runnable> planned = new ArrayList<>();
        for (Statement.Inspect.InspectClause clause : clauses) {
            Runnable one = planInspectClause(clause, origin);
            if (one == null) {
                return null;
            }
            planned.add(one);
        }
        return planned;
    }

    private void emitClauseArray(List<Runnable> clauses) {
        emitArray(clauses, Type.getInternalName(InspectScan.Clause.class));
    }

    /** 句 1 個を組み立てる命令。 */
    private Runnable planInspectClause(Statement.Inspect.InspectClause clause, Origin origin) {
        Runnable region = planRegion(clause.region(), origin);
        if (region == null) {
            return null;
        }
        Runnable pattern = clause.pattern() == null ? null : planInspectBytes(clause.pattern(), origin);
        Runnable to = clause.to() == null ? null : planInspectBytes(clause.to(), origin);
        if ((clause.pattern() != null && pattern == null) || (clause.to() != null && to == null)) {
            return null;
        }

        String name = factoryOf(clause);
        String descriptor = descriptorOf(clause);
        return () -> {
            if (pattern != null) {
                pattern.run();
            }
            if (to != null) {
                to.run();
            }
            region.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(InspectScan.Clause.class), name, descriptor, false);
        };
    }

    private static String factoryOf(Statement.Inspect.InspectClause clause) {
        boolean replacing = clause.to() != null;
        return switch (clause.kind()) {
            case CHARACTERS -> replacing ? "replaceCharacters" : "characters";
            case ALL -> replacing ? "replaceAll" : "all";
            case LEADING -> replacing ? "replaceLeading" : "leading";
            case FIRST -> "replaceFirst";
        };
    }

    private static String descriptorOf(Statement.Inspect.InspectClause clause) {
        int arrays = (clause.pattern() == null ? 0 : 1) + (clause.to() == null ? 0 : 1);
        return "(" + "[B".repeat(arrays) + REGION + ")" + CLAUSE;
    }

    private Runnable planRegion(Statement.Inspect.RegionSpec region, Origin origin) {
        Runnable after = region.after() == null ? null : planInspectBytes(region.after(), origin);
        Runnable before = region.before() == null ? null : planInspectBytes(region.before(), origin);
        if ((region.after() != null && after == null) || (region.before() != null && before == null)) {
            return null;
        }
        return () -> {
            if (after == null) {
                run.visitInsn(Opcodes.ACONST_NULL);
            } else {
                after.run();
            }
            if (before == null) {
                run.visitInsn(Opcodes.ACONST_NULL);
            } else {
                before.run();
            }
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "region", "([B[B)" + REGION, false);
        };
    }

    /** 照合や置き換えに使う並び。定数はそのままの長さで用いる。 */
    private Runnable planInspectBytes(Operand operand, Origin origin) {
        if (operand instanceof Operand.Literal literal) {
            byte[] bytes = literalBytes(literal.value(), 1);
            String field = bytesConstant(bytes);
            return () -> run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, "[B");
        }
        DataReference reference = ((Operand.Reference) operand).reference();
        return planSourceBytes(operand, origin,
                reference.constantLength().orElse(0));
    }

    private Runnable planTallyAdd(Statement.Inspect.InspectClause clause, int index, int array,
                                  Origin origin) {
        Runnable offset = planAddress(clause.counter(), origin);
        String field = numericItemConstant(clause.counter().item(), origin);
        if (offset == null || field == null) {
            return null;
        }
        return () -> {
            run.visitVarInsn(Opcodes.ALOAD, array);
            push(index);
            run.visitInsn(Opcodes.IALOAD);
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            offset.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "addTally",
                    "(I" + NUMERIC_ITEM + "L" + STORAGE + ";I)V", false);
        };
    }

    private void planConverting(Statement.Inspect statement, Runnable offset, int size,
                                List<Runnable> body) {
        Statement.Inspect.Converting converting = statement.converting();
        Runnable from = planInspectBytes(converting.from(), statement.origin());
        Runnable to = planInspectBytes(converting.to(), statement.origin());
        Runnable region = planRegion(converting.region(), statement.origin());
        if (from == null || to == null || region == null) {
            return;
        }
        body.add(() -> {
            offset.run();
            push(size);
            from.run();
            to.run();
            region.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "convert",
                    "(L" + STORAGE + ";II[B[B" + REGION + ")V", false);
        });
    }

    // ---- 制御構造 ----

    private void planIf(Statement.If statement, List<Runnable> body) {
        List<Runnable> onTrue = planStatements(statement.onTrue());
        List<Runnable> onFalse = planStatements(statement.onFalse());
        body.add(() -> {
            Label otherwise = new Label();
            Label end = new Label();
            // 条件が成り立てば下へ抜け、成り立たなければ ELSE へ飛ぶ
            emitCondition(statement.condition(), otherwise, false);
            onTrue.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(otherwise);
            onFalse.forEach(Runnable::run);
            run.visitLabel(end);
        });
    }

    /**
     * {@code PERFORM} を組み立てる。
     *
     * <p>繰り返しの指定と、繰り返す中身は<b>独立に決まる</b>。中身は段落の呼び出しか
     * その場に書いた文か、指定は 1 回・回数・条件のいずれか。組み合わせて出す。
     */
    private void planPerform(Statement.Perform statement, List<Runnable> body) {
        Runnable once = planPerformBody(statement);
        if (once == null) {
            return;
        }
        if (statement.times() != null) {
            planTimes(statement, once, body);
            return;
        }
        if (statement.until() != null) {
            planUntil(statement, once, body);
            return;
        }
        if (!statement.varying().isEmpty()) {
            planVarying(statement, once, body);
            return;
        }
        body.add(once);
    }

    /** 繰り返す中身を 1 回分。 */
    private Runnable planPerformBody(Statement.Perform statement) {
        if (!statement.callsParagraph()) {
            List<Runnable> inline = planStatements(statement.body());
            return () -> inline.forEach(Runnable::run);
        }
        int from = paragraphNames.indexOf(statement.target());
        int to = statement.through() == null
                ? from
                : paragraphNames.indexOf(statement.through());
        if (from < 0 || to < 0) {
            report(statement.origin(), "undefined paragraph: " + statement.target());
            return null;
        }
        return () -> emitPerformRange(from, to);
    }

    private void planTimes(Statement.Perform statement, Runnable once, List<Runnable> body) {
        Runnable count = planSourceDecimal(statement.times(), statement.origin());
        if (count == null) {
            return;
        }
        int counter = nextLocal++;
        int limit = nextLocal++;
        body.add(() -> {
            count.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
            run.visitVarInsn(Opcodes.ISTORE, limit);
            run.visitInsn(Opcodes.ICONST_0);
            run.visitVarInsn(Opcodes.ISTORE, counter);

            Label test = new Label();
            Label end = new Label();
            run.visitLabel(test);
            run.visitVarInsn(Opcodes.ILOAD, counter);
            run.visitVarInsn(Opcodes.ILOAD, limit);
            run.visitJumpInsn(Opcodes.IF_ICMPGE, end);
            once.run();
            run.visitIincInsn(counter, 1);
            run.visitJumpInsn(Opcodes.GOTO, test);
            run.visitLabel(end);
        });
    }

    /**
     * {@code UNTIL} の繰り返し。<b>条件は「やめる条件」である</b>。
     * {@code WITH TEST AFTER} なら中身を 1 度実行してから条件を見る。
     */
    private void planUntil(Statement.Perform statement, Runnable once, List<Runnable> body) {
        body.add(() -> {
            Label top = new Label();
            Label end = new Label();
            run.visitLabel(top);
            if (!statement.testAfter()) {
                emitCondition(statement.until(), end, true);
            }
            once.run();
            if (statement.testAfter()) {
                emitCondition(statement.until(), end, true);
            }
            run.visitJumpInsn(Opcodes.GOTO, top);
            run.visitLabel(end);
        });
    }

    /**
     * {@code PERFORM VARYING} … {@code AFTER} … の繰り返し。
     *
     * <p>{@code AFTER} で並べた段は入れ子であり、<b>外側が 1 進むたびに内側は初期値へ戻る</b>。
     * 段ごとに「初期値を入れる」命令と「1 回分足す」命令を作り、両者を組み合わせて出す。
     *
     * <p>{@code TEST BEFORE} では段の数だけ判定を縦に並べ、内側の段が尽きたところで
     * その段を初期値へ戻して外側を 1 進める。{@code TEST AFTER} では中身を先に実行し、
     * 内側の条件から順に見ていく。どちらも<b>初期値へ戻すのは判定に負けた段だけ</b>である。
     */
    private void planVarying(Statement.Perform statement, Runnable once, List<Runnable> body) {
        List<Statement.Perform.Varying> levels = statement.varying();
        List<Runnable> set = new ArrayList<>();
        List<Runnable> step = new ArrayList<>();
        for (Statement.Perform.Varying level : levels) {
            Runnable initialize = planStore(level.target(),
                    planSourceDecimal(level.from(), statement.origin()), statement.origin());
            Runnable increment = planIncrement(level, statement.origin());
            if (initialize == null || increment == null) {
                return;
            }
            set.add(initialize);
            step.add(increment);
        }

        int depth = levels.size();
        body.add(() -> {
            set.forEach(Runnable::run);
            if (statement.testAfter()) {
                emitVaryingTestAfter(levels, set, step, once);
            } else {
                emitVaryingTestBefore(levels, set, step, once, depth);
            }
        });
    }

    private void emitVaryingTestBefore(List<Statement.Perform.Varying> levels, List<Runnable> set,
                                       List<Runnable> step, Runnable once, int depth) {
        Label end = new Label();
        Label[] test = new Label[depth];
        Label[] exhausted = new Label[depth];
        for (int k = 0; k < depth; k++) {
            test[k] = new Label();
            // いちばん外側が尽きたら文全体が終わる
            exhausted[k] = k == 0 ? end : new Label();
        }
        for (int k = 0; k < depth; k++) {
            run.visitLabel(test[k]);
            emitCondition(levels.get(k).until(), exhausted[k], true);
        }
        once.run();
        step.get(depth - 1).run();
        run.visitJumpInsn(Opcodes.GOTO, test[depth - 1]);
        for (int k = depth - 1; k >= 1; k--) {
            run.visitLabel(exhausted[k]);
            set.get(k).run();
            step.get(k - 1).run();
            run.visitJumpInsn(Opcodes.GOTO, test[k - 1]);
        }
        run.visitLabel(end);
    }

    private void emitVaryingTestAfter(List<Statement.Perform.Varying> levels, List<Runnable> set,
                                      List<Runnable> step, Runnable once) {
        Label top = new Label();
        run.visitLabel(top);
        once.run();
        for (int k = levels.size() - 1; k >= 0; k--) {
            Label exhausted = new Label();
            emitCondition(levels.get(k).until(), exhausted, true);
            step.get(k).run();
            run.visitJumpInsn(Opcodes.GOTO, top);
            run.visitLabel(exhausted);
            if (k > 0) {
                set.get(k).run();
            }
        }
    }

    /** {@code v = v + by} を組み立てる。 */
    private Runnable planIncrement(Statement.Perform.Varying level, Origin origin) {
        Runnable current = planSourceDecimal(new Operand.Reference(level.target()), origin);
        Runnable by = planSourceDecimal(level.by(), origin);
        if (current == null || by == null) {
            return null;
        }
        return planStore(level.target(), () -> {
            current.run();
            by.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "add",
                    "(" + DECIMAL + DECIMAL + ")" + DECIMAL, false);
        }, origin);
    }

    /** 積んだ {@link Decimal} を数値項目へ切り捨てて書き込む命令。 */
    private Runnable planStore(DataReference target, Runnable value, Origin origin) {
        return planStore(target, value, "TRUNCATION", origin);
    }

    /** 積んだ {@link Decimal} を数値項目へ書き込む命令。 */
    private Runnable planStore(DataReference target, Runnable value, String rounding,
                               Origin origin) {
        if (value == null) {
            return null;
        }
        Runnable offset = planAddress(target, origin);
        DataItem item = target.item();
        String field = numericItemConstant(item, origin);
        if (offset == null || field == null || item.picture() == null) {
            return null;
        }
        return () -> {
            value.run();
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            offset.run();
            loadRounding(rounding);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "store",
                    "(" + DECIMAL + NUMERIC_ITEM + "L" + STORAGE + ";I"
                            + Type.getDescriptor(CobolRounding.class) + ")V", false);
        };
    }

    /**
     * 条件を評価し、{@code jumpWhenTrue} の向きに一致したら {@code target} へ飛ぶ。
     * 一致しなければ下へ抜ける。
     *
     * <p>真のときに飛ぶ形と偽のときに飛ぶ形の両方を持つのは、<b>{@code AND} と
     * {@code OR} の短絡</b>のためである。片方だけだと、どちらかで余計な分岐が要る。
     */
    private void emitCondition(Condition condition, Label target, boolean jumpWhenTrue) {
        if (condition instanceof Condition.Not not) {
            emitCondition(not.inner(), target, !jumpWhenTrue);
            return;
        }
        if (condition instanceof Condition.And and) {
            if (jumpWhenTrue) {
                // 左が偽なら全体も偽。飛ばずに下へ抜ける
                Label skip = new Label();
                emitCondition(and.left(), skip, false);
                emitCondition(and.right(), target, true);
                run.visitLabel(skip);
            } else {
                emitCondition(and.left(), target, false);
                emitCondition(and.right(), target, false);
            }
            return;
        }
        if (condition instanceof Condition.Or or) {
            if (jumpWhenTrue) {
                emitCondition(or.left(), target, true);
                emitCondition(or.right(), target, true);
            } else {
                // 左が真なら全体も真。飛ばずに下へ抜ける
                Label skip = new Label();
                emitCondition(or.left(), skip, true);
                emitCondition(or.right(), target, false);
                run.visitLabel(skip);
            }
            return;
        }
        emitRelation((Condition.Relation) condition, target, jumpWhenTrue);
    }

    private void emitRelation(Condition.Relation relation, Label target, boolean jumpWhenTrue) {
        Runnable comparison = planComparison(relation);
        if (comparison == null) {
            return;
        }
        comparison.run();
        Condition.Comparison test =
                jumpWhenTrue ? relation.comparison() : relation.comparison().negate();
        run.visitJumpInsn(branchOpcode(test), target);
    }

    /**
     * 関係の<b>3 方向の比較</b>を {@code int} として積む命令。
     *
     * <p>負なら左が小さく、0 なら等しく、正なら左が大きい。{@code IF} は符号だけを見て
     * 分岐するが、{@code SEARCH ALL} の 2 分探索は<b>3 つの向きを区別する</b>ため
     * 値そのものを使う。
     */
    private Runnable planComparison(Condition.Relation relation) {
        Runnable left;
        Runnable right;
        if (relation.numeric()) {
            left = planSourceDecimal(relation.left(), relation.origin());
            right = planSourceDecimal(relation.right(), relation.origin());
        } else {
            int length = comparisonLength(relation);
            left = planSourceBytes(relation.left(), relation.origin(), length);
            right = planSourceBytes(relation.right(), relation.origin(), length);
        }
        if (left == null || right == null) {
            return null;
        }
        return () -> {
            left.run();
            right.run();
            if (relation.numeric()) {
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "compareNumeric",
                        "(" + DECIMAL + DECIMAL + ")I", false);
            } else {
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "compareAlphanumeric",
                        "([B[B" + CODE_PAGE + ")I", false);
            }
        };
    }

    /** 図形定数を広げる長さ。相手の項目の長さに合わせる。 */
    private static int comparisonLength(Condition.Relation relation) {
        int length = lengthOf(relation.left());
        return length > 0 ? length : lengthOf(relation.right());
    }

    private static int lengthOf(Operand operand) {
        if (operand instanceof Operand.Reference reference) {
            return reference.reference().constantLength().orElse(0);
        }
        return 0;
    }

    private static int branchOpcode(Condition.Comparison comparison) {
        return switch (comparison) {
            case EQUAL -> Opcodes.IFEQ;
            case NOT_EQUAL -> Opcodes.IFNE;
            case LESS -> Opcodes.IFLT;
            case LESS_OR_EQUAL -> Opcodes.IFLE;
            case GREATER -> Opcodes.IFGT;
            case GREATER_OR_EQUAL -> Opcodes.IFGE;
        };
    }

    /**
     * {@code run} は段落全体を 1 つの範囲として実行する。
     *
     * <p>素直に流れる実行も {@code PERFORM} も、同じ「範囲を実行する」機構で書ける。
     * 違いは範囲の終わりがどこかだけである。
     */
    private void emitRun(int paragraphCount) {
        run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", RUN_DESCRIPTOR, null, null);
        run.visitCode();
        if (paragraphCount > 0) {
            emitPerformRange(0, paragraphCount - 1);
        }
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
    }

    /**
     * 段落の番号から段落のメソッドへ振り分ける。
     *
     * <p>{@code GO TO} の行き先は<b>実行時にしか分からない</b>。飛び先の番号を受け取って
     * 呼び分ける入口が要る。表引きの分岐 1 つで済む。
     */
    private void emitDispatch(int paragraphCount) {
        MethodVisitor dispatch = writer.visitMethod(Opcodes.ACC_PRIVATE, "dispatch",
                DISPATCH_DESCRIPTOR, null, null);
        dispatch.visitCode();
        Label[] targets = new Label[paragraphCount];
        for (int i = 0; i < paragraphCount; i++) {
            targets[i] = new Label();
        }
        Label fallthrough = new Label();
        dispatch.visitVarInsn(Opcodes.ILOAD, 1);
        dispatch.visitTableSwitchInsn(0, paragraphCount - 1, fallthrough, targets);
        for (int i = 0; i < paragraphCount; i++) {
            dispatch.visitLabel(targets[i]);
            dispatch.visitVarInsn(Opcodes.ALOAD, 0);
            dispatch.visitVarInsn(Opcodes.ALOAD, 2);
            dispatch.visitVarInsn(Opcodes.ALOAD, 3);
            dispatch.visitVarInsn(Opcodes.ALOAD, 4);
            dispatch.visitMethodInsn(Opcodes.INVOKESPECIAL, internal, paragraphMethod(i),
                    PARAGRAPH_DESCRIPTOR, false);
            dispatch.visitInsn(Opcodes.IRETURN);
        }
        dispatch.visitLabel(fallthrough);
        dispatch.visitInsn(Opcodes.ICONST_M1);
        dispatch.visitInsn(Opcodes.IRETURN);
        dispatch.visitMaxs(0, 0);
        dispatch.visitEnd();
    }

    /**
     * 段落の範囲 {@code [from, through]} を実行する。
     *
     * <p>段落は「次はどこか」を返す。{@code -1} なら最後まで流れたということであり、
     * 0 以上なら {@code GO TO} で飛んだ先である。
     *
     * <p><b>範囲が終わるのは、最後の段落を最後まで流れきったときだけ</b>である。
     * {@code GO TO} で範囲の外へ出ても戻ってはこない。参照実装が範囲の終わりに戻り口を
     * 置くのと同じであり、そこへ来なければ戻らない。
     *
     * <p>範囲の外へ出たまま手続き部の最後まで流れきったときは、{@code PERFORM} へ
     * 戻るのではなく<b>暗黙の {@code GOBACK}</b> になる。手続き部の終わりに達したのだから、
     * 待っている {@code PERFORM} があってもそこで実行は終わり、呼んだ側へ戻る。
     */
    private void emitPerformMethod(int paragraphCount) {
        MethodVisitor perform = writer.visitMethod(Opcodes.ACC_PRIVATE, "performRange",
                PERFORM_DESCRIPTOR, null, null);
        perform.visitCode();
        int pc = 6;
        int next = 7;
        perform.visitVarInsn(Opcodes.ILOAD, 1);
        perform.visitVarInsn(Opcodes.ISTORE, pc);

        Label top = new Label();
        Label end = new Label();
        Label jumped = new Label();
        perform.visitLabel(top);
        perform.visitVarInsn(Opcodes.ALOAD, 0);
        perform.visitVarInsn(Opcodes.ILOAD, pc);
        perform.visitVarInsn(Opcodes.ALOAD, 3);
        perform.visitVarInsn(Opcodes.ALOAD, 4);
        perform.visitVarInsn(Opcodes.ALOAD, 5);
        perform.visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "dispatch",
                DISPATCH_DESCRIPTOR, false);
        perform.visitVarInsn(Opcodes.ISTORE, next);

        // GO TO で飛んだのなら、範囲の終わりに来たとは言えない
        perform.visitVarInsn(Opcodes.ILOAD, next);
        perform.visitJumpInsn(Opcodes.IFGE, jumped);
        perform.visitVarInsn(Opcodes.ILOAD, pc);
        perform.visitVarInsn(Opcodes.ILOAD, 2);
        perform.visitJumpInsn(Opcodes.IF_ICMPEQ, end);
        perform.visitIincInsn(pc, 1);
        Label check = new Label();
        perform.visitJumpInsn(Opcodes.GOTO, check);
        perform.visitLabel(jumped);
        perform.visitVarInsn(Opcodes.ILOAD, next);
        perform.visitVarInsn(Opcodes.ISTORE, pc);

        // 最後の段落を流れきったら、手続き部の終わりである。暗黙の STOP RUN になる
        Label offEnd = new Label();
        perform.visitLabel(check);
        perform.visitVarInsn(Opcodes.ILOAD, pc);
        push(perform, paragraphCount);
        perform.visitJumpInsn(Opcodes.IF_ICMPGE, offEnd);
        perform.visitJumpInsn(Opcodes.GOTO, top);

        perform.visitLabel(offEnd);
        perform.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "programReturn", "()V", false);

        perform.visitLabel(end);
        perform.visitInsn(Opcodes.RETURN);
        perform.visitMaxs(0, 0);
        perform.visitEnd();
    }

    /**
     * {@code main} を出す。生成したクラスをそのまま {@code java} で起動できるようにする。
     */
    private void emitMain() {
        MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitTypeInsn(Opcodes.NEW, internal);
        main.visitInsn(Opcodes.DUP);
        main.visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "<init>", "()V", false);
        main.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(CobolProgram.class),
                "runFresh", "()L" + Type.getInternalName(Storage.class) + ";", true);
        main.visitInsn(Opcodes.POP);
        main.visitInsn(Opcodes.RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
    }

    /**
     * 段落 1 つをメソッドとして出す。
     *
     * <p>返す値は<b>次にどこへ行くか</b>である。最後まで流れたら {@code -1}、
     * {@code GO TO} で飛ぶならその段落の番号。{@code GO TO} を素直な {@code return} に
     * できるので、段落の途中からでも抜けられる。
     */
    private void emitParagraph(int index, List<Runnable> body) {
        run = writer.visitMethod(Opcodes.ACC_PRIVATE, paragraphMethod(index),
                PARAGRAPH_DESCRIPTOR, null, null);
        run.visitCode();
        body.forEach(Runnable::run);
        run.visitInsn(Opcodes.ICONST_M1);
        run.visitInsn(Opcodes.IRETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
    }

    /** 段落の範囲を実行する呼び出しを積む。 */
    private void emitPerformRange(int from, int through) {
        run.visitVarInsn(Opcodes.ALOAD, 0);
        push(from);
        push(through);
        run.visitVarInsn(Opcodes.ALOAD, 1);
        run.visitVarInsn(Opcodes.ALOAD, 2);
        run.visitVarInsn(Opcodes.ALOAD, ARGUMENTS_LOCAL);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "performRange",
                PERFORM_DESCRIPTOR, false);
    }

    private static String paragraphMethod(int index) {
        return "paragraph$" + index;
    }

    private void planMove(Statement.Move move, List<Runnable> body) {
        for (Statement.Move.Target target : move.targets()) {
            Runnable offset = planAddress(target.reference(), move.origin());
            OptionalInt length = lengthOf(target.reference(), move.origin());
            if (offset == null || length.isEmpty()) {
                return;
            }
            switch (target.kind()) {
                case ALPHANUMERIC -> planAlphanumericMove(move, target, offset,
                        length.getAsInt(), body);
                case NUMERIC -> planNumericMove(move, target, offset, body);
                case NUMERIC_EDITED -> planEditedMove(move, target, offset, body);
            }
        }
    }

    private void planAlphanumericMove(Statement.Move move, Statement.Move.Target target,
                                      Runnable offset, int length, List<Runnable> body) {
        Runnable source = planSourceBytes(move.source(), move.origin(), length);
        if (source == null) {
            return;
        }
        boolean justified = target.reference().item().justified();
        body.add(() -> {
            source.run();
            offset.run();
            push(length);
            run.visitInsn(justified ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "moveAlphanumeric",
                    "([BL" + STORAGE + ";IIZ" + CODE_PAGE + ")V", false);
        });
    }

    private void planNumericMove(Statement.Move move, Statement.Move.Target target,
                                 Runnable offset, List<Runnable> body) {
        Runnable source = planSourceDecimal(move.source(), move.origin());
        if (source == null) {
            return;
        }
        DataItem item = target.reference().item();
        String field = numericItemConstant(item, move.origin());
        if (field == null) {
            return;
        }
        body.add(() -> {
            source.run();
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            offset.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "moveNumeric",
                    "(" + DECIMAL + NUMERIC_ITEM + "L" + STORAGE + ";I)V", false);
        });
    }

    private void planEditedMove(Statement.Move move, Statement.Move.Target target,
                                Runnable offset, List<Runnable> body) {
        Runnable source = planSourceDecimal(move.source(), move.origin());
        if (source == null) {
            return;
        }
        String field = pictureConstant(target.reference().item().picture());
        body.add(() -> {
            source.run();
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, PICTURE);
            offset.run();
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "moveNumericEdited",
                    "(" + DECIMAL + PICTURE + "L" + STORAGE + ";I" + CODE_PAGE + ")V", false);
        });
    }

    // ---- 算術文 ----

    /**
     * 算術文を組み立てる。
     *
     * <p>受取項目ごとに計算をまるごと出す。<b>除算の商の桁数は受取項目に合わせる</b>ため、
     * 受取項目が複数あれば計算そのものが変わりうるからである。
     */
    /**
     * {@code COMPUTE} を組み立てる。
     *
     * <p>式は<b>1 度だけ</b>評価し、局所変数へ取ってから受取項目へ配る。受取項目ごとに
     * 評価しなおすと、式の中に受取項目が現れたときに 2 つ目以降の値が変わってしまう。
     *
     * <p>中間結果の桁数は {@link IntermediateDigits} が決める (要件 5.5.1)。
     * 除算だけは結果の桁数が被演算子から決まらないため、この桁数がなければ命令が出せない。
     */
    private void planCompute(Statement.Compute statement, List<Runnable> body) {
        IntermediateDigits digits = IntermediateDigits.of(statement.value(), statement.targets());
        Runnable value = planExpression(statement.value(), digits, statement.origin());
        if (value == null) {
            return;
        }
        if (statement.isChecked()) {
            planCheckedCompute(statement, value, body);
            return;
        }
        int slot = nextLocal++;
        List<Runnable> stores = new ArrayList<>();
        for (Statement.Arithmetic.Target target : statement.targets()) {
            Runnable store = planStore(target.reference(),
                    () -> run.visitVarInsn(Opcodes.ALOAD, slot),
                    target.rounded() ? "NEAREST_AWAY_FROM_ZERO" : "TRUNCATION",
                    statement.origin());
            if (store == null) {
                return;
            }
            stores.add(store);
        }
        body.add(() -> {
            value.run();
            run.visitVarInsn(Opcodes.ASTORE, slot);
            stores.forEach(Runnable::run);
        });
    }

    /**
     * {@code ON SIZE ERROR} つきの {@code COMPUTE}。
     *
     * <p>0 除算は<b>式のどこにでも現れうる</b>。ほかの算術文のように割る前に除数を調べる形は
     * 取れないため、式の評価そのものを {@code try} で囲み、ランタイムが投げる
     * {@code DecimalDivideException} を条件へ読み替える。
     */
    private void planCheckedCompute(Statement.Compute statement, Runnable value,
                                    List<Runnable> body) {
        int flag = nextLocal++;
        int slot = nextLocal++;
        List<Runnable> stores = new ArrayList<>();
        for (Statement.Arithmetic.Target target : statement.targets()) {
            Runnable store = planCheckedStore(target, slot, flag, statement.origin());
            if (store == null) {
                return;
            }
            stores.add(store);
        }
        List<Runnable> onError = planStatements(statement.sizeError().onError());
        List<Runnable> otherwise = planStatements(statement.sizeError().otherwise());

        body.add(() -> {
            run.visitInsn(Opcodes.ICONST_0);
            run.visitVarInsn(Opcodes.ISTORE, flag);

            Label start = new Label();
            Label caught = new Label();
            Label handler = new Label();
            Label evaluated = new Label();
            run.visitTryCatchBlock(start, caught, handler,
                    Type.getInternalName(DecimalDivideException.class));
            run.visitLabel(start);
            value.run();
            run.visitVarInsn(Opcodes.ASTORE, slot);
            run.visitLabel(caught);
            run.visitJumpInsn(Opcodes.GOTO, evaluated);
            run.visitLabel(handler);
            run.visitInsn(Opcodes.POP);
            run.visitInsn(Opcodes.ICONST_1);
            run.visitVarInsn(Opcodes.ISTORE, flag);
            run.visitInsn(Opcodes.ACONST_NULL);
            run.visitVarInsn(Opcodes.ASTORE, slot);
            run.visitLabel(evaluated);

            // 0 除算なら受取項目には触れない
            Label stored = new Label();
            run.visitVarInsn(Opcodes.ILOAD, flag);
            run.visitJumpInsn(Opcodes.IFNE, stored);
            stores.forEach(Runnable::run);
            run.visitLabel(stored);

            Label noError = new Label();
            Label end = new Label();
            run.visitVarInsn(Opcodes.ILOAD, flag);
            run.visitJumpInsn(Opcodes.IFEQ, noError);
            onError.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(noError);
            otherwise.forEach(Runnable::run);
            run.visitLabel(end);
        });
    }

    /** 収まらなければ受取項目を変えず、条件を立てる格納。 */
    private Runnable planCheckedStore(Statement.Arithmetic.Target target, int slot, int flag,
                                      Origin origin) {
        Runnable offset = planAddress(target.reference(), origin);
        DataItem item = target.reference().item();
        String field = numericItemConstant(item, origin);
        if (offset == null || field == null || item.picture() == null) {
            return null;
        }
        String rounding = target.rounded() ? "NEAREST_AWAY_FROM_ZERO" : "TRUNCATION";
        return () -> {
            Label done = new Label();
            run.visitVarInsn(Opcodes.ALOAD, slot);
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            offset.run();
            loadRounding(rounding);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeChecked",
                    "(" + DECIMAL + NUMERIC_ITEM + "L" + STORAGE + ";I"
                            + Type.getDescriptor(CobolRounding.class) + ")Z", false);
            run.visitJumpInsn(Opcodes.IFEQ, done);
            run.visitInsn(Opcodes.ICONST_1);
            run.visitVarInsn(Opcodes.ISTORE, flag);
            run.visitLabel(done);
        };
    }

    /**
     * 算術式を評価して {@link Decimal} を 1 個積む命令。
     *
     * <p>加減乗は正確に計算でき、結果の小数桁は規則どおりになる。上限を超えて桁を削った
     * ときだけ切り捨てを挟む。除算は<b>この節に決まった桁数で打ち切る</b>。
     */
    private Runnable planExpression(Expression expression, IntermediateDigits digits,
                                    Origin origin) {
        if (expression instanceof Expression.Value value) {
            return planSourceDecimal(value.operand(), origin);
        }
        if (expression instanceof Expression.Negate negate) {
            Runnable inner = planExpression(negate.operand(), digits, origin);
            if (inner == null) {
                return null;
            }
            return () -> {
                inner.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "negate",
                        "(" + DECIMAL + ")" + DECIMAL, false);
            };
        }
        Expression.Binary binary = (Expression.Binary) expression;
        Runnable left = planExpression(binary.left(), digits, origin);
        Runnable right = planExpression(binary.right(), digits, origin);
        if (left == null || right == null) {
            return null;
        }
        int scale = digits.of(expression).scale();
        if (binary.operator() == Expression.Operator.DIVIDE) {
            return () -> {
                left.run();
                right.run();
                push(scale);
                loadRounding("TRUNCATION");
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "divide",
                        "(" + DECIMAL + DECIMAL + "I"
                                + Type.getDescriptor(CobolRounding.class) + ")" + DECIMAL, false);
            };
        }
        String name = switch (binary.operator()) {
            case ADD -> "add";
            case SUBTRACT -> "subtract";
            case MULTIPLY -> "multiply";
            case DIVIDE -> throw new IllegalStateException("handled above");
        };
        int natural = naturalScale(binary, digits);
        return () -> {
            left.run();
            right.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, name,
                    "(" + DECIMAL + DECIMAL + ")" + DECIMAL, false);
            if (scale < natural) {
                // 総桁数の上限を超えたぶんだけ小数部を削る (要件 FR-047)
                push(scale);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "truncate",
                        "(" + DECIMAL + "I)" + DECIMAL, false);
            }
        };
    }

    /** 加減乗を正確に計算したときの小数桁。上限で削られる前の値である。 */
    private static int naturalScale(Expression.Binary binary, IntermediateDigits digits) {
        int left = digits.of(binary.left()).scale();
        int right = digits.of(binary.right()).scale();
        return binary.operator() == Expression.Operator.MULTIPLY
                ? left + right
                : Math.max(left, right);
    }

    private void planArithmetic(Statement.Arithmetic statement, List<Runnable> body) {
        if (statement.isChecked()) {
            planCheckedArithmetic(statement, body);
            return;
        }
        for (Statement.Arithmetic.Target target : statement.targets()) {
            Runnable offset = planAddress(target.reference(), statement.origin());
            if (offset == null) {
                return;
            }
            DataItem item = target.reference().item();
            String field = numericItemConstant(item, statement.origin());
            if (field == null || item.picture() == null) {
                return;
            }
            int scale = item.picture().scale();
            String rounding = target.rounded() ? "NEAREST_AWAY_FROM_ZERO" : "TRUNCATION";

            List<Runnable> value = new ArrayList<>();
            if (statement.accumulate() != null) {
                // 受取項目の現在値から始める
                String source = field;
                value.add(() -> {
                    run.visitFieldInsn(Opcodes.GETSTATIC, internal, source, NUMERIC_ITEM);
                    offset.run();
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readNumeric",
                            "(" + NUMERIC_ITEM + "L" + STORAGE + ";I)" + DECIMAL, false);
                });
            }
            if (!planFold(statement, value, scale, rounding)) {
                return;
            }
            if (statement.accumulate() != null) {
                value.add(() -> emitOperator(statement.accumulate(), scale, rounding));
            }

            body.add(() -> {
                value.forEach(Runnable::run);
                run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
                offset.run();
                loadRounding(rounding);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "store",
                        "(" + DECIMAL + NUMERIC_ITEM + "L" + STORAGE + ";I"
                                + Type.getDescriptor(CobolRounding.class) + ")V", false);
            });
        }
    }

    /**
     * {@code ON SIZE ERROR} つきの算術文。
     *
     * <p>指定がないときとの違いは<b>桁があふれたときに受取項目に何が残るか</b>である。
     * 指定があれば受取項目は変わらず、なければ上位桁を切り捨てた値が入る。
     *
     * <p>0 除算も条件を立てる。<b>割る前に除数を調べる</b>ため、被演算子はいったん
     * 局所変数へ取る。計算の途中で飛ぶと、作用対象のスタックが揃わなくなるためである。
     */
    private void planCheckedArithmetic(Statement.Arithmetic statement, List<Runnable> body) {
        int flag = nextLocal++;
        List<Runnable> perTarget = new ArrayList<>();
        for (Statement.Arithmetic.Target target : statement.targets()) {
            Runnable planned = planCheckedTarget(statement, target, flag);
            if (planned == null) {
                return;
            }
            perTarget.add(planned);
        }
        planSizeErrorBranch(perTarget, statement.sizeError(), flag, body);
    }

    /**
     * 計算のあとに条件を見る形を組み立てる。
     *
     * <p>旗は<b>計算の全体で 1 つ</b>である。受取項目が複数あっても、
     * {@code CORRESPONDING} で組が複数あっても、条件文を通るのは 1 度だけである。
     */
    private void planSizeErrorBranch(List<Runnable> operations,
                                     Statement.Arithmetic.SizeError sizeError, int flag,
                                     List<Runnable> body) {
        List<Runnable> onError = planStatements(sizeError.onError());
        List<Runnable> otherwise = planStatements(sizeError.otherwise());

        body.add(() -> {
            run.visitInsn(Opcodes.ICONST_0);
            run.visitVarInsn(Opcodes.ISTORE, flag);
            operations.forEach(Runnable::run);

            Label noError = new Label();
            Label end = new Label();
            run.visitVarInsn(Opcodes.ILOAD, flag);
            run.visitJumpInsn(Opcodes.IFEQ, noError);
            onError.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(noError);
            otherwise.forEach(Runnable::run);
            run.visitLabel(end);
        });
    }

    /**
     * {@code DIVIDE ... REMAINDER} を組み立てる。
     *
     * <p>商と剰余は<b>どちらも書き込む前に求める</b>。商を先に書き込むと、割られる側が
     * 商の受取項目と同じだったときに剰余が狂う。
     *
     * <p>剰余は切り捨てた商から求める。商に {@code ROUNDED} を書いても、剰余の計算に使う
     * 商は丸めない。丸めた商から求めると、商と剰余を足し戻したときに元の値にならない。
     */
    private void planDivideRemainder(Statement.DivideRemainder statement, List<Runnable> body) {
        Runnable dividend = planSourceDecimal(statement.dividend(), statement.origin());
        Runnable divisor = planSourceDecimal(statement.divisor(), statement.origin());
        Picture quotientPicture = statement.quotient().reference().item().picture();
        if (dividend == null || divisor == null || quotientPicture == null) {
            return;
        }
        int quotientScale = quotientPicture.scale();
        String rounding = statement.quotient().rounded()
                ? "NEAREST_AWAY_FROM_ZERO"
                : "TRUNCATION";

        int quotient = nextLocal++;
        int remainder = nextLocal++;
        Runnable compute = () -> {
            dividend.run();
            divisor.run();
            push(quotientScale);
            loadRounding(rounding);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "divide",
                    "(" + DECIMAL + DECIMAL + "I" + Type.getDescriptor(CobolRounding.class) + ")"
                            + DECIMAL, false);
            run.visitVarInsn(Opcodes.ASTORE, quotient);
            dividend.run();
            divisor.run();
            push(quotientScale);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "remainder",
                    "(" + DECIMAL + DECIMAL + "I)" + DECIMAL, false);
            run.visitVarInsn(Opcodes.ASTORE, remainder);
        };

        if (!statement.isChecked()) {
            Runnable storeQuotient = planStore(statement.quotient().reference(),
                    () -> run.visitVarInsn(Opcodes.ALOAD, quotient), rounding, statement.origin());
            Runnable storeRemainder = planStore(statement.remainder().reference(),
                    () -> run.visitVarInsn(Opcodes.ALOAD, remainder),
                    statement.remainder().rounded() ? "NEAREST_AWAY_FROM_ZERO" : "TRUNCATION",
                    statement.origin());
            if (storeQuotient == null || storeRemainder == null) {
                return;
            }
            body.add(() -> {
                compute.run();
                storeQuotient.run();
                storeRemainder.run();
            });
            return;
        }
        planCheckedDivideRemainder(statement, compute, quotient, remainder, body);
    }

    /**
     * {@code ON SIZE ERROR} つきの {@code DIVIDE ... REMAINDER}。
     *
     * <p>0 除算は商と剰余の<b>どちらの計算でも</b>起きる。計算をまとめて {@code try} で囲み、
     * ランタイムが投げる {@code DecimalDivideException} を条件へ読み替える。
     *
     * <p>商が受取項目に収まらなければ<b>剰余も書き込まない</b>。入らなかった商から求めた
     * 剰余に意味はないためである。剰余だけが収まらなければ、剰余だけが変わらずに残る。
     */
    private void planCheckedDivideRemainder(Statement.DivideRemainder statement, Runnable compute,
                                            int quotient, int remainder, List<Runnable> body) {
        int flag = nextLocal++;
        Runnable storeQuotient = planCheckedStore(statement.quotient(), quotient, flag,
                statement.origin());
        Runnable storeRemainder = planCheckedStore(statement.remainder(), remainder, flag,
                statement.origin());
        if (storeQuotient == null || storeRemainder == null) {
            return;
        }
        List<Runnable> operations = List.of(() -> {
            Label start = new Label();
            Label caught = new Label();
            Label handler = new Label();
            Label computed = new Label();
            run.visitTryCatchBlock(start, caught, handler,
                    Type.getInternalName(DecimalDivideException.class));
            run.visitLabel(start);
            compute.run();
            run.visitLabel(caught);
            run.visitJumpInsn(Opcodes.GOTO, computed);
            run.visitLabel(handler);
            run.visitInsn(Opcodes.POP);
            run.visitInsn(Opcodes.ICONST_1);
            run.visitVarInsn(Opcodes.ISTORE, flag);
            run.visitInsn(Opcodes.ACONST_NULL);
            run.visitVarInsn(Opcodes.ASTORE, quotient);
            run.visitInsn(Opcodes.ACONST_NULL);
            run.visitVarInsn(Opcodes.ASTORE, remainder);
            run.visitLabel(computed);

            // 0 除算なら受取項目には触れない
            Label stored = new Label();
            run.visitVarInsn(Opcodes.ILOAD, flag);
            run.visitJumpInsn(Opcodes.IFNE, stored);
            storeQuotient.run();
            // 商が収まらなければ剰余も書かない。入らなかった商から求めた剰余に意味はない
            run.visitVarInsn(Opcodes.ILOAD, flag);
            run.visitJumpInsn(Opcodes.IFNE, stored);
            storeRemainder.run();
            run.visitLabel(stored);
        });
        planSizeErrorBranch(operations, statement.sizeError(), flag, body);
    }

    /**
     * 1 つの {@code ON SIZE ERROR} を分け合う算術文の集まりを組み立てる。
     *
     * <p>指定がなければ、ただ順に出すだけである。指定があれば<b>旗を 1 つだけ作り</b>、
     * すべての計算で共有する。組ごとに条件文を通ってしまわないようにするためである。
     */
    private void planArithmeticGroup(Statement.ArithmeticGroup group, List<Runnable> body) {
        if (group.sizeError() == null) {
            group.operations().forEach(operation -> planArithmetic(operation, body));
            return;
        }
        int flag = nextLocal++;
        List<Runnable> operations = new ArrayList<>();
        for (Statement.Arithmetic operation : group.operations()) {
            Runnable planned = planCheckedTarget(operation, operation.targets().get(0), flag);
            if (planned == null) {
                return;
            }
            operations.add(planned);
        }
        planSizeErrorBranch(operations, group.sizeError(), flag, body);
    }

    private Runnable planCheckedTarget(Statement.Arithmetic statement,
                                       Statement.Arithmetic.Target target, int flag) {
        Runnable offset = planAddress(target.reference(), statement.origin());
        if (offset == null) {
            return null;
        }
        DataItem item = target.reference().item();
        String field = numericItemConstant(item, statement.origin());
        if (field == null || item.picture() == null) {
            return null;
        }
        int scale = item.picture().scale();
        String rounding = target.rounded() ? "NEAREST_AWAY_FROM_ZERO" : "TRUNCATION";

        // 被演算子を先に局所変数へ取る。除数を調べてから割るためである
        List<Integer> slots = new ArrayList<>();
        List<Runnable> loads = new ArrayList<>();
        for (Operand operand : statement.operands()) {
            Runnable push = planSourceDecimal(operand, statement.origin());
            if (push == null) {
                return null;
            }
            int slot = nextLocal++;
            slots.add(slot);
            loads.add(() -> {
                push.run();
                run.visitVarInsn(Opcodes.ASTORE, slot);
            });
        }
        int folded = nextLocal++;
        boolean foldDivides = statement.fold() == Statement.Arithmetic.Operator.DIVIDE;
        boolean accumulateDivides =
                statement.accumulate() == Statement.Arithmetic.Operator.DIVIDE;

        return () -> {
            Label failed = new Label();
            Label done = new Label();
            loads.forEach(Runnable::run);
            if (foldDivides) {
                // 2 個目以降が除数になる
                for (int i = 1; i < slots.size(); i++) {
                    emitZeroCheck(slots.get(i), failed);
                }
            }
            for (int i = 0; i < slots.size(); i++) {
                run.visitVarInsn(Opcodes.ALOAD, slots.get(i));
                if (i > 0) {
                    emitOperator(statement.fold(), scale, rounding);
                }
            }
            run.visitVarInsn(Opcodes.ASTORE, folded);
            if (accumulateDivides) {
                emitZeroCheck(folded, failed);
            }

            if (statement.accumulate() != null) {
                run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
                offset.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readNumeric",
                        "(" + NUMERIC_ITEM + "L" + STORAGE + ";I)" + DECIMAL, false);
                run.visitVarInsn(Opcodes.ALOAD, folded);
                emitOperator(statement.accumulate(), scale, rounding);
            } else {
                run.visitVarInsn(Opcodes.ALOAD, folded);
            }
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            offset.run();
            loadRounding(rounding);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeChecked",
                    "(" + DECIMAL + NUMERIC_ITEM + "L" + STORAGE + ";I"
                            + Type.getDescriptor(CobolRounding.class) + ")Z", false);
            run.visitJumpInsn(Opcodes.IFEQ, done);
            run.visitLabel(failed);
            run.visitInsn(Opcodes.ICONST_1);
            run.visitVarInsn(Opcodes.ISTORE, flag);
            run.visitLabel(done);
        };
    }

    /** 除数が 0 なら {@code failed} へ飛ぶ。 */
    private void emitZeroCheck(int slot, Label failed) {
        run.visitVarInsn(Opcodes.ALOAD, slot);
        run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "isZero", "(" + DECIMAL + ")Z", false);
        run.visitJumpInsn(Opcodes.IFNE, failed);
    }

    /** 被演算子を左から畳む命令を積む。 */
    private boolean planFold(Statement.Arithmetic statement, List<Runnable> value, int scale,
                             String rounding) {
        boolean first = true;
        for (Operand operand : statement.operands()) {
            Runnable push = planSourceDecimal(operand, statement.origin());
            if (push == null) {
                return false;
            }
            value.add(push);
            if (!first) {
                value.add(() -> emitOperator(statement.fold(), scale, rounding));
            }
            first = false;
        }
        return true;
    }

    private void emitOperator(Statement.Arithmetic.Operator operator, int scale, String rounding) {
        if (operator == Statement.Arithmetic.Operator.DIVIDE) {
            push(scale);
            loadRounding(rounding);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "divide",
                    "(" + DECIMAL + DECIMAL + "I" + Type.getDescriptor(CobolRounding.class) + ")"
                            + DECIMAL, false);
            return;
        }
        String name = switch (operator) {
            case ADD -> "add";
            case SUBTRACT -> "subtract";
            case MULTIPLY -> "multiply";
            case DIVIDE -> throw new IllegalStateException("handled above");
        };
        run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, name,
                "(" + DECIMAL + DECIMAL + ")" + DECIMAL, false);
    }

    private void loadRounding(String name) {
        run.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(CobolRounding.class), name,
                Type.getDescriptor(CobolRounding.class));
    }

    // ---- 位置と長さ ----

    /**
     * 参照の位置を {@code int} として積む命令。
     *
     * <p>添字がすべて定数なら定数を積むだけである。データ項目で書いた添字が混ざれば、
     * <b>定数の分をまとめてから、変数の分を実行時に足す</b>。
     *
     * <pre>
     * 位置 = 項目の変位 + Σ (添字 - 1) x その表の 1 回分の長さ
     * </pre>
     */
    /**
     * 記憶域と、その中の位置を<b>この順に</b>積む命令。
     *
     * <p>ランタイムの入口はどれも「記憶域」「位置」をこの順で取る。1 つの組にしてあるので、
     * <b>記憶域が項目ごとに違っても</b>呼び出し側は変わらない。
     *
     * <p>連絡節の項目は記憶域を持たない。呼ぶ側から渡された領域が実体であり、
     * 記憶域も位置もその領域から取る。
     */
    private Runnable planAddress(DataReference reference, Origin origin) {
        Runnable offset = planOffset(reference, origin);
        if (offset == null) {
            return null;
        }
        DataItem record = reference.item().record();
        if (record.section() != DataSection.LINKAGE) {
            return () -> {
                run.visitVarInsn(Opcodes.ALOAD, 1);
                offset.run();
            };
        }
        int index = parameters.indexOf(record);
        if (index < 0) {
            report(origin, "a LINKAGE SECTION item is not listed in PROCEDURE DIVISION USING: "
                    + describe(record));
            return null;
        }
        return () -> {
            emitArgument(index);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA_VIEW, "storage",
                    "()L" + Type.getInternalName(Storage.class) + ";", false);
            // 渡された領域の始まりからの位置になる
            emitArgument(index);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA_VIEW, "offset", "()I", false);
            offset.run();
            run.visitInsn(Opcodes.IADD);
        };
    }

    /** {@code USING} の {@code index} 番目に渡された領域を積む。 */
    private void emitArgument(int index) {
        run.visitVarInsn(Opcodes.ALOAD, ARGUMENTS_LOCAL);
        push(index);
        run.visitInsn(Opcodes.AALOAD);
    }

    private Runnable planOffset(DataReference reference, Origin origin) {
        OptionalInt constant = reference.absoluteOffset();
        if (constant.isPresent()) {
            int value = constant.getAsInt();
            return () -> push(value);
        }

        List<DataItem> tables = DataReference.tableChain(reference.item());
        int fixed = reference.item().record().base() + reference.item().offset();
        List<Runnable> variable = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            DataReference.Subscript subscript = reference.subscripts().get(i);
            int unit = tables.get(i).length();
            if (subscript instanceof DataReference.Subscript.Constant value) {
                fixed += (value.value() - 1) * unit;
                continue;
            }
            DataReference inner = ((DataReference.Subscript.Variable) subscript).reference();
            Runnable push = planSourceDecimal(new Operand.Reference(inner), origin);
            if (push == null) {
                return null;
            }
            DataItem table = tables.get(i);
            variable.add(() -> {
                push.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
                emitSubscriptCheck(table);
                run.visitInsn(Opcodes.ICONST_1);
                run.visitInsn(Opcodes.ISUB);
                push(unit);
                run.visitInsn(Opcodes.IMUL);
                run.visitInsn(Opcodes.IADD);
            });
        }
        if (reference.refMod() != null) {
            Runnable leftmost = planRefModLeftmost(reference, origin);
            if (leftmost == null) {
                return null;
            }
            if (reference.refMod().leftmost() instanceof DataReference.Subscript.Constant value) {
                fixed += value.value() - 1;
            } else {
                variable.add(leftmost);
            }
        }
        int base = fixed;
        return () -> {
            push(base);
            variable.forEach(Runnable::run);
        };
    }

    /**
     * 添字の範囲検査を積む ({@code SSRANGE} 指定時のみ)。
     *
     * <p>検査は<b>積まれた値をそのまま返す</b>形にしてある。位置の計算の途中に挟むだけで
     * 済み、指定がないときの命令列がまったく変わらない。
     */
    private void emitSubscriptCheck(DataItem table) {
        if (!rangeChecks) {
            return;
        }
        push(table.occurs());
        run.visitLdcInsn(describe(table));
        run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "checkSubscript",
                "(IILjava/lang/String;)I", false);
    }

    /** 部分参照の範囲検査を積む ({@code SSRANGE} 指定時のみ)。 */
    private void emitRefModCheck(DataReference reference, Origin origin) {
        if (!rangeChecks) {
            return;
        }
        OptionalInt length = reference.constantLength();
        if (length.isEmpty()) {
            // 長さが定数でない形はそもそも生成できない (暫定判断 P-027)
            return;
        }
        push(length.getAsInt());
        push(reference.item().length());
        run.visitLdcInsn(describe(reference.item()));
        run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "checkRefMod",
                "(IIILjava/lang/String;)I", false);
    }

    /** 診断に出す項目の名前。無名項目は {@code FILLER} である。 */
    private static String describe(DataItem item) {
        return item.name() == null ? "FILLER" : item.name();
    }

    /** 部分参照の開始位置を、すでに積まれた位置へ足す命令。 */
    private Runnable planRefModLeftmost(DataReference reference, Origin origin) {
        if (reference.refMod().leftmost() instanceof DataReference.Subscript.Constant) {
            return () -> { };
        }
        DataReference inner = ((DataReference.Subscript.Variable)
                reference.refMod().leftmost()).reference();
        Runnable push = planSourceDecimal(new Operand.Reference(inner), origin);
        if (push == null) {
            return null;
        }
        return () -> {
            push.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
            emitRefModCheck(reference, origin);
            run.visitInsn(Opcodes.ICONST_1);
            run.visitInsn(Opcodes.ISUB);
            run.visitInsn(Opcodes.IADD);
        };
    }

    /**
     * 参照の長さ。<b>長さは翻訳時に決まっていなければならない</b>。
     * 部分参照の長さにデータ項目を書いた場合は、まだ生成できない (暫定判断 P-027)。
     */
    private OptionalInt lengthOf(DataReference reference, Origin origin) {
        OptionalInt length = reference.constantLength();
        if (length.isEmpty()) {
            report(origin, "a reference modification whose length is not a constant"
                    + " is not supported yet");
        }
        return length;
    }

    // ---- 送出側 ----

    /** 送出側をバイト列として積む命令。 */
    private Runnable planSourceBytes(Operand source, Origin origin, int targetLength) {
        if (source instanceof Operand.Literal literal) {
            byte[] bytes = literalBytes(literal.value(), targetLength);
            String field = bytesConstant(bytes);
            return () -> run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, "[B");
        }
        DataReference reference = ((Operand.Reference) source).reference();
        Runnable offset = planAddress(reference, origin);
        OptionalInt length = lengthOf(reference, origin);
        if (offset == null || length.isEmpty()) {
            return null;
        }
        return () -> {
            offset.run();
            push(length.getAsInt());
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "read",
                    "(L" + STORAGE + ";II)[B", false);
        };
    }

    /** 送出側を {@link Decimal} として積む命令。 */
    private Runnable planSourceDecimal(Operand source, Origin origin) {
        if (source instanceof Operand.Literal literal) {
            Decimal value = decimalOf(literal.value(), origin);
            if (value == null) {
                return null;
            }
            String field = decimalConstant(value);
            return () -> run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, DECIMAL);
        }
        DataReference reference = ((Operand.Reference) source).reference();
        Runnable offset = planAddress(reference, origin);
        OptionalInt length = lengthOf(reference, origin);
        if (offset == null || length.isEmpty()) {
            return null;
        }
        if (!DataCategory.of(reference).isNumeric()) {
            // 英数字項目から数値項目への転記。送出側は符号なしの整数として読む
            return () -> {
                offset.run();
                push(length.getAsInt());
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readAsInteger",
                        "(L" + STORAGE + ";II" + CODE_PAGE + ")" + DECIMAL, false);
            };
        }
        String field = numericItemConstant(reference.item(), origin);
        if (field == null) {
            return null;
        }
        return () -> {
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            offset.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readNumeric",
                    "(" + NUMERIC_ITEM + "L" + STORAGE + ";I)" + DECIMAL, false);
        };
    }

    private Decimal decimalOf(LiteralValue value, Origin origin) {
        if (value instanceof LiteralValue.Number number) {
            return number.value();
        }
        if (value instanceof LiteralValue.Figure figure
                && figure.constant() == LiteralValue.FigurativeConstant.ZERO) {
            return Decimal.zero(0);
        }
        report(origin, "a numeric receiver requires a numeric literal");
        return null;
    }

    /** 定数を受取項目の長さまで広げたバイト列。図形定数と {@code ALL} はここで埋める。 */
    private byte[] literalBytes(LiteralValue value, int targetLength) {
        if (value instanceof LiteralValue.Text text) {
            return codePage.encode(text.text());
        }
        if (value instanceof LiteralValue.Repeated repeated) {
            byte[] unit = codePage.encode(repeated.text());
            byte[] out = new byte[targetLength];
            for (int i = 0; i < targetLength; i++) {
                out[i] = unit[i % unit.length];
            }
            return out;
        }
        if (value instanceof LiteralValue.Number number) {
            return codePage.encode(number.value().toBigDecimal().toPlainString());
        }
        byte[] out = new byte[targetLength];
        Arrays.fill(out, figureByte(((LiteralValue.Figure) value).constant()));
        return out;
    }

    private byte figureByte(LiteralValue.FigurativeConstant constant) {
        return switch (constant) {
            case ZERO -> codePage.digit(0);
            case SPACE -> codePage.space();
            case HIGH_VALUE -> (byte) 0xFF;
            case LOW_VALUE, NULL -> (byte) 0x00;
            case QUOTE -> codePage.ch('"');
        };
    }

    // ---- 定数 ----

    private String numericItemConstant(DataItem item, Origin origin) {
        if (item.picture() == null) {
            report(origin, "floating-point items are not supported by the generator yet");
            return null;
        }
        Usage usage = item.usage() == null ? Usage.DISPLAY : item.usage();
        String key = "N:" + item.picture().source() + ":" + usage + ":" + item.signPosition();
        return constants.computeIfAbsent(key, k -> {
            String name = "N" + constants.size();
            return new Constant(name, NUMERIC_ITEM, () -> {
                clinit.visitLdcInsn(item.picture().source());
                clinit.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(Usage.class),
                        usage.name(), Type.getDescriptor(Usage.class));
                // 通貨記号は翻訳時に決まる。PICTURE の解釈がこれに依る
                clinit.visitLdcInsn((int) currency);
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(NumericItem.class), "of",
                        "(Ljava/lang/String;" + Type.getDescriptor(Usage.class) + "C)"
                                + NUMERIC_ITEM, false);
                if (item.signPosition() != SignPosition.UNSIGNED) {
                    clinit.visitFieldInsn(Opcodes.GETSTATIC,
                            Type.getInternalName(SignPosition.class),
                            item.signPosition().name(),
                            Type.getDescriptor(SignPosition.class));
                    clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            Type.getInternalName(NumericItem.class), "withSignPosition",
                            "(" + Type.getDescriptor(SignPosition.class)
                                    + ")" + NUMERIC_ITEM, false);
                }
            });
        }).name();
    }

    private String pictureConstant(Picture picture) {
        return constants.computeIfAbsent("P:" + picture.source(), k -> {
            String name = "P" + constants.size();
            return new Constant(name, PICTURE, () -> {
                clinit.visitLdcInsn(picture.source());
                clinit.visitLdcInsn((int) currency);
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(PictureParser.class), "parse",
                        "(Ljava/lang/String;C)" + PICTURE, false);
            });
        }).name();
    }

    private String bytesConstant(byte[] bytes) {
        String latin1 = new String(bytes, StandardCharsets.ISO_8859_1);
        return constants.computeIfAbsent("B:" + latin1, k -> {
            String name = "B" + constants.size();
            return new Constant(name, "[B", () -> {
                clinit.visitLdcInsn(latin1);
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "bytes",
                        "(Ljava/lang/String;)[B", false);
            });
        }).name();
    }

    private String decimalConstant(Decimal value) {
        String text = value.toBigDecimal().toPlainString();
        return constants.computeIfAbsent("D:" + text, k -> {
            String name = "D" + constants.size();
            return new Constant(name, DECIMAL, () -> {
                clinit.visitLdcInsn(text);
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(Decimal.class), "parse",
                        "(Ljava/lang/String;)" + DECIMAL, false);
            });
        }).name();
    }

    private void emitStaticInitializer() {
        // フィールドの宣言を先に済ませてから静的初期化子を書く
        for (Constant constant : constants.values()) {
            if (constant.emit() != null) {
                writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        constant.name(), constant.descriptor(), null, null).visitEnd();
            }
        }
        clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();

        clinit.visitLdcInsn(new String(initialStorageBytes, StandardCharsets.ISO_8859_1));
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "bytes",
                "(Ljava/lang/String;)[B", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, "INITIAL", "[B");

        for (Constant constant : constants.values()) {
            if (constant.emit() == null) {
                continue;
            }
            constant.emit().run();
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, constant.name(),
                    constant.descriptor());
        }
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
    }

    private void loadCodePage() {
        run.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(CodePages.class), "DEFAULT",
                CODE_PAGE);
    }

    private void push(int value) {
        push(run, value);
    }

    private static void push(MethodVisitor method, int value) {
        method.visitLdcInsn(value);
    }

    /**
     * {@code GO TO} を組み立てる。
     *
     * <p>段落のメソッドから<b>飛び先の番号を返して抜ける</b>だけである。段落の途中でも
     * 入れ子の {@code IF} や {@code PERFORM} の中でも、その場で {@code return} できる。
     * これが段落を別々のメソッドにしている構えの効いているところである。
     */
    private void planGoTo(Statement.GoTo statement, List<Runnable> body) {
        int target = paragraphNames.indexOf(statement.target());
        if (target < 0) {
            report(statement.origin(), "undefined paragraph: " + statement.target());
            return;
        }
        body.add(() -> {
            push(target);
            run.visitInsn(Opcodes.IRETURN);
        });
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }
}
