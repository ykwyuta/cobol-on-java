package dev.cobolonjava.compiler.codegen;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.semantic.Condition;
import dev.cobolonjava.compiler.semantic.DataCategory;
import dev.cobolonjava.compiler.semantic.Intrinsic;
import dev.cobolonjava.compiler.semantic.DataItem;
import dev.cobolonjava.compiler.semantic.DataReference;
import dev.cobolonjava.compiler.semantic.DataLayout;
import dev.cobolonjava.compiler.semantic.DataSection;
import dev.cobolonjava.compiler.semantic.Expression;
import dev.cobolonjava.compiler.semantic.FileDescription;
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
import dev.cobolonjava.runtime.function.Intrinsics;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.codepage.CollatingSequence;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.decimal.DecimalDivideException;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.Organization;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.abend.StorageMap;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.picture.PictureParser;
import dev.cobolonjava.runtime.sort.SortKey;
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
    private static final String STORAGE_MAP = "L" + Type.getInternalName(StorageMap.class) + ";";
    private static final String SUPPORT = Type.getInternalName(ProgramSupport.class);
    private static final String STORAGE = Type.getInternalName(Storage.class);
    private static final String CODE_PAGE = Type.getDescriptor(CodePage.class);
    private static final String NUMERIC_ITEM = Type.getDescriptor(NumericItem.class);
    private static final String CLASS_TEST =
            Type.getInternalName(dev.cobolonjava.runtime.verb.ClassTest.class);
    private static final String PICTURE = Type.getDescriptor(Picture.class);
    private static final String DECIMAL = Type.getDescriptor(Decimal.class);
    private static final String INTRINSICS = Type.getInternalName(Intrinsics.class);
    private static final String COLLATING = Type.getDescriptor(CollatingSequence.class);
    private static final String CLAUSE = Type.getDescriptor(InspectScan.Clause.class);
    private static final String REGION = Type.getDescriptor(Region.class);

    private final String className;
    private final CodePage codePage;
    /** {@code SSRANGE} が効いているか。効いていれば添字と部分参照の位置を実行時に検査する。 */
    private final boolean rangeChecks;
    private final SpecialNames specialNames;
    /** PICTURE の通貨記号。{@code CURRENCY SIGN IS} で差し替えられる。 */
    private char currency = SpecialNames.DEFAULT_CURRENCY;
    /** PICTURE の小数点。{@code DECIMAL-POINT IS COMMA} で差し替えられる。 */
    private char decimalPoint = '.';

    /**
     * このプログラムの照合順序 (要件 FR-054)。
     *
     * <p>{@code PROGRAM COLLATING SEQUENCE} が書かれ、それがコードページのバイト値の
     * 並びと違うときだけ表が入る。{@code null} なら既定の比較を出す。
     */
    private byte[] collating;

    /**
     * 翻訳を始めた時刻 (要件 FR-070)。{@code FUNCTION WHEN-COMPILED} が返す。
     *
     * <p>翻訳時に決まる値なので、生成したクラスの定数として持たせる。
     */
    private final java.time.ZonedDateTime compiledAt = java.time.ZonedDateTime.now();
    /** {@code PROCEDURE DIVISION USING} に並べた 01 レベル。連絡節の位置決めに使う。 */
    private List<DataItem> parameters = List.of();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /** 静的初期化子で作る定数。綴りから field 名を引く。 */
    private final Map<String, Constant> constants = new LinkedHashMap<>();

    /**
     * 局所変数の 0 番は {@code this}、1 番は記憶域、2 番は実行時の入口、3 番は引数の並びである。
     */
    private static final int FIRST_FREE_LOCAL = 4;
    /** {@code FILE STATUS} の項目の長さ。2 文字の英数字である。 */
    private static final int FILE_STATUS_LENGTH = 2;
    private static final String CONTEXT = Type.getDescriptor(ProgramContext.class);

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
    /**
     * 原文のファイル名。クラスファイルの {@code SourceFile} になる。
     *
     * <p>行番号表へ入れるのは<b>このファイルから来た文だけ</b>である。{@code COPY} で
     * 取り込んだ文の行番号は写本の中の行であり、ここへ混ぜると別のファイルの行を
     * このファイルの行として指してしまう (暫定判断 P-050)。
     */
    private final String sourceName;
    /** 作業場所の割り付け。異常終了の覚え書きが項目名で書けるようにする (要件 FR-142)。 */
    private DataLayout layout;
    private List<String> paragraphNames = new ArrayList<>();

    /** いま組み立てている段落の名前。行き先の無い {@code GO TO} の文面に使う。 */
    private String currentParagraphName = "";

    /**
     * 行き先の無い {@code GO TO} を通ったときに止める命令を出す。
     *
     * <p>例外を<b>返して</b>もらってから投げるのは、投げたあとが到達不能だと
     * 検証器に伝わるようにするためである。
     */
    private void emitUnalteredGoTo(String paragraph) {
        run.visitLdcInsn(paragraph == null ? "" : paragraph);
        run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "unalteredGoTo",
                "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
        run.visitInsn(Opcodes.ATHROW);
    }
    private List<ProcedureBuilder.Section> sections = List.of();
    private List<ProcedureBuilder.Declarative> declaratives = List.of();
    /** 通常の流れが始まる段落の番号。宣言部分はそれより前にある。 */
    private int firstNormal;
    private int nextLocal = FIRST_FREE_LOCAL;
    private MethodVisitor run;
    private MethodVisitor clinit;
    private byte[] initialStorageBytes;

    private ProgramGenerator(String className, String sourceName, CodePage codePage,
                             CompilerOptions options, SpecialNames specialNames) {
        this.className = className;
        this.sourceName = sourceName;
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
            return !Diagnostic.blocking(diagnostics);
        }
    }

    /** プログラムを生成する。 */
    public static Result generate(String programName, ProcedureBuilder.Result procedure,
                                  InitialImage.Result image) {
        return generate(programName, procedure, image, CompilerOptions.NONE,
                SpecialNames.standard());
    }

    /** 原文のファイル名と割り付けまで指定してプログラムを生成する (要件 FR-142)。 */
    public static Result generate(String programName, String sourceName,
                                  ProcedureBuilder.Result procedure, InitialImage.Result image,
                                  DataLayout layout, CompilerOptions options,
                                  SpecialNames specialNames) {
        ProgramGenerator generator = new ProgramGenerator(classNameOf(programName), sourceName,
                CodePages.DEFAULT, options, specialNames);
        generator.layout = layout;
        return generator.emit(procedure, image);
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
        return new ProgramGenerator(classNameOf(programName), null, codePage, options,
                specialNames).emit(procedure, image);
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
        decimalPoint = specialNames.decimalPoint();
        collating = specialNames.collatingSequence();
        writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        internal = className.replace('.', '/');
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internal, null, "java/lang/Object",
                new String[] {Type.getInternalName(CobolProgram.class)});
        // 原文のファイル名を埋める。異常終了の覚え書きが原文の行を指せるようになる
        // (要件 FR-142)。行番号は文ごとに planStatements が入れる
        if (sourceName != null) {
            writer.visitSource(sourceName, null);
        }

        emitInitialStorage(image.storage());
        emitStorageMap();
        List<List<Runnable>> paragraphs = planParagraphs(procedure);
        if (!diagnostics.isEmpty()) {
            return new Result(className, null, List.copyOf(diagnostics));
        }
        // 飛び先の表があるかどうかは、段落を読んでからでないと決まらない
        emitConstructor(writer, internal);
        declareAlterTables();
        emitRun(firstNormal, paragraphs.size());
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

    private void emitConstructor(ClassWriter writer, String internal) {
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (hasAlterable) {
            // 飛び先の表は実行のたびに書き換えられるので、複製して持つ
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitFieldInsn(Opcodes.GETSTATIC, internal, ALTER_INITIAL, "[I");
            init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;",
                    false);
            init.visitTypeInsn(Opcodes.CHECKCAST, "[I");
            init.visitFieldInsn(Opcodes.PUTFIELD, internal, ALTERED, "[I");
        }
        if (hasIndependentSegment && hasAlterable) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitInsn(Opcodes.ICONST_M1);
            init.visitFieldInsn(Opcodes.PUTFIELD, internal, CURRENT_SEGMENT, "I");
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    /** 書き換えられたあとの飛び先を持つ表。 */
    private static final String ALTERED = "altered$";
    /** 書かれたままの飛び先。 */
    private static final String ALTER_INITIAL = "ALTER_INITIAL$";
    /** 段落ごとの段番号。 */
    private static final String SEGMENTS = "SEGMENTS$";
    /** いま動いている段の番号。 */
    private static final String CURRENT_SEGMENT = "segment$";

    /**
     * {@code ALTER} のための表を出す (要件 FR-063)。
     *
     * <p>書き換えられる段落が 1 つも無ければ何も出さない。ふつうのプログラムの
     * 生成結果は<b>今までと同じ</b>である。
     */
    private void declareAlterTables() {
        if (!hasAlterable) {
            return;
        }
        writer.visitField(Opcodes.ACC_PRIVATE, ALTERED, "[I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                ALTER_INITIAL, "[I", null, null).visitEnd();
        if (hasIndependentSegment) {
            writer.visitField(Opcodes.ACC_PRIVATE, CURRENT_SEGMENT, "I", null, null).visitEnd();
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    SEGMENTS, "[I", null, null).visitEnd();
        }
    }

    /** 飛び先の表をクラスの初期化で組み立てる。 */
    private void initAlterTables() {
        if (!hasAlterable) {
            return;
        }
        emitIntArray(ALTER_INITIAL, alterInitial);
        if (hasIndependentSegment) {
            emitIntArray(SEGMENTS, segments);
        }
    }

    /** クラスの初期化で {@code int} の配列を組み立てる。 */
    private void emitIntArray(String field, int[] values) {
        push(clinit, values.length);
        clinit.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        for (int i = 0; i < values.length; i++) {
            clinit.visitInsn(Opcodes.DUP);
            push(clinit, i);
            push(clinit, values[i]);
            clinit.visitInsn(Opcodes.IASTORE);
        }
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, field, "[I");
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
        planAlterable(procedure.paragraphs());
        sections = procedure.sections();
        declaratives = procedure.declaratives();
        firstNormal = procedure.firstNormalParagraph();
        List<List<Runnable>> planned = new ArrayList<>();
        for (ProcedureBuilder.Paragraph paragraph : procedure.paragraphs()) {
            nextLocal = FIRST_FREE_LOCAL;
            currentParagraphName = paragraph.name();
            planned.add(planStatements(paragraph.statements()));
        }
        return planned;
    }

    /**
     * この文が原文のどの行から来たかを、クラスファイルの行番号表へ入れる (要件 FR-142)。
     *
     * <p>異常終了したとき「どの文で止まったか」を言えなければ、診断は役に立たない。
     * 対応表を自分で持つのではなく<b>クラスファイルの行番号表を使う</b>。JVM の呼び出し
     * 履歴がそのまま原文の行を指すようになり、対応表が本体とずれる余地が無い。
     *
     * <p>{@code COPY} で取り込んだ行は取り込み元の行を指す。{@link Origin} が
     * 1 文字ごとに出自を持っているので、写した先ではなく<b>書いてある場所</b>になる。
     */
    private void planLine(Origin origin, List<Runnable> body) {
        if (origin == null || origin.line() <= 0 || sourceName == null
                || !sourceName.equals(origin.fileName())) {
            return;
        }
        int line = origin.line();
        body.add(() -> {
            Label here = new Label();
            run.visitLabel(here);
            run.visitLineNumber(line, here);
        });
    }

    private List<Runnable> planStatements(List<Statement> statements) {
        List<Runnable> body = new ArrayList<>();
        for (Statement statement : statements) {
            planLine(statement.origin(), body);
            if (statement instanceof Statement.Sequence sequence) {
                // 意味解析で展開された文の並び。そのまま並べて出す
                body.addAll(planStatements(sequence.statements()));
            } else if (statement instanceof Statement.Sentence sentence) {
                planSentence(sentence, body);
            } else if (statement instanceof Statement.NextSentence) {
                planNextSentence(statement.origin(), body);
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
            } else if (statement instanceof Statement.ExitProgram) {
                // 呼ばれていれば戻り、主プログラムなら何もしない。決めるのは実行時である
                body.add(() -> {
                    run.visitVarInsn(Opcodes.ALOAD, 2);
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "exitProgram",
                            "(" + CONTEXT + ")V", false);
                });
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
            } else if (statement instanceof Statement.Open open) {
                planOpen(open, body);
            } else if (statement instanceof Statement.Close close) {
                planClose(close, body);
            } else if (statement instanceof Statement.Read read) {
                planRead(read, body);
            } else if (statement instanceof Statement.Write write) {
                planWrite(write, body);
            } else if (statement instanceof Statement.Rewrite rewrite) {
                planRewrite(rewrite, body);
            } else if (statement instanceof Statement.Delete delete) {
                planDelete(delete, body);
            } else if (statement instanceof Statement.Start start) {
                planStart(start, body);
            } else if (statement instanceof Statement.Sort sort) {
                planSort(sort, body);
            } else if (statement instanceof Statement.Release release) {
                planRelease(release, body);
            } else if (statement instanceof Statement.Return returned) {
                planReturn(returned, body);
            } else if (statement instanceof Statement.GoTo goTo) {
                planGoTo(goTo, body);
            } else if (statement instanceof Statement.GoToDepending depending) {
                planGoToDepending(depending, body);
            } else if (statement instanceof Statement.Alter alter) {
                planAlter(alter, body);
            } else if (statement instanceof Statement.SetSwitch set) {
                body.add(() -> {
                    run.visitVarInsn(Opcodes.ALOAD, 2);
                    push(set.index());
                    run.visitInsn(set.on() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "setSwitch",
                            "(" + CONTEXT + "IZ)V", false);
                });
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

    // ---- ファイル入出力 ----

    /**
     * {@code OPEN} を組み立てる (要件 FR-102)。
     *
     * <p>ファイルごとに 1 回ずつ呼ぶ。1 つの文にいくつ並べても、それぞれが独立に
     * 開かれ、独立に状態コードを返す。
     */
    private void planOpen(Statement.Open statement, List<Runnable> body) {
        for (Statement.Open.Opened opened : statement.files()) {
            FileDescription file = opened.file();
            int slot = nextLocal++;
            Runnable status = planFileStatus(file, statement.origin(), slot, false, false,
                    opened.mode());
            if (status == null) {
                return;
            }
            int mode = opened.mode().ordinal();
            int organization = file.organization().ordinal();
            int format = file.format().ordinal();
            int length = file.recordLength();
            boolean optional = file.optional();
            boolean indexed = file.organization() == Organization.INDEXED;
            body.add(() -> {
                emitFileName(file);
                push(mode);
                if (!indexed) {
                    push(organization);
                }
                push(format);
                push(length);
                run.visitInsn(optional ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                if (indexed) {
                    emitKeyPositions(file);
                }
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS,
                        indexed ? "openIndexed" : "open",
                        "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;II"
                                + (indexed ? "" : "I") + "IZ" + (indexed ? "[I" : "") + ")[B",
                        false);
                run.visitVarInsn(Opcodes.ASTORE, slot);
                status.run();
                // 開けば頁は初めからである。形を読み直し、行数を 0 に戻す
                if (file.linage() != null) {
                    emitLinageSetup(file.linage(), statement.origin());
                }
            });
        }
    }

    /** {@code CLOSE} を組み立てる (要件 FR-102)。 */
    private void planClose(Statement.Close statement, List<Runnable> body) {
        for (Statement.Close.Closed closed : statement.files()) {
            FileDescription file = closed.file();
            int slot = nextLocal++;
            Runnable status = planFileStatus(file, statement.origin(), slot, false, false);
            if (status == null) {
                return;
            }
            body.add(() -> {
                emitFileName(file);
                push(closed.lock() ? 1 : 0);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "close",
                        "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;Z)[B", false);
                run.visitVarInsn(Opcodes.ASTORE, slot);
                status.run();
            });
        }
    }

    /**
     * {@code READ} を組み立てる (要件 FR-102, FR-103)。
     *
     * <p>状態コードで 3 つに分かれる。読めたなら {@code INTO} の転記と
     * {@code NOT AT END}、終わりなら {@code AT END}、それ以外の誤りなら<b>どちらも
     * 通らない</b>。誤りのときにレコード領域の中身は決まっておらず、読めたことにして
     * 先へ進めるわけにはいかない。
     */
    private void planRead(Statement.Read statement, List<Runnable> body) {
        FileDescription file = statement.file();
        int slot = nextLocal++;
        Runnable status = planFileStatus(file, statement.origin(), slot,
                !statement.atEnd().isEmpty(), statement.keyCheck() != null);
        Runnable area = planAddress(areaOf(file, statement.origin()), statement.origin());
        if (status == null || area == null) {
            return;
        }
        Runnable depending = planReadLength(file, statement.origin());
        if (depending == null && file.varying() != null && file.varying().depending() != null) {
            return;
        }
        boolean byKey = keyed(file, statement.next());
        boolean indexed = file.organization() == Organization.INDEXED;
        Runnable key = null;
        Runnable recordOffset = null;
        if (byKey && indexed) {
            key = planRecordKey(file, statement.keyIndex(), statement.origin());
            recordOffset = planOffset(areaOf(file, statement.origin()), statement.origin());
            if (key == null || recordOffset == null) {
                return;
            }
        } else if (byKey) {
            key = planKeyValue(file.relativeKey(), statement.origin());
            if (key == null) {
                return;
            }
        }
        // 順次読みでは読んでみるまで番号が決まらない。読めた番号を鍵の項目へ返す
        Runnable number = !byKey && file.organization() == Organization.RELATIVE
                        && file.relativeKey() != null
                ? planRelativeNumber(file, statement.origin())
                : null;
        List<Runnable> into = statement.into() == null
                ? List.of()
                : planStatements(List.of(statement.into()));
        List<Runnable> atEnd = planStatements(statement.atEnd());
        List<Runnable> notAtEnd = planStatements(statement.notAtEnd());
        List<Runnable> onInvalid = planStatements(onInvalidOf(statement.keyCheck(), true));
        List<Runnable> otherwise = planStatements(onInvalidOf(statement.keyCheck(), false));
        int length = file.recordLength();
        Runnable keyArguments = key;
        Runnable offset = recordOffset;
        int keyIndex = statement.keyIndex();
        body.add(() -> {
            emitFileName(file);
            if (byKey && indexed) {
                // 鍵の値はレコード領域の中にある。位置と長さを渡して読ませる
                push(keyIndex);
                keyArguments.run();
                offset.run();
                push(length);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readKey",
                        "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;IL" + STORAGE
                                + ";IIII)[B", false);
            } else {
                if (byKey) {
                    keyArguments.run();
                }
                area.run();
                push(length);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, byKey ? "readAt" : "read",
                        "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;"
                                + (byKey ? "I" : "") + "L" + STORAGE + ";II)[B", false);
            }
            run.visitVarInsn(Opcodes.ASTORE, slot);
            status.run();

            Label ended = new Label();
            Label invalid = new Label();
            Label end = new Label();
            emitStatusTest(slot, "fileAtEnd", Opcodes.IFNE, ended);
            emitStatusTest(slot, "fileInvalidKey", Opcodes.IFNE, invalid);
            emitStatusTest(slot, "fileSucceeded", Opcodes.IFEQ, end);
            if (number != null) {
                number.run();
            }
            if (depending != null) {
                depending.run();
            }
            into.forEach(Runnable::run);
            notAtEnd.forEach(Runnable::run);
            otherwise.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(invalid);
            onInvalid.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(ended);
            atEnd.forEach(Runnable::run);
            run.visitLabel(end);
        });
    }

    /** その文が鍵で引く形かどうか。動的アクセスでは {@code NEXT} の有無で決まる。 */
    private static boolean keyed(FileDescription file, boolean next) {
        return switch (file.access()) {
            case SEQUENTIAL -> false;
            case RANDOM -> true;
            case DYNAMIC -> !next;
        };
    }

    private static List<Statement> onInvalidOf(Statement.KeyCheck keyCheck, boolean invalid) {
        if (keyCheck == null) {
            return List.of();
        }
        return invalid ? keyCheck.onInvalid() : keyCheck.otherwise();
    }

    /** 状態コードを判定して飛ぶ。 */
    private void emitStatusTest(int slot, String test, int jump, Label target) {
        run.visitVarInsn(Opcodes.ALOAD, slot);
        loadCodePage();
        run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, test, "([B" + CODE_PAGE + ")Z", false);
        run.visitJumpInsn(jump, target);
    }

    /**
     * 索引編成の鍵の場所を積む (要件 FR-101)。
     *
     * <p>積むのは<b>記憶域・位置・長さ</b>である。鍵の値はレコード領域の中にあり、
     * プログラムがそこへ入れてから読む。
     */
    private Runnable planRecordKey(FileDescription file, int keyIndex, Origin origin) {
        if (keyIndex < 0 || keyIndex >= file.keys().size()) {
            report(origin, "no such key on " + file.name());
            return null;
        }
        DataReference key = file.keys().get(keyIndex).reference();
        Runnable address = planAddress(key, origin);
        OptionalInt length = lengthOf(key, origin);
        if (address == null || length.isEmpty()) {
            return null;
        }
        int size = length.getAsInt();
        return () -> {
            address.run();
            push(size);
        };
    }

    /** 項目のバイト列を、番地と長さの組として積む。 */
    private Runnable planKeyBytes(DataReference key, Origin origin) {
        Runnable address = planAddress(key, origin);
        OptionalInt length = lengthOf(key, origin);
        if (address == null || length.isEmpty()) {
            return null;
        }
        int size = length.getAsInt();
        return () -> {
            address.run();
            push(size);
        };
    }

    /** 数値項目の値を {@code int} として積む。相対レコード番号とレコード長に使う。 */
    private Runnable planKeyValue(DataReference key, Origin origin) {
        Runnable address = planAddress(key, origin);
        String field = numericItemConstant(key.item(), origin);
        if (address == null || field == null) {
            return null;
        }
        return () -> {
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            address.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readInteger",
                    "(" + NUMERIC_ITEM + "L" + STORAGE + ";I)I", false);
        };
    }

    /** 読めたレコードの相対レコード番号を {@code RELATIVE KEY} の項目へ入れる。 */
    private Runnable planRelativeNumber(FileDescription file, Origin origin) {
        DataReference key = file.relativeKey();
        Runnable address = planAddress(key, origin);
        String field = numericItemConstant(key.item(), origin);
        if (address == null || field == null) {
            return null;
        }
        return () -> {
            emitFileName(file);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "relativeNumber",
                    "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;)I", false);
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            address.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeInteger",
                    "(I" + NUMERIC_ITEM + "L" + STORAGE + ";I)V", false);
        };
    }

    /**
     * {@code WRITE} を組み立てる (要件 FR-102)。
     *
     * <p>{@code FROM} はレコード記述への転記に展開されている。転記が先で、
     * 書き出しがあとである。
     */
    private void planWrite(Statement.Write statement, List<Runnable> body) {
        planRecordOutput(statement.file(), statement.record(), statement.from(),
                statement.keyCheck(), "write", statement.advancing(), statement.origin(), body);
        planPageCheck(statement.pageCheck(), body);
    }

    /**
     * {@code AT END-OF-PAGE} の分岐を組み立てる (要件 FR-113)。
     *
     * <p>頁の終わりに達したかは、書いた側 (実行時) しか知らない。記憶域に残すと
     * プログラムから見えてしまうので、実行時の入口が覚えたものを読む。
     */
    private void planPageCheck(Statement.PageCheck pageCheck, List<Runnable> body) {
        if (pageCheck == null) {
            return;
        }
        List<Runnable> atEnd = planStatements(pageCheck.atEnd());
        List<Runnable> otherwise = planStatements(pageCheck.otherwise());
        body.add(() -> {
            Label reached = new Label();
            Label end = new Label();
            run.visitVarInsn(Opcodes.ALOAD, 2);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "atEndOfPage",
                    "(" + CONTEXT + ")Z", false);
            run.visitJumpInsn(Opcodes.IFNE, reached);
            otherwise.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(reached);
            atEnd.forEach(Runnable::run);
            run.visitLabel(end);
        });
    }

    /**
     * {@code REWRITE} を組み立てる (要件 FR-102)。
     *
     * <p>{@code WRITE} と組み立ては同じである。違うのは呼ぶ先だけであり、
     * 「どのレコードを書き換えるか」を知っているのは開いているファイルのほうである。
     */
    private void planRewrite(Statement.Rewrite statement, List<Runnable> body) {
        planRecordOutput(statement.file(), statement.record(), statement.from(),
                statement.keyCheck(), "rewrite", null, statement.origin(), body);
    }

    /**
     * {@code WRITE} と {@code REWRITE} を組み立てる (要件 FR-101, FR-102)。
     *
     * <p>2 つの違いは呼ぶ先だけである。鍵で引く様式なら番号を渡す形になり、
     * どちらを呼ぶかは<b>アクセス様式で翻訳時に決まる</b>。
     */
    private void planRecordOutput(FileDescription file, DataItem record, Statement.Move from,
                                  Statement.KeyCheck keyCheck, String verb,
                                  Statement.Advancing advancing, Origin origin,
                                  List<Runnable> body) {
        int slot = nextLocal++;
        Runnable status = planFileStatus(file, origin, slot, false, keyCheck != null);
        Runnable area = planAddress(new DataReference(record, List.of(), null, origin), origin);
        Runnable length = planWrittenLength(file, record, origin);
        if (status == null || area == null || length == null) {
            return;
        }
        boolean indexed = file.organization() == Organization.INDEXED;
        boolean byKey = file.access().isKeyed();
        // 索引編成の鍵はレコードの中にある。渡すものは順アクセスと変わらない
        Runnable key = byKey && !indexed ? planKeyValue(file.relativeKey(), origin) : null;
        if (byKey && !indexed && key == null) {
            return;
        }
        if (from != null) {
            planMove(from, body);
        }
        String entry = verb;
        if (byKey) {
            entry = indexed ? verb + "Key" : verb + "At";
        }
        boolean withNumber = byKey && !indexed;
        Runnable lines = null;
        if (advancing != null) {
            if (byKey) {
                // 行送りは印字するファイルのものである。鍵で引くファイルには行がない
                report(origin, "ADVANCING cannot be used on a keyed file: " + file.name());
                return;
            }
            lines = planAdvancing(advancing, origin);
            if (lines == null) {
                return;
            }
            entry = "writeLine";
        }
        // LINAGE を書いたファイルは、行送りを書かない WRITE も 1 行を使う。
        // 頁の中の位置を数え続けなければならないので、常にこちらの道を通す
        FileDescription.Linage linage = "write".equals(verb) && !byKey ? file.linage() : null;
        if (linage != null) {
            entry = "writeLinage";
        }
        String called = entry;
        Runnable advance = lines;
        boolean before = advancing != null && advancing.before();
        Runnable page = linage == null ? null : planLinageShape(linage);
        Runnable call = () -> {
            emitFileName(file);
            if (withNumber) {
                key.run();
            }
            area.run();
            length.run();
            emitLengthBounds(file, record);
            if (linage != null && advance == null) {
                // 行送りを書かない WRITE は AFTER ADVANCING 1 と同じだけ進む
                push(1);
                push(0);
            } else if (advance != null) {
                advance.run();
                push(before ? 1 : 0);
            }
            if (page != null) {
                page.run();
            }
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, called,
                    "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;"
                            + (withNumber ? "I" : "") + "L" + STORAGE + ";IIII"
                            + (linage != null || advance != null ? "IZ" : "")
                            + (linage != null ? "IIIII" : "") + ")[B", false);
        };
        planKeyedCall(call, status, slot, keyCheck, body);
    }

    /**
     * 論理頁の形が<b>どこに置いてあるか</b>を積む (要件 FR-113)。
     *
     * <p>値そのものではなく置き場を渡す。頁の形は項目で書けるので、開くたびに
     * 読み直さなければならない。翻訳時に決まるのは置き場だけである。
     */
    private Runnable planLinageShape(FileDescription.Linage linage) {
        int counterAt = linage.counter().absoluteOffset().orElse(-1);
        int pageAt = linage.page().at().absoluteOffset().orElse(-1);
        int footingAt = linage.footing().at().absoluteOffset().orElse(-1);
        int topAt = linage.top().at().absoluteOffset().orElse(-1);
        int bottomAt = linage.bottom().at().absoluteOffset().orElse(-1);
        return () -> {
            push(counterAt);
            push(pageAt);
            push(footingAt);
            push(topAt);
            push(bottomAt);
        };
    }

    /**
     * 開くときに、頁の形をその置き場へ写す (要件 FR-113)。
     *
     * <p>項目で書かれた形は<b>開くたびに読み直す</b>決まりである。数で書かれた形も
     * 同じ道を通す。書かれていない指定は 0 になる。
     */
    private void emitLinageSetup(FileDescription.Linage linage, Origin origin) {
        storeLinageSlot(linage.page(), origin);
        storeLinageSlot(linage.footing(), origin);
        storeLinageSlot(linage.top(), origin);
        storeLinageSlot(linage.bottom(), origin);
        // 開けば頁は初めからである。0 は「まだ 1 行も置いていない」
        emitStoreCounter(linage.counter().absoluteOffset().orElse(0), () -> push(0));
    }

    private void storeLinageSlot(FileDescription.Linage.Slot slot, Origin origin) {
        int at = slot.at().absoluteOffset().orElse(0);
        if (slot.source() == null) {
            emitStoreCounter(at, () -> push(0));
            return;
        }
        Runnable value = planLinageValue(slot.source(), origin);
        if (value == null) {
            return;
        }
        emitStoreCounter(at, value);
    }

    /** 頁の形の値を {@code int} として積む。 */
    private Runnable planLinageValue(Operand source, Origin origin) {
        if (source instanceof Operand.Literal literal) {
            Decimal value = decimalOf(literal.value(), origin);
            if (value == null) {
                return null;
            }
            int written = value.toBigDecimal().intValue();
            return () -> push(written);
        }
        return planKeyValue(((Operand.Reference) source).reference(), origin);
    }

    private void emitStoreCounter(int at, Runnable value) {
        run.visitVarInsn(Opcodes.ALOAD, 1);
        push(at);
        value.run();
        run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "writeCounter",
                "(L" + STORAGE + ";II)V", false);
    }

    /**
     * {@code DELETE} を組み立てる (要件 FR-101, FR-102)。
     *
     * <p>消す相手は文に書かれていない。順アクセスなら直前に読んだレコード、
     * 乱アクセスなら鍵の指すレコードである。
     */
    private void planDelete(Statement.Delete statement, List<Runnable> body) {
        FileDescription file = statement.file();
        int slot = nextLocal++;
        Runnable status = planFileStatus(file, statement.origin(), slot, false,
                statement.keyCheck() != null);
        if (status == null) {
            return;
        }
        boolean indexed = file.organization() == Organization.INDEXED;
        boolean byKey = file.access().isKeyed();
        Runnable key = null;
        if (byKey) {
            key = indexed
                    ? planRecordKey(file, 0, statement.origin())
                    : planKeyValue(file.relativeKey(), statement.origin());
            if (key == null) {
                return;
            }
        }
        Runnable keyArguments = key;
        planKeyedCall(() -> {
            emitFileName(file);
            if (byKey) {
                keyArguments.run();
            }
            String entry = byKey ? (indexed ? "deleteKey" : "deleteAt") : "delete";
            String arguments = byKey
                    ? (indexed ? "L" + STORAGE + ";II" : "I")
                    : "";
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, entry,
                    "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;" + arguments + ")[B",
                    false);
        }, status, slot, statement.keyCheck(), body);
    }

    /**
     * {@code START} を組み立てる (要件 FR-101)。
     *
     * <p>読まない。位置を決めるだけである。
     */
    private void planStart(Statement.Start statement, List<Runnable> body) {
        FileDescription file = statement.file();
        int slot = nextLocal++;
        Runnable status = planFileStatus(file, statement.origin(), slot, false,
                statement.keyCheck() != null);
        boolean indexed = file.organization() == Organization.INDEXED;
        // 索引編成では<b>書かれた項目</b>を渡す。鍵より短ければ総称鍵になる
        Runnable key = indexed
                ? planKeyBytes(statement.key(), statement.origin())
                : planKeyValue(statement.key(), statement.origin());
        if (status == null || key == null) {
            return;
        }
        int relation = statement.relation().ordinal();
        int keyIndex = statement.keyIndex();
        planKeyedCall(() -> {
            emitFileName(file);
            if (indexed) {
                push(keyIndex);
            }
            key.run();
            push(relation);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, indexed ? "startKey" : "start",
                    "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;"
                            + (indexed ? "IL" + STORAGE + ";III" : "II") + ")[B", false);
        }, status, slot, statement.keyCheck(), body);
    }

    /**
     * 入出力を呼び、状態コードを始末して {@code INVALID KEY} で分かれる。
     *
     * @param call 状態コードのバイト列を積む命令
     */
    private void planKeyedCall(Runnable call, Runnable status, int slot,
                               Statement.KeyCheck keyCheck, List<Runnable> body) {
        if (keyCheck == null) {
            body.add(() -> {
                call.run();
                run.visitVarInsn(Opcodes.ASTORE, slot);
                status.run();
            });
            return;
        }
        List<Runnable> onInvalid = planStatements(keyCheck.onInvalid());
        List<Runnable> otherwise = planStatements(keyCheck.otherwise());
        body.add(() -> {
            call.run();
            run.visitVarInsn(Opcodes.ASTORE, slot);
            status.run();

            Label invalid = new Label();
            Label end = new Label();
            emitStatusTest(slot, "fileInvalidKey", Opcodes.IFNE, invalid);
            otherwise.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(invalid);
            onInvalid.forEach(Runnable::run);
            run.visitLabel(end);
        });
    }

    /**
     * 書き出す長さの下限と上限を積む (要件 FR-106)。
     *
     * <p>固定長ではレコード記述の長さそのものである。可変長では宣言された範囲であり、
     * {@code DEPENDING ON} の項目に範囲外の値が入っていたときにそこへ収める。
     */
    private void emitLengthBounds(FileDescription file, DataItem record) {
        FileDescription.Varying varying = file.varying();
        if (varying == null) {
            push(record.totalLength());
            push(record.totalLength());
            return;
        }
        push(Math.max(1, varying.minimum()));
        push(varying.maximum());
    }

    /**
     * 書き出す長さを積む命令 (要件 FR-106)。
     *
     * <p>固定長なら翻訳時に決まる。可変長で {@code DEPENDING ON} が書かれていれば、
     * <b>その項目の値がレコード長である</b>。長さそのものがデータなので、実行時に読む。
     */
    private Runnable planWrittenLength(FileDescription file, DataItem record, Origin origin) {
        FileDescription.Varying varying = file.varying();
        if (varying == null || varying.depending() == null) {
            // DEPENDING ON がなければ、書いたレコード記述の長さがそのままレコード長である
            int length = record.totalLength();
            return () -> push(length);
        }
        Runnable address = planAddress(varying.depending(), origin);
        String field = numericItemConstant(varying.depending().item(), origin);
        if (address == null || field == null) {
            return null;
        }
        return () -> {
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            address.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readInteger",
                    "(" + NUMERIC_ITEM + "L" + STORAGE + ";I)I", false);
        };
    }

    /**
     * 送る行数を積む命令 (要件 FR-102)。
     *
     * <p>頁の先頭へ送る指定は<b>負の数</b>で表す。行数と同じ 1 つの引数に載せられ、
     * 呼ぶ側の形が増えないからである。
     *
     * @return 読めなければ {@code null}
     */
    private Runnable planAdvancing(Statement.Advancing advancing, Origin origin) {
        if (advancing.page()) {
            return () -> push(Ops.PAGE);
        }
        if (advancing.fixed()) {
            int lines = advancing.lines();
            return () -> push(lines);
        }
        Runnable address = planAddress(advancing.count(), origin);
        String field = numericItemConstant(advancing.count().item(), origin);
        if (address == null || field == null) {
            return null;
        }
        return () -> {
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            address.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readInteger",
                    "(" + NUMERIC_ITEM + "L" + STORAGE + ";I)I", false);
        };
    }

    /**
     * 読めた長さを {@code DEPENDING ON} の項目へ入れる命令 (要件 FR-106)。
     *
     * @return 可変長でなければ {@code null}
     */
    private Runnable planReadLength(FileDescription file, Origin origin) {
        FileDescription.Varying varying = file.varying();
        if (varying == null || varying.depending() == null) {
            return null;
        }
        Runnable address = planAddress(varying.depending(), origin);
        String field = numericItemConstant(varying.depending().item(), origin);
        if (address == null || field == null) {
            return null;
        }
        return () -> {
            emitFileName(file);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "recordLength",
                    "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;)I", false);
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            address.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeInteger",
                    "(I" + NUMERIC_ITEM + "L" + STORAGE + ";I)V", false);
        };
    }

    /**
     * 索引編成の鍵の場所を積む (要件 FR-100)。
     *
     * <p>位置・長さ・重複を許すかの 3 つ組を並べた {@code int[]} である。先頭の組が主鍵で、
     * 以降が副鍵である。鍵の場所はファイルではなくプログラムが決めるので、開くときに渡す。
     */
    private void emitKeyPositions(FileDescription file) {
        List<FileDescription.RecordKey> keys = file.keys();
        push(keys.size() * 3);
        run.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        for (int i = 0; i < keys.size(); i++) {
            FileDescription.RecordKey key = keys.get(i);
            emitIntElement(i * 3, key.offset());
            emitIntElement(i * 3 + 1, key.length());
            emitIntElement(i * 3 + 2, key.duplicates() ? 1 : 0);
        }
    }

    private void emitIntElement(int index, int value) {
        run.visitInsn(Opcodes.DUP);
        push(index);
        push(value);
        run.visitInsn(Opcodes.IASTORE);
    }

    // ---- 整列と合併 ----

    /**
     * {@code SORT} と {@code MERGE} を組み立てる (要件 FR-120, FR-121)。
     *
     * <p>出すのは<b>溜めて、並べ替えて、配る</b>の 3 つである。入口と出口がファイルなら
     * ランタイムの入口を呼び、手続きなら段落の範囲を実行する。並べ替えそのものは
     * ランタイムが持つ (方針 ARC-7)。
     */
    private void planSort(Statement.Sort statement, List<Runnable> body) {
        FileDescription work = statement.work();
        List<Runnable> keys = new ArrayList<>();
        for (Statement.Sort.SortKeySpec key : statement.keys()) {
            Runnable element = planSortKey(key, statement.origin());
            if (element == null) {
                return;
            }
            keys.add(element);
        }
        Runnable input = planSortSide(statement.using(), statement.input(), "sortUsing",
                work, statement.origin());
        Runnable output = planSortSide(statement.giving(), statement.output(), "sortGiving",
                work, statement.origin());
        if (input == null || output == null) {
            return;
        }
        String name = work.name();
        byte[] sequence = statement.sequence();
        body.add(() -> {
            run.visitVarInsn(Opcodes.ALOAD, 2);
            run.visitLdcInsn(name);
            emitArray(keys, Type.getInternalName(SortKey.class));
            // 並べ替えの鍵も、文が指した (なければプログラムの) 照合順序に従う。
            // ここだけコードページの並びで並べると、同じプログラムの中で
            // 場所によって順序が食い違う
            if (sequence != null) {
                run.visitFieldInsn(Opcodes.GETSTATIC, internal,
                        collatingConstant(sequence), COLLATING);
            }
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "sortOpen",
                    "(" + CONTEXT + "Ljava/lang/String;[" + Type.getDescriptor(SortKey.class)
                            + (sequence != null ? COLLATING : "") + ")V", false);
            input.run();
            run.visitVarInsn(Opcodes.ALOAD, 2);
            run.visitLdcInsn(name);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "sortRecords",
                    "(" + CONTEXT + "Ljava/lang/String;)V", false);
            output.run();
        });
    }

    /**
     * 鍵 1 個を作る命令。
     *
     * <p>数値項目なら<b>値として</b>比べる。{@code 010} と {@code 9} はバイトで比べれば
     * {@code 010} が小さいが、値としては {@code 9} が小さい。
     */
    private Runnable planSortKey(Statement.Sort.SortKeySpec key, Origin origin) {
        DataReference reference = key.reference();
        OptionalInt offset = reference.constantOffset();
        OptionalInt length = reference.constantLength();
        if (offset.isEmpty() || length.isEmpty()) {
            report(origin, "a sort key must have a fixed position and length");
            return null;
        }
        String field = DataCategory.of(reference).isNumeric()
                ? numericItemConstant(reference.item(), origin)
                : null;
        if (DataCategory.of(reference).isNumeric() && field == null) {
            return null;
        }
        int at = offset.getAsInt();
        int size = length.getAsInt();
        boolean ascending = key.ascending();
        return () -> {
            run.visitTypeInsn(Opcodes.NEW, Type.getInternalName(SortKey.class));
            run.visitInsn(Opcodes.DUP);
            push(at);
            push(size);
            run.visitInsn(ascending ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            if (field == null) {
                run.visitInsn(Opcodes.ACONST_NULL);
            } else {
                run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            }
            run.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(SortKey.class),
                    "<init>", "(IIZ" + NUMERIC_ITEM + ")V", false);
        };
    }

    /**
     * 入口と出口を組み立てる。
     *
     * @param files ファイルで指定したもの。手続きで指定していれば空
     * @param entry ランタイムの入口の名前 ({@code sortUsing} か {@code sortGiving})
     */
    private Runnable planSortSide(List<FileDescription> files,
                                  Statement.Sort.Procedure procedure, String entry,
                                  FileDescription work, Origin origin) {
        if (procedure != null) {
            int from = paragraphNames.indexOf(procedure.from());
            int through = procedure.through() == null
                    ? lastOf(procedure.from(), from)
                    : lastOf(procedure.through(), paragraphNames.indexOf(procedure.through()));
            if (from < 0 || through < 0) {
                report(origin, "undefined paragraph: " + procedure.from());
                return null;
            }
            return () -> emitPerformRange(from, through);
        }
        String name = work.name();
        List<Runnable> calls = new ArrayList<>();
        for (FileDescription file : files) {
            int organization = file.organization().ordinal();
            int format = file.format().ordinal();
            int length = file.recordLength();
            calls.add(() -> {
                run.visitVarInsn(Opcodes.ALOAD, 2);
                run.visitLdcInsn(name);
                run.visitLdcInsn(file.name());
                run.visitLdcInsn(file.ddName());
                push(organization);
                push(format);
                push(length);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, entry,
                        "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;"
                                + "Ljava/lang/String;III)V", false);
            });
        }
        return () -> calls.forEach(Runnable::run);
    }

    /** {@code RELEASE} を組み立てる (要件 FR-120)。 */
    private void planRelease(Statement.Release statement, List<Runnable> body) {
        Runnable area = planAddress(
                new DataReference(statement.record(), List.of(), null, statement.origin()),
                statement.origin());
        if (area == null) {
            return;
        }
        if (statement.from() != null) {
            planMove(statement.from(), body);
        }
        String name = statement.work().name();
        int length = statement.record().totalLength();
        body.add(() -> {
            run.visitVarInsn(Opcodes.ALOAD, 2);
            run.visitLdcInsn(name);
            area.run();
            push(length);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "release",
                    "(" + CONTEXT + "Ljava/lang/String;L" + STORAGE + ";II)V", false);
        });
    }

    /**
     * {@code RETURN} を組み立てる (要件 FR-120)。
     *
     * <p>返すものが尽きたかどうかで分かれるだけである。ファイルの状態コードは持たない。
     * 整列作業ファイルはデータセットではないので、状態を持たせる先がない。
     */
    private void planReturn(Statement.Return statement, List<Runnable> body) {
        FileDescription work = statement.work();
        Runnable area = planAddress(areaOf(work, statement.origin()), statement.origin());
        if (area == null) {
            return;
        }
        List<Runnable> into = statement.into() == null
                ? List.of()
                : planStatements(List.of(statement.into()));
        List<Runnable> atEnd = planStatements(statement.atEnd());
        List<Runnable> notAtEnd = planStatements(statement.notAtEnd());
        String name = work.name();
        int length = work.recordLength();
        body.add(() -> {
            run.visitVarInsn(Opcodes.ALOAD, 2);
            run.visitLdcInsn(name);
            area.run();
            push(length);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "sortReturn",
                    "(" + CONTEXT + "Ljava/lang/String;L" + STORAGE + ";II)Z", false);
            Label ended = new Label();
            Label end = new Label();
            run.visitJumpInsn(Opcodes.IFEQ, ended);
            into.forEach(Runnable::run);
            notAtEnd.forEach(Runnable::run);
            run.visitJumpInsn(Opcodes.GOTO, end);
            run.visitLabel(ended);
            atEnd.forEach(Runnable::run);
            run.visitLabel(end);
        });
    }

    /** 実行時の入口とファイル名・DD 名を積む。どの入出力にも要る前置きである。 */
    private void emitFileName(FileDescription file) {
        run.visitVarInsn(Opcodes.ALOAD, 2);
        run.visitLdcInsn(file.name());
        run.visitLdcInsn(file.ddName());
    }

    /** レコード領域の先頭への参照。 */
    private static DataReference areaOf(FileDescription file, Origin origin) {
        return new DataReference(file.area(), List.of(), null, origin);
    }

    /**
     * 状態コードの始末 (要件 FR-103, FR-104)。
     *
     * <p>積まれているバイト列を消費する。{@code FILE STATUS} が書かれていればそこへ入れ、
     * 書かれていなければ<b>異常なら止める</b>。黙って続けると、読めていないデータで
     * 処理が進んでしまう。
     */
    private Runnable planFileStatus(FileDescription file, Origin origin, int slot,
                                    boolean atEndHandled, boolean invalidKeyHandled) {
        return planFileStatus(file, origin, slot, atEndHandled, invalidKeyHandled, null);
    }

    /**
     * 状態コードの始末 (要件 FR-103, FR-104, FR-105)。
     *
     * <p>やることは 3 つある。{@code FILE STATUS} が書かれていればそこへ入れる。
     * 宣言節があれば、受け止め手のない異常のときにそれを動かす。どちらもなければ止める。
     * 黙って続けると、読めていないデータで処理が進む。
     *
     * @param slc   状態コードのバイト列が入っている局所変数
     * @param opened {@code OPEN} で開こうとした開き方。ほかの文では {@code null}
     */
    private Runnable planFileStatus(FileDescription file, Origin origin, int slot,
                                    boolean atEndHandled, boolean invalidKeyHandled,
                                    OpenMode opened) {
        Runnable store = null;
        if (file.status() != null) {
            Runnable address = planAddress(file.status(), origin);
            if (address == null) {
                return null;
            }
            store = () -> {
                run.visitVarInsn(Opcodes.ALOAD, slot);
                address.run();
                push(FILE_STATUS_LENGTH);
                run.visitInsn(Opcodes.ICONST_0);
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "moveAlphanumeric",
                        "([BL" + STORAGE + ";IIZ" + CODE_PAGE + ")V", false);
            };
        }
        Runnable handler = planDeclarative(file, slot, atEndHandled, invalidKeyHandled, opened);
        if (handler == null && file.status() == null) {
            String name = file.name();
            handler = () -> {
                run.visitVarInsn(Opcodes.ALOAD, slot);
                loadCodePage();
                run.visitLdcInsn(name);
                run.visitInsn(atEndHandled ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                run.visitInsn(invalidKeyHandled ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "checkFile",
                        "([B" + CODE_PAGE + "Ljava/lang/String;ZZ)V", false);
            };
        }
        Runnable storing = store;
        Runnable handling = handler;
        return () -> {
            if (storing != null) {
                storing.run();
            }
            if (handling != null) {
                handling.run();
            }
        };
    }

    /**
     * 宣言節を動かす命令 (要件 FR-105)。
     *
     * <p>受け持ちの決め方は 2 つある。<b>ファイル名で指定したもの</b>が先で、なければ
     * <b>開き方で指定したもの</b>を見る。開き方は {@code OPEN} では書いてあるとおりに決まり、
     * ほかの文では実行時のものを見る。
     *
     * @return 受け持つ宣言節がなければ {@code null}
     */
    private Runnable planDeclarative(FileDescription file, int slot, boolean atEndHandled,
                                     boolean invalidKeyHandled, OpenMode opened) {
        ProcedureBuilder.Declarative named = null;
        List<ProcedureBuilder.Declarative> byMode = new ArrayList<>();
        for (ProcedureBuilder.Declarative declarative : declaratives) {
            if (declarative.files().stream().anyMatch(f -> f.name().equals(file.name()))) {
                named = declarative;
            } else if (declarative.mode() != null) {
                byMode.add(declarative);
            }
        }
        if (named != null) {
            return planDeclarativeCall(named, slot, atEndHandled, invalidKeyHandled, null);
        }
        if (opened != null) {
            // OPEN の開き方は書いてあるとおりに決まる。実行時に見るまでもない
            for (ProcedureBuilder.Declarative declarative : byMode) {
                if (declarative.mode() == opened) {
                    return planDeclarativeCall(declarative, slot, atEndHandled, invalidKeyHandled,
                            null);
                }
            }
            return null;
        }
        if (byMode.isEmpty()) {
            return null;
        }
        List<Runnable> tests = new ArrayList<>();
        for (ProcedureBuilder.Declarative declarative : byMode) {
            tests.add(planDeclarativeCall(declarative, slot, atEndHandled, invalidKeyHandled,
                    file));
        }
        return () -> tests.forEach(Runnable::run);
    }

    /**
     * 宣言節 1 つを呼ぶ命令。
     *
     * @param modeCheck 開き方を実行時に見るファイル。ファイル名で指定した節では {@code null}
     */
    private Runnable planDeclarativeCall(ProcedureBuilder.Declarative declarative, int slot,
                                         boolean atEndHandled, boolean invalidKeyHandled,
                                         FileDescription modeCheck) {
        int from = paragraphNames.indexOf(declarative.first());
        int through = paragraphNames.indexOf(declarative.last());
        int mode = declarative.mode() == null ? -1 : declarative.mode().ordinal();
        return () -> {
            Label skip = new Label();
            run.visitVarInsn(Opcodes.ALOAD, slot);
            loadCodePage();
            run.visitInsn(atEndHandled ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            run.visitInsn(invalidKeyHandled ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "fileFailed",
                    "([B" + CODE_PAGE + "ZZ)Z", false);
            run.visitJumpInsn(Opcodes.IFEQ, skip);
            if (modeCheck != null) {
                emitFileName(modeCheck);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "fileMode",
                        "(" + CONTEXT + "Ljava/lang/String;Ljava/lang/String;)I", false);
                push(mode);
                run.visitJumpInsn(Opcodes.IF_ICMPNE, skip);
            }
            emitPerformRange(from, through);
            run.visitLabel(skip);
        };
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
                ? lastOf(statement.target(), from)
                : lastOf(statement.through(), paragraphNames.indexOf(statement.through()));
        if (from < 0 || to < 0) {
            report(statement.origin(), "undefined paragraph: " + statement.target());
            return null;
        }
        return () -> emitPerformRange(from, to);
    }

    /**
     * その名前が節なら、節の最後の段落まで含める (要件 FR-061)。
     *
     * <p>{@code PERFORM 節名} は<b>節の全体</b>を動かす。節は段落をまとめたものなので、
     * 範囲の終わりを最後の段落へ広げれば、段落の範囲の実行としてそのまま出せる。
     */
    private int lastOf(String name, int fallback) {
        for (ProcedureBuilder.Section section : sections) {
            if (section.name().equals(name)) {
                return paragraphNames.indexOf(section.last());
            }
        }
        return fallback;
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
        if (offset == null || item.picture() == null) {
            return null;
        }
        if (DataCategory.of(target) == DataCategory.NUMERIC_EDITED) {
            return planStoreEdited(item, value, offset, rounding);
        }
        String field = numericItemConstant(item, origin);
        if (field == null) {
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
     * 積んだ {@link Decimal} を数字編集項目へ書き込む命令 (要件 FR-041)。
     *
     * <p>{@code GIVING} と {@code COMPUTE} の受取側は数字編集項目でもよい。書き込む道が
     * 転記と同じになるのは、<b>編集は転記の規則そのもの</b>だからである。違うのは丸めが
     * 効くことだけで、ランタイムが編集の前に丸める。
     */
    private Runnable planStoreEdited(DataItem item, Runnable value, Runnable offset,
                                     String rounding) {
        String picture = pictureConstant(item.picture());
        return () -> {
            value.run();
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, picture, PICTURE);
            offset.run();
            loadRounding(rounding);
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "storeEdited",
                    "(" + DECIMAL + PICTURE + "L" + STORAGE + ";I"
                            + Type.getDescriptor(CobolRounding.class) + CODE_PAGE + ")V", false);
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
        if (condition instanceof Condition.ClassTest test) {
            emitClassTest(test, target, jumpWhenTrue);
            return;
        }
        if (condition instanceof Condition.SwitchTest test) {
            // 記憶域を見ない。実行の外から立てられたものを読むだけである
            run.visitVarInsn(Opcodes.ALOAD, 2);
            push(test.index());
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "switchState",
                    "(" + CONTEXT + "I)Z", false);
            run.visitJumpInsn(jumpWhenTrue == test.whenOn() ? Opcodes.IFNE : Opcodes.IFEQ, target);
            return;
        }
        emitRelation((Condition.Relation) condition, target, jumpWhenTrue);
    }

    /**
     * 級条件を組み立てる (要件 FR-046)。
     *
     * <p>{@code NUMERIC} の見方は<b>項目の書き方で変わる</b>。符号を持つ数値項目では
     * 符号の場所まで見るので、その項目として読めるかどうかで決める。英数字項目に
     * 符号は無いので、すべてのバイトが数字かどうかだけを見る。
     */
    private void emitClassTest(Condition.ClassTest test, Label target, boolean jumpWhenTrue) {
        Runnable bytes = planSourceBytes(new Operand.Reference(test.item()), test.origin(), 0);
        if (bytes == null) {
            return;
        }
        DataItem item = test.item().item();
        boolean signedNumeric = test.kind() == Condition.ClassTest.Kind.NUMERIC
                && item.picture() != null && item.picture().isNumeric();
        String shape = signedNumeric ? numericItemConstant(item, test.origin()) : null;
        if (signedNumeric && shape == null) {
            return;
        }
        byte[] allowed = test.allowed();
        Condition.ClassTest.Kind kind = test.kind();
        bytes.run();
        switch (kind) {
            case NUMERIC -> {
                if (signedNumeric) {
                    run.visitFieldInsn(Opcodes.GETSTATIC, internal, shape, NUMERIC_ITEM);
                    loadCodePage();
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, CLASS_TEST, "numeric",
                            "([B" + NUMERIC_ITEM + CODE_PAGE + ")Z", false);
                } else {
                    loadCodePage();
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, CLASS_TEST, "digits",
                            "([B" + CODE_PAGE + ")Z", false);
                }
            }
            case DEFINED -> {
                run.visitFieldInsn(Opcodes.GETSTATIC, internal, bytesConstant(allowed), "[B");
                run.visitMethodInsn(Opcodes.INVOKESTATIC, CLASS_TEST, "member", "([B[B)Z", false);
            }
            default -> {
                loadCodePage();
                push(switch (kind) {
                    case ALPHABETIC_LOWER -> 1;
                    case ALPHABETIC_UPPER -> 2;
                    default -> 0;
                });
                run.visitMethodInsn(Opcodes.INVOKESTATIC, CLASS_TEST, "alphabetic",
                        "([B" + CODE_PAGE + "I)Z", false);
            }
        }
        run.visitJumpInsn(jumpWhenTrue ? Opcodes.IFNE : Opcodes.IFEQ, target);
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
            left = planComparisonSide(relation.left(), relation.origin());
            right = planComparisonSide(relation.right(), relation.origin());
        } else {
            // 英数字の比較に式は書けない。四則の相手は数値しかない
            int length = comparisonLength(relation);
            left = planSourceBytes(Condition.Relation.operandOf(relation.left()),
                    relation.origin(), length);
            right = planSourceBytes(Condition.Relation.operandOf(relation.right()),
                    relation.origin(), length);
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
            } else if (collating == null) {
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "compareAlphanumeric",
                        "([B[B" + CODE_PAGE + ")I", false);
            } else {
                // 照合順序を差し替えたプログラムでは、位置で比べる (要件 FR-054)
                loadCollating();
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "compareAlphanumeric",
                        "([B[B" + COLLATING + CODE_PAGE + ")I", false);
            }
        };
    }

    /**
     * 比べる片側を {@link Decimal} として積む命令。
     *
     * <p>被演算子 1 個なら今までどおり。式なら評価して積む。
     */
    private Runnable planComparisonSide(Expression side, Origin origin) {
        Operand operand = Condition.Relation.operandOf(side);
        return operand != null
                ? planSourceDecimal(operand, origin)
                : planExpression(side, IntermediateDigits.of(side, List.of()), origin);
    }

    /** 図形定数を広げる長さ。相手の項目の長さに合わせる。 */
    private static int comparisonLength(Condition.Relation relation) {
        int length = lengthOf(Condition.Relation.operandOf(relation.left()));
        return length > 0 ? length : lengthOf(Condition.Relation.operandOf(relation.right()));
    }

    private static int lengthOf(Operand operand) {
        if (operand == null) {
            return 0;
        }
        if (operand instanceof Operand.Reference reference) {
            // 長さが実行時に決まる部分参照でも、項目全体を超えることはない。
            // 図形定数を広げる長さとしては、その上限で足りる
            return reference.reference().constantLength()
                    .orElse(reference.reference().item().length());
        }
        if (operand instanceof Operand.Function function) {
            return functionLength(function);
        }
        return 0;
    }

    /**
     * 組み込み関数が返すバイト列の長さ (要件 FR-070)。
     *
     * <p>翻訳時に決まっていなければならない。決まらなければ、転記も比較も長さが決まらない。
     *
     * @return 数値を返す関数なら {@code 0}
     */
    private static int functionLength(Operand.Function function) {
        return switch (function.returns()) {
            case ONE_CHARACTER -> 1;
            case TIMESTAMP -> Intrinsics.TIMESTAMP_LENGTH;
            case SAME_LENGTH -> alphanumericLength(argument(function, 0));
            case WIDEST -> widestArgument(function);
            case INTEGER, NUMERIC -> 0;
        };
    }

    /** いちばん長い引数の長さ。{@code MAX} と {@code MIN} は引数そのものを返す。 */
    private static int widestArgument(Operand.Function function) {
        int widest = 0;
        for (int i = 0; i < function.arguments().size(); i++) {
            widest = Math.max(widest, alphanumericLength(argument(function, i)));
        }
        return widest;
    }

    /** バイト列として見たときの長さ。定数はその綴りの長さである。 */
    private static int alphanumericLength(Operand operand) {
        if (operand instanceof Operand.Literal literal
                && literal.value() instanceof LiteralValue.Text text) {
            return text.text().length();
        }
        return lengthOf(operand);
    }

    /** 文字を受け取る関数の引数。意味解析が式でないことを確かめてある。 */
    private static Operand argument(Operand.Function function, int index) {
        return ((Expression.Value) function.arguments().get(index)).operand();
    }

    // ---- 組み込み関数 ----

    /**
     * 組み込み関数の呼び出しを積む命令 (要件 FR-070)。バイト列を返すもの。
     */
    private Runnable planFunctionBytes(Operand.Function function) {
        Origin origin = function.origin();
        return switch (function.intrinsic()) {
            case UPPER_CASE -> planLetterMap(function, "upperCase");
            case LOWER_CASE -> planLetterMap(function, "lowerCase");
            case REVERSE -> {
                Runnable value = planAlphanumericArgument(function, 0);
                yield value == null ? null : () -> {
                    value.run();
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, "reverse",
                            "([B)[B", false);
                };
            }
            case CURRENT_DATE -> () -> {
                run.visitVarInsn(Opcodes.ALOAD, 2);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "currentDate",
                        "(L" + Type.getInternalName(ProgramContext.class) + ";)[B", false);
            };
            // 翻訳した時刻は翻訳時に決まっている。実行時に読むものは何も無い
            case WHEN_COMPILED -> {
                String field = bytesConstant(Intrinsics.timestamp(compiledAt, CodePages.DEFAULT));
                yield () -> run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, "[B");
            }
            case MAX -> planTextFold(function, "maxText", "[B");
            case MIN -> planTextFold(function, "minText", "[B");
            case CHAR -> {
                Runnable ordinal = planNumericArgument(function, 0, origin);
                yield ordinal == null ? null : () -> {
                    ordinal.run();
                    loadCollating();
                    run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, "charOf",
                            "(" + DECIMAL + COLLATING + ")[B", false);
                };
            }
            default -> {
                report(origin, "FUNCTION " + function.intrinsic().spelling()
                        + " does not return an alphanumeric value");
                yield null;
            }
        };
    }

    /** 引数を文字として受け取る形かどうか。 */
    private static boolean takesText(Operand.Function function) {
        return function.returns() == Intrinsic.Result.WIDEST
                || (function.intrinsic().takes() == Intrinsic.Argument.EITHER
                        && function.returns() == Intrinsic.Result.INTEGER
                        && !allNumericArguments(function));
    }

    /** 引数がぜんぶ数値として読めるか。 */
    private static boolean allNumericArguments(Operand.Function function) {
        for (int i = 0; i < function.arguments().size(); i++) {
            if (!(function.arguments().get(i) instanceof Expression.Value value)) {
                return true;
            }
            Operand operand = value.operand();
            if (operand instanceof Operand.Reference reference
                    && !DataCategory.of(reference.reference()).isNumeric()) {
                return false;
            }
            if (operand instanceof Operand.Literal literal
                    && literal.value() instanceof LiteralValue.Text) {
                return false;
            }
        }
        return true;
    }

    /**
     * 引数を<b>バイト列の配列</b>にして渡す関数 (要件 FR-070)。
     *
     * <p>{@code MAX} と {@code MIN} は引数が英数字なら照合順序で比べる。したがって
     * 並びを一緒に渡す。
     */
    private Runnable planTextFold(Operand.Function function, String method, String returns) {
        List<Runnable> values = new ArrayList<>();
        for (int i = 0; i < function.arguments().size(); i++) {
            Runnable value = planAlphanumericArgument(function, i);
            if (value == null) {
                return null;
            }
            values.add(value);
        }
        return () -> {
            push(values.size());
            run.visitTypeInsn(Opcodes.ANEWARRAY, "[B");
            for (int i = 0; i < values.size(); i++) {
                run.visitInsn(Opcodes.DUP);
                push(i);
                values.get(i).run();
                run.visitInsn(Opcodes.AASTORE);
            }
            loadCollating();
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, method,
                    "([[B" + COLLATING + CODE_PAGE + ")" + returns, false);
        };
    }

    private Runnable planLetterMap(Operand.Function function, String method) {
        Runnable value = planAlphanumericArgument(function, 0);
        if (value == null) {
            return null;
        }
        return () -> {
            value.run();
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, method,
                    "([B" + CODE_PAGE + ")[B", false);
        };
    }

    /** 組み込み関数の呼び出しを積む命令。{@link Decimal} を返すもの。 */
    private Runnable planFunctionDecimal(Operand.Function function) {
        Origin origin = function.origin();
        return switch (function.intrinsic()) {
            // 長さは翻訳時に決まる。実行時に数えるものは何も無い
            case LENGTH -> planConstantLength(function, origin);
            case MAX -> planFold(function, "max", origin);
            case MIN -> planFold(function, "min", origin);
            case SUM -> planFold(function, "sum", origin);
            case ORD_MAX -> takesText(function)
                    ? planTextFold(function, "ordMaxText", DECIMAL)
                    : planFold(function, "ordMax", origin);
            case ORD_MIN -> takesText(function)
                    ? planTextFold(function, "ordMinText", DECIMAL)
                    : planFold(function, "ordMin", origin);
            case RANGE -> planFold(function, "range", origin);
            case MEDIAN -> planFold(function, "median", origin);
            case MIDRANGE -> planFold(function, "midrange", origin);
            case INTEGER_OF_DATE -> planUnary(function, "integerOfDate", origin);
            case INTEGER_OF_DAY -> planUnary(function, "integerOfDay", origin);
            case DATE_OF_INTEGER -> planUnary(function, "dateOfInteger", origin);
            case DAY_OF_INTEGER -> planUnary(function, "dayOfInteger", origin);
            case INTEGER -> planUnary(function, "integer", origin);
            case INTEGER_PART -> planUnary(function, "integerPart", origin);
            case FACTORIAL -> planUnary(function, "factorial", origin);
            case MOD -> planBinary(function, "mod", origin);
            case REM -> planBinary(function, "rem", origin);
            case ANNUITY -> planBinary(function, "annuity", origin);
            case MEAN -> planFold(function, "mean", origin);
            case VARIANCE -> planFold(function, "variance", origin);
            case STANDARD_DEVIATION -> planFold(function, "standardDeviation", origin);
            case PRESENT_VALUE -> planFold(function, "presentValue", origin);
            case SQRT -> planUnary(function, "sqrt", origin);
            case LOG -> planUnary(function, "log", origin);
            case LOG10 -> planUnary(function, "log10", origin);
            case EXP -> planUnary(function, "exp", origin);
            case EXP10 -> planUnary(function, "exp10", origin);
            case SIN -> planUnary(function, "sin", origin);
            case COS -> planUnary(function, "cos", origin);
            case TAN -> planUnary(function, "tan", origin);
            case ASIN -> planUnary(function, "asin", origin);
            case ACOS -> planUnary(function, "acos", origin);
            case ATAN -> planUnary(function, "atan", origin);
            case RANDOM -> planRandom(function, origin);
            case ORD -> planOrd(function);
            case NUMVAL -> planReading(function, "numval");
            case NUMVAL_C -> planNumvalC(function);
            default -> {
                report(origin, "FUNCTION " + function.intrinsic().spelling()
                        + " does not return a numeric value");
                yield null;
            }
        };
    }

    /**
     * {@code FUNCTION LENGTH}。
     *
     * <p>項目の文字位置の数は<b>データ部を読んだ時点で決まっている</b>。実行時に数えると、
     * 数えるための命令を出すことになるうえ、答えは同じである。
     */
    private Runnable planConstantLength(Operand.Function function, Origin origin) {
        int length = alphanumericLength(argument(function, 0));
        if (length <= 0) {
            report(origin, "FUNCTION LENGTH requires an item whose length is known"
                    + " at compile time");
            return null;
        }
        String field = decimalConstant(Decimal.of(length, 0));
        return () -> run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, DECIMAL);
    }

    /** 引数をいくつでも取る関数。{@link Decimal} の配列にして渡す。 */
    private Runnable planFold(Operand.Function function, String method, Origin origin) {
        List<Runnable> values = new ArrayList<>();
        for (int i = 0; i < function.arguments().size(); i++) {
            Runnable value = planNumericArgument(function, i, origin);
            if (value == null) {
                return null;
            }
            values.add(value);
        }
        return () -> {
            push(values.size());
            run.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(Decimal.class));
            for (int i = 0; i < values.size(); i++) {
                run.visitInsn(Opcodes.DUP);
                push(i);
                values.get(i).run();
                run.visitInsn(Opcodes.AASTORE);
            }
            run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, method,
                    "([" + DECIMAL + ")" + DECIMAL, false);
        };
    }

    private Runnable planUnary(Operand.Function function, String method, Origin origin) {
        Runnable value = planNumericArgument(function, 0, origin);
        if (value == null) {
            return null;
        }
        return () -> {
            value.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, method,
                    "(" + DECIMAL + ")" + DECIMAL, false);
        };
    }

    private Runnable planBinary(Operand.Function function, String method, Origin origin) {
        Runnable left = planNumericArgument(function, 0, origin);
        Runnable right = planNumericArgument(function, 1, origin);
        if (left == null || right == null) {
            return null;
        }
        return () -> {
            left.run();
            right.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, method,
                    "(" + DECIMAL + DECIMAL + ")" + DECIMAL, false);
        };
    }

    /** バイト列を読んで数値を返す関数。 */
    private Runnable planReading(Operand.Function function, String method) {
        Runnable value = planAlphanumericArgument(function, 0);
        if (value == null) {
            return null;
        }
        return () -> {
            value.run();
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, method,
                    "([B" + CODE_PAGE + ")" + DECIMAL, false);
        };
    }

    /** {@code FUNCTION ORD}。答えは<b>照合順序の何番目か</b>である。 */
    private Runnable planOrd(Operand.Function function) {
        Runnable value = planAlphanumericArgument(function, 0);
        if (value == null) {
            return null;
        }
        return () -> {
            value.run();
            loadCollating();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, "ord",
                    "([B" + COLLATING + ")" + DECIMAL, false);
        };
    }

    /** {@code FUNCTION NUMVAL-C}。第 2 引数の通貨記号は省ける。 */
    private Runnable planNumvalC(Operand.Function function) {
        Runnable value = planAlphanumericArgument(function, 0);
        Runnable currency = function.arguments().size() > 1
                ? planAlphanumericArgument(function, 1)
                : () -> run.visitFieldInsn(Opcodes.GETSTATIC, internal,
                        bytesConstant(new byte[0]), "[B");
        if (value == null || currency == null) {
            return null;
        }
        return () -> {
            value.run();
            currency.run();
            loadCodePage();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS, "numvalC",
                    "([B[B" + CODE_PAGE + ")" + DECIMAL, false);
        };
    }

    /**
     * {@code FUNCTION RANDOM} (要件 FR-070)。
     *
     * <p>引数を書けば種になる。書かなければ<b>前の続き</b>を返すので、並びを持っている
     * {@link ProgramContext} を渡す。
     */
    private Runnable planRandom(Operand.Function function, Origin origin) {
        if (function.arguments().isEmpty()) {
            return () -> {
                run.visitVarInsn(Opcodes.ALOAD, 2);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "random",
                        "(L" + Type.getInternalName(ProgramContext.class) + ";)" + DECIMAL,
                        false);
            };
        }
        Runnable seed = planNumericArgument(function, 0, origin);
        if (seed == null) {
            return null;
        }
        return () -> {
            seed.run();
            run.visitVarInsn(Opcodes.ALOAD, 2);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "random",
                    "(" + DECIMAL + "L" + Type.getInternalName(ProgramContext.class) + ";)"
                            + DECIMAL, false);
        };
    }

    /** 数値の引数。式を書いてもよい。 */
    private Runnable planNumericArgument(Operand.Function function, int index, Origin origin) {
        Expression argument = function.arguments().get(index);
        return planExpression(argument, IntermediateDigits.of(argument, List.of()), origin);
    }

    /** 文字の引数。式は書けないことを意味解析が確かめてある。 */
    private Runnable planAlphanumericArgument(Operand.Function function, int index) {
        Operand operand = argument(function, index);
        return planSourceBytes(operand, function.origin(), alphanumericLength(operand));
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
    /**
     * 実行の入口。
     *
     * <p>始まるのは<b>宣言部分のうしろから</b>である (要件 FR-105)。宣言節は入出力で異常が
     * 起きたときにだけ呼ばれるものであり、通常の流れで通ってはならない。
     */
    private void emitRun(int from, int paragraphCount) {
        run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", RUN_DESCRIPTOR, null, null);
        run.visitCode();
        if (paragraphCount > from) {
            emitPerformRange(from, paragraphCount - 1);
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
        if (hasAlterable && hasIndependentSegment) {
            // 独立段へ別の段から入るたびに、その段の ALTER を元へ戻す (要件 FR-061)
            dispatch.visitVarInsn(Opcodes.ALOAD, 0);
            dispatch.visitVarInsn(Opcodes.ALOAD, 0);
            dispatch.visitFieldInsn(Opcodes.GETFIELD, internal, ALTERED, "[I");
            dispatch.visitFieldInsn(Opcodes.GETSTATIC, internal, ALTER_INITIAL, "[I");
            dispatch.visitFieldInsn(Opcodes.GETSTATIC, internal, SEGMENTS, "[I");
            dispatch.visitVarInsn(Opcodes.ILOAD, 1);
            dispatch.visitVarInsn(Opcodes.ALOAD, 0);
            dispatch.visitFieldInsn(Opcodes.GETFIELD, internal, CURRENT_SEGMENT, "I");
            dispatch.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "enterParagraph",
                    "([I[I[III)I", false);
            dispatch.visitFieldInsn(Opcodes.PUTFIELD, internal, CURRENT_SEGMENT, "I");
        }
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
                "runMain", "()I", true);
        // RETURN-CODE がプロセスの終了コードになる (要件 FR-084)
        main.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
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
        if (alterInitial[index] != NOT_ALTERABLE) {
            // 書き換えられる段落は、飛び先を表から読んで返す。
            // 中身は GO TO 1 つだけなので、これで置き換えてしまってよい
            run.visitVarInsn(Opcodes.ALOAD, 0);
            run.visitFieldInsn(Opcodes.GETFIELD, internal, ALTERED, "[I");
            push(index);
            run.visitInsn(Opcodes.IALOAD);
            if (alterInitial[index] == ALTER_UNSET) {
                // 行き先を持たずに書かれた段落。ALTER される前に通ったら止める
                Label decided = new Label();
                run.visitInsn(Opcodes.DUP);
                run.visitJumpInsn(Opcodes.IFGE, decided);
                run.visitInsn(Opcodes.POP);
                emitUnalteredGoTo(paragraphNames.get(index));
                run.visitLabel(decided);
            }
            run.visitInsn(Opcodes.IRETURN);
            run.visitMaxs(0, 0);
            run.visitEnd();
            return;
        }
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

    /**
     * {@code ALTER} で書き換えられる段落と、段分けの段番号を控える (要件 FR-061, FR-063)。
     *
     * <p>書き換えられる段落は<b>{@code GO TO} だけを書いた段落</b>である。その段落は
     * 飛び先を定数で返すのではなく、書き換えられる<b>表から読んで返す</b>ようにする。
     */
    private void planAlterable(List<ProcedureBuilder.Paragraph> paragraphs) {
        alterInitial = new int[paragraphs.size()];
        segments = new int[paragraphs.size()];
        hasAlterable = false;
        hasIndependentSegment = false;
        for (int i = 0; i < paragraphs.size(); i++) {
            ProcedureBuilder.Paragraph paragraph = paragraphs.get(i);
            segments[i] = paragraph.segment();
            hasIndependentSegment |= paragraph.segment() >= INDEPENDENT_SEGMENT;
            Statement.GoTo goTo = paragraph.alterableGoTo();
            alterInitial[i] = alterInitialOf(goTo);
            hasAlterable |= alterInitial[i] != NOT_ALTERABLE;
        }
    }

    /**
     * 書かれたままの飛び先。
     *
     * <p>3 通りある。<b>書き換えられない段落</b> ({@link #NOT_ALTERABLE})、
     * 書き換えられて<b>初めの行き先を持つ</b>段落 (0 以上)、そして書き換えられるが
     * <b>行き先をまだ持たない</b>段落 ({@link #ALTER_UNSET}) である。3 つ目は
     * {@code GO TO.} とだけ書いた段落で、{@code ALTER} が書き込むまで通ってはならない。
     */
    private int alterInitialOf(Statement.GoTo goTo) {
        if (goTo == null) {
            return NOT_ALTERABLE;
        }
        return goTo.target() == null ? ALTER_UNSET : paragraphNames.indexOf(goTo.target());
    }

    /** 書き換えられない段落。 */
    private static final int NOT_ALTERABLE = -1;
    /** 書き換えられるが、行き先をまだ持たない段落。 */
    private static final int ALTER_UNSET = -2;

    /** ここから上が独立段である ({@code Ops.enterParagraph} と対)。 */
    private static final int INDEPENDENT_SEGMENT = 50;

    /** 段落ごとの、書かれたままの飛び先。書き換えられない段落は {@code -1}。 */
    private int[] alterInitial = new int[0];
    /** 段落ごとの段番号。 */
    private int[] segments = new int[0];
    /** 書き換えられる段落があるか。無ければ表そのものを出さない。 */
    private boolean hasAlterable;
    /** 独立段があるか。 */
    private boolean hasIndependentSegment;

    private static String paragraphMethod(int index) {
        return "paragraph$" + index;
    }

    private void planMove(Statement.Move move, List<Runnable> body) {
        for (Statement.Move.Target target : move.targets()) {
            Runnable offset = planAddress(target.reference(), move.origin());
            if (offset == null) {
                return;
            }
            switch (target.kind()) {
                case ALPHANUMERIC -> planAlphanumericMove(move, target, offset, body);
                case NUMERIC -> planNumericMove(move, target, offset, body);
                case NUMERIC_EDITED -> planEditedMove(move, target, offset, body);
            }
        }
    }

    /**
     * 英数字転記。
     *
     * <p>受取側の長さは<b>実行時に決まってよい</b> ({@code WS-A (1: WS-N)})。
     * 図形定数を広げる長さだけは翻訳時に要るので、そこには<b>項目全体の長さ</b>を使う。
     * 部分参照の長さは項目全体を超えないので、広げてから切り詰めた結果は同じになる。
     */
    private void planAlphanumericMove(Statement.Move move, Statement.Move.Target target,
                                      Runnable offset, List<Runnable> body) {
        Runnable length = planLength(target.reference(), move.origin());
        if (length == null) {
            return;
        }
        int widest = target.reference().constantLength()
                .orElse(target.reference().item().length());
        Runnable source = planSourceBytes(move.source(), move.origin(), widest);
        if (source == null) {
            return;
        }
        boolean justified = target.reference().item().justified();
        body.add(() -> {
            source.run();
            offset.run();
            length.run();
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
        if (offset == null || item.picture() == null) {
            return null;
        }
        String rounding = target.rounded() ? "NEAREST_AWAY_FROM_ZERO" : "TRUNCATION";
        boolean edited = DataCategory.of(target.reference()) == DataCategory.NUMERIC_EDITED;
        String field = edited
                ? pictureConstant(item.picture())
                : numericItemConstant(item, origin);
        if (field == null) {
            return null;
        }
        return () -> {
            Label done = new Label();
            run.visitVarInsn(Opcodes.ALOAD, slot);
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field,
                    edited ? PICTURE : NUMERIC_ITEM);
            offset.run();
            loadRounding(rounding);
            if (edited) {
                loadCodePage();
            }
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS,
                    edited ? "storeEditedChecked" : "storeChecked",
                    "(" + DECIMAL + (edited ? PICTURE : NUMERIC_ITEM) + "L" + STORAGE + ";I"
                            + Type.getDescriptor(CobolRounding.class)
                            + (edited ? CODE_PAGE : "") + ")Z", false);
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
        if (binary.operator() == Expression.Operator.POWER) {
            return () -> {
                left.run();
                right.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "power",
                        "(" + DECIMAL + DECIMAL + ")" + DECIMAL, false);
                // 近似が入る答えは桁が伸びる。中間結果の上限まで削る
                push(scale);
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "truncate",
                        "(" + DECIMAL + "I)" + DECIMAL, false);
            };
        }
        String name = switch (binary.operator()) {
            case ADD -> "add";
            case SUBTRACT -> "subtract";
            case MULTIPLY -> "multiply";
            case DIVIDE, POWER -> throw new IllegalStateException("handled above");
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
            DataItem item = target.reference().item();
            if (item.picture() == null) {
                return;
            }
            int scale = item.picture().scale();
            String rounding = target.rounded() ? "NEAREST_AWAY_FROM_ZERO" : "TRUNCATION";

            List<Runnable> value = new ArrayList<>();
            if (statement.accumulate() != null) {
                // 受取項目の現在値から始める。この形の受取項目は数値に限られる
                Runnable read = planReadReceiver(target.reference(), statement.origin());
                if (read == null) {
                    return;
                }
                value.add(read);
            }
            if (!planFold(statement, value, scale, rounding)) {
                return;
            }
            if (statement.accumulate() != null) {
                value.add(() -> emitOperator(statement.accumulate(), scale, rounding));
            }

            Runnable store = planStore(target.reference(), () -> value.forEach(Runnable::run),
                    rounding, statement.origin());
            if (store == null) {
                return;
            }
            body.add(store);
        }
    }

    /**
     * {@code GIVING} を書かない算術文が、受取項目の現在値を読む命令。
     *
     * <p>この形の受取項目は<b>計算に加わる</b>ので数値項目に限られる。数字編集項目が
     * ここへ来ることは意味解析が防いでいる。
     */
    private Runnable planReadReceiver(DataReference reference, Origin origin) {
        Runnable offset = planAddress(reference, origin);
        String field = numericItemConstant(reference.item(), origin);
        if (offset == null || field == null) {
            return null;
        }
        return () -> {
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            offset.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readNumeric",
                    "(" + NUMERIC_ITEM + "L" + STORAGE + ";I)" + DECIMAL, false);
        };
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
        DataItem item = target.reference().item();
        if (item.picture() == null) {
            return null;
        }
        int scale = item.picture().scale();
        String rounding = target.rounded() ? "NEAREST_AWAY_FROM_ZERO" : "TRUNCATION";
        Runnable read = statement.accumulate() == null
                ? () -> { }
                : planReadReceiver(target.reference(), statement.origin());
        if (read == null) {
            return null;
        }
        int result = nextLocal++;
        Runnable store = planCheckedStore(target, result, flag, statement.origin());
        if (store == null) {
            return null;
        }

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
                read.run();
                run.visitVarInsn(Opcodes.ALOAD, folded);
                emitOperator(statement.accumulate(), scale, rounding);
            } else {
                run.visitVarInsn(Opcodes.ALOAD, folded);
            }
            run.visitVarInsn(Opcodes.ASTORE, result);
            store.run();
            run.visitJumpInsn(Opcodes.GOTO, done);
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
        if (record.section() == DataSection.SPECIAL_REGISTER) {
            // 実行の全体で 1 つの置き場を指す。呼ぶ側と呼ばれる側が同じものを見る
            return () -> {
                run.visitVarInsn(Opcodes.ALOAD, 2);
                run.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(ProgramContext.class), "registers",
                        "()L" + STORAGE + ";", false);
                offset.run();
            };
        }
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
        String item = describe(record);
        return () -> {
            emitArgument(index, item);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA_VIEW, "storage",
                    "()L" + Type.getInternalName(Storage.class) + ";", false);
            // 渡された領域の始まりからの位置になる
            emitArgument(index, item);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA_VIEW, "offset", "()I", false);
            offset.run();
            run.visitInsn(Opcodes.IADD);
        };
    }

    /**
     * {@code USING} の {@code index} 番目に渡された領域を積む。
     *
     * <p>配列から直に取らずランタイムを通すのは、<b>渡されていないときに打ち切る</b>ため
     * である。ホストではその場合の中身が定まらず、運が悪ければ {@code S0C4} で終わり、
     * 運がよければ誤った値のまま処理が進む (要件 FR-141)。
     */
    private void emitArgument(int index, String item) {
        run.visitVarInsn(Opcodes.ALOAD, ARGUMENTS_LOCAL);
        push(index);
        run.visitLdcInsn(item);
        run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "linkage",
                "([L" + DATA_VIEW + ";ILjava/lang/String;)L" + DATA_VIEW + ";", false);
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
            DataReference.Subscript.Variable given =
                    (DataReference.Subscript.Variable) subscript;
            Runnable push = planSourceDecimal(new Operand.Reference(given.reference()), origin);
            if (push == null) {
                return null;
            }
            DataItem table = tables.get(i);
            variable.add(() -> {
                push.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
                // 相対指定のずれは、範囲を確かめる前に足す。確かめるのは足したあとの値である
                addOffset(given.offset());
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
        DataReference.Subscript.Variable given =
                (DataReference.Subscript.Variable) reference.refMod().leftmost();
        Runnable push = planSourceDecimal(new Operand.Reference(given.reference()), origin);
        if (push == null) {
            return null;
        }
        return () -> {
            push.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
            addOffset(given.offset());
            emitRefModCheck(reference, origin);
            run.visitInsn(Opcodes.ICONST_1);
            run.visitInsn(Opcodes.ISUB);
            run.visitInsn(Opcodes.IADD);
        };
    }

    /**
     * 参照の長さ。<b>長さは翻訳時に決まっていなければならない</b>。
     *
     * <p>長さそのものを翻訳時の値として使う道 (定数のバイト列を作る、編集の形を決める)
     * が引く。実行時に決まってよい道は {@link #planLength} を使う。
     */
    private OptionalInt lengthOf(DataReference reference, Origin origin) {
        OptionalInt length = reference.constantLength();
        if (length.isEmpty()) {
            report(origin, "a reference modification whose length is not a constant"
                    + " is not supported yet");
        }
        return length;
    }

    /**
     * 参照の長さを積む命令 (要件 FR-026、暫定判断 P-027)。
     *
     * <p>部分参照の長さは<b>データ項目で書ける</b>。{@code WS-A (1: WS-N)} の {@code WS-N}
     * は実行時にしか決まらない。ランタイムの演算はどれも長さを引数で受け取るので、
     * <b>定数を積むところを計算に差し替える</b>だけで通る。
     *
     * <p>長さを省いた {@code WS-A (WS-I:)} は「項目の終わりまで」であり、
     * 開始位置が実行時に決まればこれも実行時に決まる。
     *
     * @return 積む命令。組み立てられなければ {@code null}
     */
    private Runnable planLength(DataReference reference, Origin origin) {
        OptionalInt constant = reference.constantLength();
        if (constant.isPresent()) {
            int length = constant.getAsInt();
            return () -> push(length);
        }
        DataReference.RefMod refMod = reference.refMod();
        if (refMod.length() != null) {
            return planSubscriptValue(refMod.length(), origin);
        }
        // 長さの省略。項目の終わりまでなので「全体の長さ - (開始位置 - 1)」である
        Runnable leftmost = planSubscriptValue(refMod.leftmost(), origin);
        if (leftmost == null) {
            return null;
        }
        int whole = reference.item().length();
        return () -> {
            push(whole + 1);
            leftmost.run();
            run.visitInsn(Opcodes.ISUB);
        };
    }

    /** 添字 1 個の値を {@code int} として積む命令。 */
    private Runnable planSubscriptValue(DataReference.Subscript subscript, Origin origin) {
        if (subscript instanceof DataReference.Subscript.Constant value) {
            return () -> push(value.value());
        }
        if (!(subscript instanceof DataReference.Subscript.Variable given)) {
            report(origin, "ALL may not be written as a reference modification");
            return null;
        }
        Runnable push = planSourceDecimal(new Operand.Reference(given.reference()), origin);
        if (push == null) {
            return null;
        }
        return () -> {
            push.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
            addOffset(given.offset());
        };
    }

    // ---- 送出側 ----

    /** 送出側をバイト列として積む命令。 */
    private Runnable planSourceBytes(Operand source, Origin origin, int targetLength) {
        if (source instanceof Operand.Function function) {
            return planFunctionBytes(function);
        }
        if (source instanceof Operand.Literal literal) {
            byte[] bytes = literalBytes(literal.value(), targetLength);
            String field = bytesConstant(bytes);
            return () -> run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, "[B");
        }
        DataReference reference = ((Operand.Reference) source).reference();
        Runnable offset = planAddress(reference, origin);
        Runnable length = planLength(reference, origin);
        if (offset == null || length == null) {
            return null;
        }
        return () -> {
            offset.run();
            length.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "read",
                    "(L" + STORAGE + ";II)[B", false);
        };
    }

    /** 送出側を {@link Decimal} として積む命令。 */
    private Runnable planSourceDecimal(Operand source, Origin origin) {
        if (source instanceof Operand.Function function) {
            return planFunctionDecimal(function);
        }
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
        if (DataCategory.of(reference) == DataCategory.NUMERIC_EDITED) {
            // 数字編集項目からは<b>編集を解いて</b>値を取り出す (de-editing)
            int scale = reference.item().picture() == null ? 0 : reference.item().picture().scale();
            return () -> {
                offset.run();
                push(length.getAsInt());
                push(scale);
                loadCodePage();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "deEdit",
                        "(L" + STORAGE + ";III" + CODE_PAGE + ")" + DECIMAL, false);
            };
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
        if (value instanceof LiteralValue.Text text && isDigits(text.text())) {
            // 数字だけでできた英数字定数は、<b>符号なしの整数</b>として読む。
            // 英数字の項目を数値へ移すのと同じ扱いである
            return Decimal.parse(text.text());
        }
        report(origin, "a numeric receiver requires a numeric literal");
        return null;
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
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
        String key = "N:" + item.picture().source() + ":" + usage + ":" + item.signPosition()
                + ":" + decimalPoint;
        return constants.computeIfAbsent(key, k -> {
            String name = "N" + constants.size();
            return new Constant(name, NUMERIC_ITEM, () -> {
                clinit.visitLdcInsn(item.picture().source());
                clinit.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(Usage.class),
                        usage.name(), Type.getDescriptor(Usage.class));
                // 通貨記号は翻訳時に決まる。PICTURE の解釈がこれに依る
                clinit.visitLdcInsn((int) currency);
                // 小数点も翻訳時に決まる。ここが食い違うと、実行時に PICTURE が
                // 別の意味に読まれる
                clinit.visitLdcInsn((int) decimalPoint);
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(NumericItem.class), "of",
                        "(Ljava/lang/String;" + Type.getDescriptor(Usage.class) + "CC)"
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

    /**
     * PICTURE を組み立てる定数。
     *
     * <p>字面だけでは足りない。{@code BLANK WHEN ZERO} は PICTURE 文字列の外に書く
     * 句なので、字面から作り直すと落ちてしまう。定数の名前もこれで分ける
     * — 同じ {@code 9(5)} でも、空白にするものとしないものは別の PICTURE である。
     */
    private String pictureConstant(Picture picture) {
        boolean blank = picture.blankWhenZero();
        String key = "P:" + picture.source() + ":" + decimalPoint + ":" + blank;
        return constants.computeIfAbsent(key, k -> {
            String name = "P" + constants.size();
            return new Constant(name, PICTURE, () -> {
                clinit.visitLdcInsn(picture.source());
                clinit.visitLdcInsn((int) currency);
                clinit.visitLdcInsn((int) decimalPoint);
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(PictureParser.class), "parse",
                        "(Ljava/lang/String;CC)" + PICTURE, false);
                if (blank) {
                    clinit.visitInsn(Opcodes.ICONST_1);
                    clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            Type.getInternalName(Picture.class), "withBlankWhenZero",
                            "(Z)" + PICTURE, false);
                }
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

    /**
     * 照合順序を組み立てる定数 (要件 FR-054)。
     *
     * <p>256 バイトの表を定数として持ち、クラスの初期化で
     * {@link CollatingSequence} に包む。表そのものは翻訳時に決まっている。
     */
    private String collatingConstant(byte[] table) {
        String bytes = bytesConstant(table);
        return constants.computeIfAbsent("C:" + bytes, k -> {
            String name = "C" + constants.size();
            return new Constant(name, COLLATING, () -> {
                clinit.visitFieldInsn(Opcodes.GETSTATIC, internal, bytes, "[B");
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(CollatingSequence.class), "of",
                        "([B)" + COLLATING, false);
            });
        }).name();
    }

    /** このプログラムの照合順序を積む。既定なら恒等の並びを積む。 */
    private void loadCollating() {
        if (collating == null) {
            String field = constants.computeIfAbsent("C:native", k -> {
                String name = "C" + constants.size();
                return new Constant(name, COLLATING, () -> clinit.visitMethodInsn(
                        Opcodes.INVOKESTATIC, Type.getInternalName(CollatingSequence.class),
                        "nativeOrder", "()" + COLLATING, false));
            }).name();
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, COLLATING);
            return;
        }
        run.visitFieldInsn(Opcodes.GETSTATIC, internal, collatingConstant(collating), COLLATING);
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

    /**
     * 作業場所の割り付けを生成クラスへ埋める (要件 FR-142)。
     *
     * <p>どのバイトがどの項目かを知っているのは翻訳の側である。実行時に手元にあるのは
     * バイト列だけなので、割り付けを持ち歩かせる。項目ごとにバイトコードを吐くと項目の
     * 多いプログラムでクラスファイルが膨らむので、初期イメージと同じく<b>文字列定数
     * 1 個</b>に畳む。
     */
    private void emitStorageMap() {
        if (layout == null) {
            return;
        }
        List<StorageMap.Entry> entries = new ArrayList<>();
        for (DataItem record : layout.records()) {
            if (record.section() == DataSection.LINKAGE
                    || record.section() == DataSection.SPECIAL_REGISTER) {
                // 連絡節の実体は呼ぶ側にある。特殊レジスタは実行の全体で 1 つである
                continue;
            }
            collectEntries(record, 0, entries);
        }
        if (entries.isEmpty()) {
            return;
        }
        String encoded = new StorageMap(entries).encoded();
        // フィールドの宣言は静的初期化子を書くところがまとめて行う
        constants.put("\0storageMap", new Constant("STORAGE_MAP", STORAGE_MAP, () -> {
            clinit.visitLdcInsn(encoded);
            clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(StorageMap.class), "parse",
                    "(Ljava/lang/String;)" + STORAGE_MAP, false);
        }));

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "storageMap",
                "()" + STORAGE_MAP, null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, internal, "STORAGE_MAP", STORAGE_MAP);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /** 項目とその下位を、書かれた順に並べる。 */
    private static void collectEntries(DataItem item, int depth, List<StorageMap.Entry> out) {
        if (item.name() != null) {
            // base() が立つのは 01 レベルだけである。配下の項目は根から辿る
            int offset = item.record().base() + item.offset();
            out.add(new StorageMap.Entry(depth, item.level(), item.name(), offset,
                    item.length(), Math.max(item.occurs(), 1), kindOf(item), pictureOf(item),
                    usageOf(item)));
        }
        for (DataItem child : item.children()) {
            // 名前のない項目 (FILLER) は段を増やさない。見せ方だけの話である
            collectEntries(child, item.name() == null ? depth : depth + 1, out);
        }
    }

    private static StorageMap.Kind kindOf(DataItem item) {
        if (!item.isElementary()) {
            return StorageMap.Kind.GROUP;
        }
        if (item.isIndex()) {
            return StorageMap.Kind.INDEX;
        }
        Picture picture = item.picture();
        return picture != null && picture.isNumeric()
                ? StorageMap.Kind.NUMBER
                : StorageMap.Kind.TEXT;
    }

    /** {@code USAGE} を書かなければ {@code DISPLAY} である。 */
    private static Usage usageOf(DataItem item) {
        return item.usage() == null ? Usage.DISPLAY : item.usage();
    }

    private static String pictureOf(DataItem item) {
        Picture picture = item.picture();
        return picture == null ? "" : picture.source();
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
        initAlterTables();

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
        if (statement.target() == null) {
            // 行き先の無い GO TO が、書き換えられる段落の外に書かれていた。
            // 書き換えようが無いので、通ったらそこで止める
            body.add(() -> emitUnalteredGoTo(currentParagraphName));
            return;
        }
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

    /**
     * {@code GO TO ... DEPENDING ON} を組み立てる (要件 FR-063)。
     *
     * <p>値が 1 なら 1 つ目、2 なら 2 つ目へ飛ぶ。<b>並びの外なら飛ばない</b>ので、
     * 飛び先表の外れ道は「何もせず下へ抜ける」になる。誤りにはならない。
     */
    private void planGoToDepending(Statement.GoToDepending statement, List<Runnable> body) {
        List<Integer> targets = new ArrayList<>();
        for (String name : statement.targets()) {
            int target = paragraphNames.indexOf(name);
            if (target < 0) {
                report(statement.origin(), "undefined paragraph: " + name);
                return;
            }
            targets.add(target);
        }
        Runnable selector = planSourceDecimal(new Operand.Reference(statement.selector()),
                statement.origin());
        if (selector == null) {
            return;
        }
        body.add(() -> {
            Label fallThrough = new Label();
            Label[] cases = new Label[targets.size()];
            for (int i = 0; i < cases.length; i++) {
                cases[i] = new Label();
            }
            selector.run();
            run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
            run.visitTableSwitchInsn(1, targets.size(), fallThrough, cases);
            for (int i = 0; i < cases.length; i++) {
                run.visitLabel(cases[i]);
                push(targets.get(i));
                run.visitInsn(Opcodes.IRETURN);
            }
            run.visitLabel(fallThrough);
        });
    }

    /**
     * 1 つの文 (センテンス) を出す (要件 FR-061)。
     *
     * <p>並べて出すだけだが、<b>終わりに印を打つ</b>。{@code NEXT SENTENCE} はここへ飛ぶ。
     * 文は入れ子にならないので印は 1 つでよいが、念のため外側のものを退避しておく。
     */
    private void planSentence(Statement.Sentence sentence, List<Runnable> body) {
        List<Runnable> inner = planStatements(sentence.body());
        body.add(() -> {
            Label end = new Label();
            Label outer = sentenceEnd;
            sentenceEnd = end;
            inner.forEach(Runnable::run);
            sentenceEnd = outer;
            run.visitLabel(end);
        });
    }

    /**
     * {@code NEXT SENTENCE} を出す (要件 FR-061)。
     *
     * <p>いまの文の終わりへ飛ぶ。{@code CONTINUE} との違いはここである。
     * {@code CONTINUE} は何もしないので、囲んでいる {@code IF} の外側にある
     * 同じ文の続きが実行される。
     */
    private void planNextSentence(Origin origin, List<Runnable> body) {
        body.add(() -> {
            if (sentenceEnd == null) {
                report(origin, "NEXT SENTENCE must be written inside a sentence");
                return;
            }
            run.visitJumpInsn(Opcodes.GOTO, sentenceEnd);
        });
    }

    /** いま出している文の終わりの印。{@code NEXT SENTENCE} の飛び先である。 */
    private Label sentenceEnd;

    /**
     * {@code ALTER} を組み立てる (要件 FR-063)。
     *
     * <p>書き換えられる段落は飛び先を表から読んで返すので、ここでするのは
     * <b>表を書き換えること</b>だけである。
     */
    private void planAlter(Statement.Alter statement, List<Runnable> body) {
        List<int[]> changes = new ArrayList<>();
        for (Statement.Alter.Change change : statement.changes()) {
            int from = paragraphNames.indexOf(change.from());
            int to = paragraphNames.indexOf(change.to());
            if (from < 0 || to < 0) {
                report(statement.origin(), "undefined paragraph: "
                        + (from < 0 ? change.from() : change.to()));
                return;
            }
            changes.add(new int[] {from, to});
        }
        body.add(() -> {
            for (int[] change : changes) {
                run.visitVarInsn(Opcodes.ALOAD, 0);
                run.visitFieldInsn(Opcodes.GETFIELD, internal, ALTERED, "[I");
                push(change[0]);
                push(change[1]);
                run.visitInsn(Opcodes.IASTORE);
            }
        });
    }

    /** 相対指定のずれを、積んである添字へ足す。0 なら何も出さない。 */
    private void addOffset(int offset) {
        if (offset == 0) {
            return;
        }
        push(offset);
        run.visitInsn(Opcodes.IADD);
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }
}
