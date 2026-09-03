package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class DirectiveProcessorTest {

    private static final String FILE = "MAIN.cbl";

    private static String source(String... contents) {
        StringBuilder sb = new StringBuilder();
        for (String content : contents) {
            sb.append("       ").append(content).append('\n');
        }
        return sb.toString();
    }

    private static String process(String... contents) {
        return process(Map.of(), contents);
    }

    private static String process(Map<String, String> parameters, String... contents) {
        return DirectiveProcessor.apply(
                FixedFormatReader.standard().normalize(FILE, source(contents)), parameters).text();
    }

    @Test
    @DisplayName("真の >>IF は本体を残し、指示文そのものは消える (FR-092)")
    void aTrueConditionKeepsItsBody() {
        assertEquals("MOVE A TO B.", process(
                ">>DEFINE DEBUGGING AS 1",
                ">>IF DEBUGGING = 1",
                "MOVE A TO B.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("偽の >>IF は本体を捨てる (FR-092)")
    void aFalseConditionDropsItsBody() {
        assertEquals("MOVE C TO D.", process(
                ">>DEFINE DEBUGGING AS 0",
                ">>IF DEBUGGING = 1",
                "MOVE A TO B.",
                ">>END-IF",
                "MOVE C TO D."));
    }

    @Test
    @DisplayName(">>ELSE は反対の枝を残す (FR-092)")
    void elseKeepsTheOppositeBranch() {
        assertEquals("MOVE C TO D.", process(
                ">>DEFINE X AS 0",
                ">>IF X = 1",
                "MOVE A TO B.",
                ">>ELSE",
                "MOVE C TO D.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("DEFINED で定義の有無を問える (FR-092)")
    void definedAsksWhetherAConstantExists() {
        assertEquals("YES.", process(
                ">>DEFINE X AS 1",
                ">>IF X DEFINED",
                "YES.",
                ">>END-IF",
                ">>IF Y DEFINED",
                "NO.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("NOT は条件を反転する (FR-092)")
    void notInvertsTheCondition() {
        assertEquals("YES.", process(
                ">>IF Y IS NOT DEFINED",
                "YES.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("数字定数は数として比較する (FR-092)")
    void numericConstantsAreComparedAsNumbers() {
        // 文字列として比較すると "10" < "9" になってしまう
        assertEquals("YES.", process(
                ">>DEFINE LEVEL AS 10",
                ">>IF LEVEL > 9",
                "YES.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("文字定数は引用符を外して比較する (FR-092)")
    void alphanumericConstantsAreComparedWithoutTheirQuotes() {
        assertEquals("YES.", process(
                ">>DEFINE TARGET AS 'ZOS'",
                ">>IF TARGET = 'ZOS'",
                "YES.",
                ">>END-IF"));
    }

    @Test
    @DisplayName(">>IF は入れ子にできる (FR-092)")
    void conditionsNest() {
        assertEquals("INNER.", process(
                ">>DEFINE A AS 1",
                ">>DEFINE B AS 1",
                ">>IF A = 1",
                ">>IF B = 1",
                "INNER.",
                ">>END-IF",
                ">>END-IF"));
    }

    @Test
    @DisplayName("死んでいる枝の中の未定義の定数は誤りにしない (FR-092)")
    void undefinedConstantsInsideADeadBranchAreNotEvaluated() {
        // 偽の枝の中身は「翻訳されない」のだから、そこの条件も評価してはならない
        assertEquals("", process(
                ">>IF UNDEFINED-ONE DEFINED",
                ">>IF UNDEFINED-TWO = 1",
                "NO.",
                ">>END-IF",
                ">>END-IF"));
    }

    @Test
    @DisplayName("死んでいる枝の中の >>DEFINE は効かない (FR-092)")
    void aDefineInsideADeadBranchHasNoEffect() {
        assertEquals("NO-X.", process(
                ">>IF X DEFINED",
                ">>DEFINE X AS 1",
                ">>END-IF",
                ">>IF X DEFINED",
                "HAS-X.",
                ">>ELSE",
                "NO-X.",
                ">>END-IF"));
    }

    @Test
    @DisplayName(">>EVALUATE は対象と一致する枝だけを残す (FR-092)")
    void evaluateKeepsTheMatchingBranch() {
        assertEquals("MVS.", process(
                ">>DEFINE PLATFORM AS 'MVS'",
                ">>EVALUATE PLATFORM",
                ">>WHEN 'VSE'",
                "VSE.",
                ">>WHEN 'MVS'",
                "MVS.",
                ">>WHEN OTHER",
                "OTHER.",
                ">>END-EVALUATE"));
    }

    @Test
    @DisplayName("どの枝にも当たらなければ >>WHEN OTHER が残る (FR-092)")
    void whenOtherCatchesTheRest() {
        assertEquals("OTHER.", process(
                ">>DEFINE PLATFORM AS 'AIX'",
                ">>EVALUATE PLATFORM",
                ">>WHEN 'MVS'",
                "MVS.",
                ">>WHEN OTHER",
                "OTHER.",
                ">>END-EVALUATE"));
    }

    @Test
    @DisplayName(">>EVALUATE TRUE では >>WHEN が条件を書く (FR-092)")
    void evaluateTrueTakesConditionsInItsWhen() {
        assertEquals("BIG.", process(
                ">>DEFINE LEVEL AS 7",
                ">>EVALUATE TRUE",
                ">>WHEN LEVEL < 5",
                "SMALL.",
                ">>WHEN LEVEL > 5",
                "BIG.",
                ">>END-EVALUATE"));
    }

    @Test
    @DisplayName(">>DEFINE AS OFF は定義を取り消す (FR-092)")
    void defineAsOffRemovesTheConstant() {
        assertEquals("GONE.", process(
                ">>DEFINE X AS 1",
                ">>DEFINE X AS OFF",
                ">>IF X DEFINED",
                "STILL-HERE.",
                ">>ELSE",
                "GONE.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("OVERRIDE のない再定義は誤りとして検出する (FR-092)")
    void redefiningWithoutOverrideIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class, () -> process(
                ">>DEFINE X AS 1",
                ">>DEFINE X AS 2"));
        assertTrue(e.getMessage().contains("OVERRIDE"), e.getMessage());
    }

    @Test
    @DisplayName("OVERRIDE を付ければ再定義できる (FR-092)")
    void overrideAllowsRedefinition() {
        assertEquals("TWO.", process(
                ">>DEFINE X AS 1",
                ">>DEFINE X AS 2 OVERRIDE",
                ">>IF X = 2",
                "TWO.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("AS PARAMETER の値は翻訳時オプションから来る (FR-092)")
    void asParameterTakesItsValueFromTheCompilerOption() {
        assertEquals("PROD.", process(Map.of("ENVIRONMENT", "'PROD'"),
                ">>DEFINE ENVIRONMENT AS PARAMETER",
                ">>IF ENVIRONMENT = 'PROD'",
                "PROD.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("供給されない AS PARAMETER は未定義のままになる (FR-092)")
    void anUnsuppliedParameterStaysUndefined() {
        assertEquals("NONE.", process(
                ">>DEFINE ENVIRONMENT AS PARAMETER",
                ">>IF ENVIRONMENT DEFINED",
                "SOME.",
                ">>ELSE",
                "NONE.",
                ">>END-IF"));
    }

    @Test
    @DisplayName(">>PUSH と >>POP は定義の状態を退避し復元する (FR-092)")
    void pushAndPopSaveAndRestoreTheConstants() {
        assertEquals("ONE.", process(
                ">>DEFINE X AS 1",
                ">>PUSH ALL",
                ">>DEFINE X AS 2 OVERRIDE",
                ">>POP ALL",
                ">>IF X = 1",
                "ONE.",
                ">>END-IF"));
    }

    @Test
    @DisplayName("未定義の定数との比較は誤りとして検出する (FR-092)")
    void comparingAnUndefinedConstantIsRejected() {
        // 黙って偽にすると、定数名の打ち間違いが条件の書き間違いとして通ってしまう
        SourceFormatException e = assertThrows(SourceFormatException.class, () -> process(
                ">>IF DEBUGING = 1",
                "MOVE A TO B.",
                ">>END-IF"));
        assertTrue(e.getMessage().contains("DEBUGING"), e.getMessage());
    }

    @Test
    @DisplayName("閉じられていない >>IF は誤りとして検出する (FR-092)")
    void anUnterminatedConditionIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class, () -> process(
                ">>IF X DEFINED",
                "MOVE A TO B."));
        assertTrue(e.getMessage().contains("not terminated"), e.getMessage());
    }

    @Test
    @DisplayName("対応しない >>END-IF は誤りとして検出する (FR-092)")
    void aStrayEndIfIsRejected() {
        assertThrows(SourceFormatException.class, () -> process(">>END-IF"));
    }

    @Test
    @DisplayName("知らない指示文は誤りとして検出する (FR-092)")
    void anUnknownDirectiveIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> process(">>NOSUCHTHING"));
        assertTrue(e.getMessage().contains("unknown compiler directive"), e.getMessage());
    }

    @Test
    @DisplayName("指示文は行で終わる (FR-092)")
    void aDirectiveEndsWithItsLine() {
        // 終止符はない。次の行の語まで条件に取り込んではならない
        assertEquals("MOVE A TO B.", process(
                ">>IF X IS NOT DEFINED",
                "MOVE A TO B.",
                ">>END-IF"));
    }

    @Test
    @DisplayName(">>CALLINTERFACE は受理して出力から消える (FR-092, P-021)")
    void callInterfaceIsAcceptedAndRemoved() {
        // 呼び出し規約の指定は CALL の生成まで効かない。いまは受理するだけである
        assertEquals("CALL 'SUB'.", process(
                ">>CALLINTERFACE COBOL",
                "CALL 'SUB'."));
    }

    @Test
    @DisplayName("条件の AND / OR は受け付けず、誤りとして報告する (P-021)")
    void combinedConditionsAreRejectedLoudly() {
        // 黙って一方だけ見るより、書けないことを言うほうがよい
        SourceFormatException e = assertThrows(SourceFormatException.class, () -> process(
                ">>DEFINE A AS 1",
                ">>DEFINE B AS 1",
                ">>IF A = 1 AND B = 1",
                "BOTH.",
                ">>END-IF"));
        assertTrue(e.getMessage().contains("unexpected text"), e.getMessage());
    }

    @Test
    @DisplayName("コピー句の中の >>IF に主ソースの >>DEFINE が届く (FR-090, FR-092)")
    void aDefineInTheMainSourceReachesTheCopybook() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source(
                        ">>IF WITH-AUDIT DEFINED",
                        "05 AUDIT-ID PIC 9(5).",
                        ">>END-IF",
                        "05 CUST-ID PIC 9(5)."));

        assertEquals("05 AUDIT-ID PIC 9(5). 05 CUST-ID PIC 9(5).",
                Preprocessor.with(resolver).process(FILE, source(
                        ">>DEFINE WITH-AUDIT AS 1",
                        "COPY REC.")).text());
    }
}
