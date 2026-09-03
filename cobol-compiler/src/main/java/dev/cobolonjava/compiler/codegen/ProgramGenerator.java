package dev.cobolonjava.compiler.codegen;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.semantic.Condition;
import dev.cobolonjava.compiler.semantic.DataCategory;
import dev.cobolonjava.compiler.semantic.DataItem;
import dev.cobolonjava.compiler.semantic.DataReference;
import dev.cobolonjava.compiler.semantic.InitialImage;
import dev.cobolonjava.compiler.semantic.LiteralValue;
import dev.cobolonjava.compiler.semantic.Operand;
import dev.cobolonjava.compiler.semantic.ProcedureBuilder;
import dev.cobolonjava.compiler.semantic.Statement;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.picture.PictureParser;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.runtime.storage.Storage;
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

    private static final String OPS = Type.getInternalName(Ops.class);
    private static final String SUPPORT = Type.getInternalName(ProgramSupport.class);
    private static final String STORAGE = Type.getInternalName(Storage.class);
    private static final String CODE_PAGE = Type.getDescriptor(CodePage.class);
    private static final String NUMERIC_ITEM = Type.getDescriptor(NumericItem.class);
    private static final String PICTURE = Type.getDescriptor(Picture.class);
    private static final String DECIMAL = Type.getDescriptor(Decimal.class);

    private final String className;
    private final CodePage codePage;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /** 静的初期化子で作る定数。綴りから field 名を引く。 */
    private final Map<String, Constant> constants = new LinkedHashMap<>();

    /** 局所変数の 0 番は {@code this}、1 番は記憶域である。 */
    private static final int FIRST_FREE_LOCAL = 2;

    private ClassWriter writer;
    private String internal;
    private List<String> paragraphNames = new ArrayList<>();
    private int nextLocal = FIRST_FREE_LOCAL;
    private MethodVisitor run;
    private MethodVisitor clinit;
    private byte[] initialStorageBytes;

    private ProgramGenerator(String className, CodePage codePage) {
        this.className = className;
        this.codePage = codePage;
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
        return generate(programName, procedure, image, CodePages.DEFAULT);
    }

    /** コードページを指定してプログラムを生成する。 */
    public static Result generate(String programName, ProcedureBuilder.Result procedure,
                                  InitialImage.Result image, CodePage codePage) {
        return new ProgramGenerator(classNameOf(programName), codePage).emit(procedure, image);
    }

    /** COBOL のプログラム名を Java のクラス名にする。ハイフンは下線に読み替える。 */
    public static String classNameOf(String programName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < programName.length(); i++) {
            char c = programName.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return "cobol.generated." + sb;
    }

    private Result emit(ProcedureBuilder.Result procedure, InitialImage.Result image) {
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
            if (statement instanceof Statement.Move move) {
                planMove(move, body);
            } else if (statement instanceof Statement.Arithmetic arithmetic) {
                planArithmetic(arithmetic, body);
            } else if (statement instanceof Statement.If branch) {
                planIf(branch, body);
            } else if (statement instanceof Statement.Perform perform) {
                planPerform(perform, body);
            } else if (statement instanceof Statement.Continue) {
                // 何もしない文である
                continue;
            } else {
                report(statement.origin(), "statement is not supported by the generator yet");
            }
        }
        return body;
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
        // THRU は範囲の段落を順に呼ぶ
        return () -> {
            for (int i = from; i <= to; i++) {
                callParagraph(i);
            }
        };
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
            return;
        }
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
        Condition.Comparison comparison =
                jumpWhenTrue ? relation.comparison() : relation.comparison().negate();
        run.visitJumpInsn(branchOpcode(comparison), target);
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

    /** {@code run} は段落を書かれた順に呼ぶ。素直に流れる実行がこれにあたる。 */
    private void emitRun(int paragraphCount) {
        run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "(L" + STORAGE + ";)V", null, null);
        run.visitCode();
        for (int i = 0; i < paragraphCount; i++) {
            callParagraph(i);
        }
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
    }

    private void emitParagraph(int index, List<Runnable> body) {
        run = writer.visitMethod(Opcodes.ACC_PRIVATE, paragraphMethod(index),
                "(L" + STORAGE + ";)V", null, null);
        run.visitCode();
        body.forEach(Runnable::run);
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
    }

    private void callParagraph(int index) {
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitVarInsn(Opcodes.ALOAD, 1);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, internal, paragraphMethod(index),
                "(L" + STORAGE + ";)V", false);
    }

    private static String paragraphMethod(int index) {
        return "paragraph$" + index;
    }

    private void planMove(Statement.Move move, List<Runnable> body) {
        if (move.corresponding()) {
            report(move.origin(), "MOVE CORRESPONDING is not supported yet");
            return;
        }
        for (Statement.Move.Target target : move.targets()) {
            Runnable offset = planOffset(target.reference(), move.origin());
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
            run.visitVarInsn(Opcodes.ALOAD, 1);
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
            run.visitVarInsn(Opcodes.ALOAD, 1);
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
            run.visitVarInsn(Opcodes.ALOAD, 1);
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
    private void planArithmetic(Statement.Arithmetic statement, List<Runnable> body) {
        if (statement.isChecked()) {
            planCheckedArithmetic(statement, body);
            return;
        }
        for (Statement.Arithmetic.Target target : statement.targets()) {
            Runnable offset = planOffset(target.reference(), statement.origin());
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
                    run.visitVarInsn(Opcodes.ALOAD, 1);
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
                run.visitVarInsn(Opcodes.ALOAD, 1);
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
        List<Runnable> onError = planStatements(statement.sizeError().onError());
        List<Runnable> otherwise = planStatements(statement.sizeError().otherwise());

        body.add(() -> {
            run.visitInsn(Opcodes.ICONST_0);
            run.visitVarInsn(Opcodes.ISTORE, flag);
            perTarget.forEach(Runnable::run);

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

    private Runnable planCheckedTarget(Statement.Arithmetic statement,
                                       Statement.Arithmetic.Target target, int flag) {
        Runnable offset = planOffset(target.reference(), statement.origin());
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
                run.visitVarInsn(Opcodes.ALOAD, 1);
                offset.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readNumeric",
                        "(" + NUMERIC_ITEM + "L" + STORAGE + ";I)" + DECIMAL, false);
                run.visitVarInsn(Opcodes.ALOAD, folded);
                emitOperator(statement.accumulate(), scale, rounding);
            } else {
                run.visitVarInsn(Opcodes.ALOAD, folded);
            }
            run.visitFieldInsn(Opcodes.GETSTATIC, internal, field, NUMERIC_ITEM);
            run.visitVarInsn(Opcodes.ALOAD, 1);
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
            variable.add(() -> {
                push.run();
                run.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "toInt", "(" + DECIMAL + ")I", false);
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
        Runnable offset = planOffset(reference, origin);
        OptionalInt length = lengthOf(reference, origin);
        if (offset == null || length.isEmpty()) {
            return null;
        }
        return () -> {
            run.visitVarInsn(Opcodes.ALOAD, 1);
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
        Runnable offset = planOffset(reference, origin);
        OptionalInt length = lengthOf(reference, origin);
        if (offset == null || length.isEmpty()) {
            return null;
        }
        if (!DataCategory.of(reference).isNumeric()) {
            // 英数字項目から数値項目への転記。送出側は符号なしの整数として読む
            return () -> {
                run.visitVarInsn(Opcodes.ALOAD, 1);
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
            run.visitVarInsn(Opcodes.ALOAD, 1);
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
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(NumericItem.class), "of",
                        "(Ljava/lang/String;" + Type.getDescriptor(Usage.class) + ")"
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
                clinit.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(PictureParser.class), "parse",
                        "(Ljava/lang/String;)" + PICTURE, false);
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
        run.visitLdcInsn(value);
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }
}
