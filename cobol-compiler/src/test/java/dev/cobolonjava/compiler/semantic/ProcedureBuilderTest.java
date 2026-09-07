package dev.cobolonjava.compiler.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.parser.CobolParsing;
import dev.cobolonjava.compiler.source.Preprocessor;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ProcedureBuilderTest {

    private static final String FILE = "MAIN.cbl";

    /** 作業場所と手続き部の中身だけを渡し、前後の決まり文句を補う。 */
    private static ProcedureBuilder.Result build(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       ").append("PROCEDURE DIVISION.").append('\n');
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }

        CobolParsing.Result parsed =
                CobolParsing.parse(Preprocessor.withoutCopybooks(), FILE, sb.toString());
        assertTrue(parsed.succeeded(), () -> "syntax errors: " + parsed.diagnostics());
        DataDivisionBuilder.Result data = DataDivisionBuilder.build(parsed.tree().programUnit(0));
        assertTrue(data.succeeded(), () -> "layout errors: " + data.diagnostics());
        return ProcedureBuilder.build(parsed.tree().programUnit(0), data.layout());
    }

    private static ProcedureBuilder.Result buildOk(List<String> storage, String... procedure) {
        ProcedureBuilder.Result result = build(storage, procedure);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        return result;
    }

    private static Statement.Move firstMove(ProcedureBuilder.Result result) {
        return assertInstanceOf(Statement.Move.class, result.statements().get(0));
    }

    private static final List<String> SIMPLE = List.of(
            "01 WS-A PIC X(5).",
            "01 WS-B PIC X(5).");

    @Test
    @DisplayName("MOVE は送り出しと受け取りを結び付ける (FR-060)")
    void moveConnectsItsSourceAndTargets() {
        Statement.Move move = firstMove(buildOk(SIMPLE, "MOVE WS-A TO WS-B."));

        Operand.Reference source = assertInstanceOf(Operand.Reference.class, move.source());
        assertEquals("WS-A", source.reference().item().name());
        assertEquals(1, move.targets().size());
        assertEquals("WS-B", move.targets().get(0).reference().item().name());
        assertFalse(move.corresponding());
    }

    @Test
    @DisplayName("受け取り側は複数書ける (FR-060)")
    void moveTakesSeveralTargets() {
        Statement.Move move = firstMove(buildOk(
                List.of("01 WS-A PIC X.", "01 WS-B PIC X.", "01 WS-C PIC X."),
                "MOVE WS-A TO WS-B WS-C."));
        assertEquals(List.of("WS-B", "WS-C"),
                move.targets().stream().map(t -> t.reference().item().name()).toList());
    }

    @Test
    @DisplayName("定数を送り出せる (FR-060)")
    void aLiteralCanBeTheSource() {
        Statement.Move move = firstMove(buildOk(SIMPLE, "MOVE 'AB' TO WS-B."));
        Operand.Literal literal = assertInstanceOf(Operand.Literal.class, move.source());
        assertEquals(new LiteralValue.Text("AB"), literal.value());
    }

    @Test
    @DisplayName("図形定数も送り出せる (FR-060)")
    void aFigurativeConstantCanBeTheSource() {
        Statement.Move move = firstMove(buildOk(SIMPLE, "MOVE SPACES TO WS-B."));
        Operand.Literal literal = assertInstanceOf(Operand.Literal.class, move.source());
        assertEquals(new LiteralValue.Figure(LiteralValue.FigurativeConstant.SPACE),
                literal.value());
    }

    @Test
    @DisplayName("段落に分けて書ける (FR-060)")
    void statementsBelongToParagraphs() {
        ProcedureBuilder.Result result = buildOk(SIMPLE,
                "MAIN-START.",
                "    MOVE WS-A TO WS-B.",
                "MAIN-END.",
                "    MOVE WS-B TO WS-A.");

        assertEquals(List.of("MAIN-START", "MAIN-END"),
                result.paragraphs().stream().map(ProcedureBuilder.Paragraph::name).toList());
        assertEquals(1, result.paragraphs().get(0).statements().size());
    }

    @Test
    @DisplayName("段落の前に置かれた文は名前のない段落に入る (FR-060)")
    void statementsBeforeTheFirstParagraphHaveNoName() {
        ProcedureBuilder.Result result = buildOk(SIMPLE,
                "MOVE WS-A TO WS-B.",
                "MAIN-END.",
                "    MOVE WS-B TO WS-A.");

        assertNull(result.paragraphs().get(0).name());
        assertEquals("MAIN-END", result.paragraphs().get(1).name());
    }

    @Test
    @DisplayName("1 つの文に複数の文を並べられる (FR-060)")
    void aSentenceMayHoldSeveralStatements() {
        ProcedureBuilder.Result result = buildOk(SIMPLE,
                "MOVE WS-A TO WS-B MOVE WS-B TO WS-A.");
        assertEquals(2, result.statements().size());
    }

    // ---- 名前の修飾 ----

    @Test
    @DisplayName("同じ名前は OF で絞り込む (FR-026)")
    void anAmbiguousNameIsNarrowedByQualification() {
        List<String> storage = List.of(
                "01 WS-IN.",
                "   05 WS-CODE PIC X(2).",
                "01 WS-OUT.",
                "   05 WS-CODE PIC X(2).");

        Statement.Move move = firstMove(buildOk(storage,
                "MOVE WS-CODE OF WS-IN TO WS-CODE OF WS-OUT."));

        Operand.Reference source = assertInstanceOf(Operand.Reference.class, move.source());
        assertEquals("WS-IN", source.reference().item().parent().name());
        assertEquals("WS-OUT", move.targets().get(0).reference().item().parent().name());
    }

    @Test
    @DisplayName("修飾は途中のレベルを飛ばしてよい (FR-026)")
    void qualificationMaySkipIntermediateLevels() {
        // WS-CODE の直上は WS-BODY だが、その外側の WS-IN で修飾できる
        List<String> storage = List.of(
                "01 WS-IN.",
                "   05 WS-BODY.",
                "      10 WS-CODE PIC X(2).",
                "01 WS-OUT.",
                "   05 WS-CODE PIC X(2).");

        Statement.Move move = firstMove(buildOk(storage,
                "MOVE WS-CODE OF WS-IN TO WS-CODE OF WS-OUT."));
        Operand.Reference source = assertInstanceOf(Operand.Reference.class, move.source());
        assertEquals("WS-BODY", source.reference().item().parent().name());
    }

    @Test
    @DisplayName("絞り込めない名前は誤りとして報告する (FR-026)")
    void anAmbiguousNameIsReported() {
        // どれか 1 個を選ぶと、書いた人の意図と違う項目を黙って使うことになる
        ProcedureBuilder.Result result = build(
                List.of("01 WS-IN.", "   05 WS-CODE PIC X(2).",
                        "01 WS-OUT.", "   05 WS-CODE PIC X(2)."),
                "MOVE WS-CODE TO WS-CODE OF WS-OUT.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("ambiguous"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("定義のない名前は誤りとして報告する (FR-026)")
    void anUndefinedNameIsReported() {
        ProcedureBuilder.Result result = build(SIMPLE, "MOVE WS-NOPE TO WS-B.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("undefined"),
                result.diagnostics().toString());
    }

    // ---- 添字 ----

    private static final List<String> TABLE = List.of(
            "01 WS-REC.",
            "   05 WS-T OCCURS 3 TIMES.",
            "      10 WS-X PIC X(2).",
            "      10 WS-Y PIC X(4).");

    @Test
    @DisplayName("添字は位置を進める (FR-024)")
    void aSubscriptMovesTheOffset() {
        Statement.Move move = firstMove(buildOk(TABLE, "MOVE WS-Y (2) TO WS-X (1)."));

        Operand.Reference source = assertInstanceOf(Operand.Reference.class, move.source());
        // WS-Y は 1 回分の中で 2 バイト目から。2 回目は 6 バイト進む
        assertEquals(OptionalInt.of(8), source.reference().constantOffset());
        assertEquals(OptionalInt.of(0), move.targets().get(0).reference().constantOffset());
    }

    @Test
    @DisplayName("添字の個数が足りなければ誤りとして報告する (FR-024)")
    void aMissingSubscriptIsReported() {
        ProcedureBuilder.Result result = build(TABLE, "MOVE WS-Y TO WS-X (1).");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("requires 1 subscript"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("範囲の外の添字は誤りとして報告する (FR-024)")
    void aSubscriptOutsideItsTableIsReported() {
        ProcedureBuilder.Result result = build(TABLE, "MOVE WS-Y (4) TO WS-X (1).");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("outside 1..3"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("データ項目で添字を書ける (FR-024)")
    void aSubscriptMayBeADataItem() {
        List<String> storage = List.of(
                "01 WS-I PIC 9(3) COMP.",
                "01 WS-REC.",
                "   05 WS-T OCCURS 3 TIMES PIC X(2).");

        Statement.Move move = firstMove(buildOk(storage, "MOVE SPACES TO WS-T (WS-I)."));
        DataReference target = move.targets().get(0).reference();
        assertInstanceOf(DataReference.Subscript.Variable.class, target.subscripts().get(0));
        assertEquals(OptionalInt.empty(), target.constantOffset(), "実行時に決まる");
    }

    // ---- 部分参照 ----

    @Test
    @DisplayName("部分参照は位置と長さを絞る (FR-026)")
    void referenceModificationNarrowsTheOffsetAndLength() {
        Statement.Move move = firstMove(buildOk(
                List.of("01 WS-A PIC X(10).", "01 WS-B PIC X(3)."),
                "MOVE WS-A (4:3) TO WS-B."));

        Operand.Reference source = assertInstanceOf(Operand.Reference.class, move.source());
        assertEquals(OptionalInt.of(3), source.reference().constantOffset());
        assertEquals(OptionalInt.of(3), source.reference().constantLength());
    }

    @Test
    @DisplayName("長さを省いた部分参照は項目の終わりまでを指す (FR-026)")
    void anOmittedLengthReachesTheEndOfTheItem() {
        Statement.Move move = firstMove(buildOk(
                List.of("01 WS-A PIC X(10).", "01 WS-B PIC X(7)."),
                "MOVE WS-A (4:) TO WS-B."));

        Operand.Reference source = assertInstanceOf(Operand.Reference.class, move.source());
        assertEquals(OptionalInt.of(7), source.reference().constantLength());
    }

    @Test
    @DisplayName("項目からはみ出す部分参照は誤りとして報告する (FR-026)")
    void aReferenceModificationPastTheEndIsReported() {
        ProcedureBuilder.Result result = build(
                List.of("01 WS-A PIC X(5).", "01 WS-B PIC X(5)."),
                "MOVE WS-A (4:3) TO WS-B.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("runs past the end"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("添字と部分参照は同時に書ける (FR-024, FR-026)")
    void aSubscriptAndAReferenceModificationCanBeCombined() {
        // 括弧が 2 つ続く。前が添字、後ろがコロンを含む部分参照である
        Statement.Move move = firstMove(buildOk(
                List.of("01 WS-REC.", "   05 WS-T OCCURS 3 TIMES PIC X(4).",
                        "01 WS-B PIC X(2)."),
                "MOVE WS-T (2) (1:2) TO WS-B."));

        Operand.Reference source = assertInstanceOf(Operand.Reference.class, move.source());
        assertEquals(OptionalInt.of(4), source.reference().constantOffset());
        assertEquals(OptionalInt.of(2), source.reference().constantLength());
    }

    @Test
    @DisplayName("表の項目に添字を書かなければ誤りとして報告する (FR-024)")
    void aTableItemAlwaysNeedsItsSubscript() {
        // 部分参照だけを書いても、添字の代わりにはならない
        ProcedureBuilder.Result result = build(
                List.of("01 WS-REC.", "   05 WS-T OCCURS 3 TIMES PIC X(4).",
                        "01 WS-B PIC X(2)."),
                "MOVE WS-T (1:2) TO WS-B.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("requires 1 subscript"),
                result.diagnostics().toString());
    }

    // ---- 分類の組み合わせ ----

    private static final List<String> CATEGORIES = List.of(
            "01 WS-ALPHA  PIC A(3).",
            "01 WS-TEXT   PIC X(3).",
            "01 WS-INT    PIC 9(3).",
            "01 WS-DEC    PIC 9(3)V99.",
            "01 WS-EDIT   PIC ZZ9.99.",
            "01 WS-GROUP.",
            "   05 WS-G1 PIC X(2).");

    private static MoveRules.Kind kindOf(String statement) {
        return firstMove(buildOk(CATEGORIES, statement)).targets().get(0).kind();
    }

    private static String rejectionOf(String statement) {
        ProcedureBuilder.Result result = build(CATEGORIES, statement);
        assertFalse(result.succeeded(), () -> "expected a diagnostic for " + statement);
        return result.diagnostics().get(0).message();
    }

    @Test
    @DisplayName("受取側の分類が転記の種類を決める (FR-060)")
    void theReceivingCategoryDecidesTheKindOfMove() {
        assertEquals(MoveRules.Kind.ALPHANUMERIC, kindOf("MOVE WS-TEXT TO WS-ALPHA."));
        assertEquals(MoveRules.Kind.NUMERIC, kindOf("MOVE WS-DEC TO WS-INT."));
        assertEquals(MoveRules.Kind.NUMERIC_EDITED, kindOf("MOVE WS-DEC TO WS-EDIT."));
    }

    @Test
    @DisplayName("集団項目への転記は無変換の英数字転記になる (FR-020, FR-060)")
    void movingToAGroupIsAlwaysAnAlphanumericMove() {
        assertEquals(MoveRules.Kind.ALPHANUMERIC, kindOf("MOVE WS-DEC TO WS-GROUP."));
        assertEquals(MoveRules.Kind.ALPHANUMERIC, kindOf("MOVE WS-GROUP TO WS-EDIT."));
    }

    @Test
    @DisplayName("部分参照を書いた項目は英数字になる (FR-026, FR-060)")
    void aReferenceModifiedItemIsAlphanumeric() {
        // 数字項目の一部を切り出しても、それは数値ではなくバイトの並びである
        assertEquals(MoveRules.Kind.ALPHANUMERIC, kindOf("MOVE WS-TEXT TO WS-INT (1:2)."));
    }

    @Test
    @DisplayName("英字項目を数値へ移す指定は誤りとする (FR-060)")
    void anAlphabeticItemHasNoNumericValue() {
        assertTrue(rejectionOf("MOVE WS-ALPHA TO WS-INT.").contains("no numeric value"),
                rejectionOf("MOVE WS-ALPHA TO WS-INT."));
    }

    @Test
    @DisplayName("数字編集項目を数値へ戻す指定は誤りとする (FR-060)")
    void aNumericEditedItemCannotBeMovedBackToANumericItem() {
        assertTrue(rejectionOf("MOVE WS-EDIT TO WS-INT.").contains("numeric-edited"),
                rejectionOf("MOVE WS-EDIT TO WS-INT."));
    }

    @Test
    @DisplayName("小数を持つ数値を英数字へ移す指定は誤りとする (FR-060)")
    void aNonIntegerCannotBeMovedToAnAlphanumericItem() {
        // 小数点の位置がバイト列から失われる
        assertTrue(rejectionOf("MOVE WS-DEC TO WS-TEXT.").contains("non-integer"),
                rejectionOf("MOVE WS-DEC TO WS-TEXT."));
    }

    @Test
    @DisplayName("整数と英数字は互いに移せる (FR-060)")
    void integersAndAlphanumericItemsMoveBothWays() {
        assertEquals(MoveRules.Kind.ALPHANUMERIC, kindOf("MOVE WS-INT TO WS-TEXT."));
        assertEquals(MoveRules.Kind.NUMERIC, kindOf("MOVE WS-TEXT TO WS-INT."));
    }

    @Test
    @DisplayName("ZERO は受取側に合わせて数値にも文字にもなる (FR-060)")
    void zeroFollowsItsReceiver() {
        assertEquals(MoveRules.Kind.NUMERIC, kindOf("MOVE ZERO TO WS-INT."));
        assertEquals(MoveRules.Kind.ALPHANUMERIC, kindOf("MOVE ZERO TO WS-TEXT."));
    }

    @Test
    @DisplayName("SPACES を数値へ移す指定は誤りとする (FR-060)")
    void spacesHaveNoNumericValue() {
        assertTrue(rejectionOf("MOVE SPACES TO WS-INT.").contains("not allowed"),
                rejectionOf("MOVE SPACES TO WS-INT."));
    }

    @Test
    @DisplayName("誤りは元のソース上の位置を指す (FR-094, FR-183)")
    void diagnosticsPointAtTheOriginalSource() {
        ProcedureBuilder.Result result = build(SIMPLE,
                "MOVE WS-A TO WS-B.",
                "MOVE WS-NOPE TO WS-B.");
        assertEquals(FILE, result.diagnostics().get(0).origin().fileName());
        assertEquals(9, result.diagnostics().get(0).origin().line());
    }
}
