package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class TokenizerTest {

    private static final String FILE = "MAIN.cbl";

    private static String source(String... contents) {
        StringBuilder sb = new StringBuilder();
        for (String content : contents) {
            sb.append("       ").append(content).append('\n');
        }
        return sb.toString();
    }

    private static List<SourceToken> tokens(String... contents) {
        return Tokenizer.tokenize(FixedFormatReader.standard().normalize(FILE, source(contents)));
    }

    /** 種別と綴りを {@code KIND(text)} の並びにする。 */
    private static String shape(List<SourceToken> tokens) {
        StringBuilder sb = new StringBuilder();
        for (SourceToken token : tokens) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(token.kind()).append('(').append(token.text()).append(')');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("語と区切り文字に分ける (ARC-8)")
    void wordsAndSeparatorsAreSplit() {
        assertEquals("WORD(MOVE) WORD(A) WORD(TO) WORD(B) SEPARATOR(.)",
                shape(tokens("MOVE A TO B.")));
    }

    @Test
    @DisplayName("括弧はつねに区切り文字である (ARC-8)")
    void parenthesesAreAlwaysSeparators() {
        assertEquals("WORD(IF) SEPARATOR(() WORD(A) SEPARATOR()) SEPARATOR(.)",
                shape(tokens("IF (A).")));
    }

    @Test
    @DisplayName("空白が続かないピリオドは数字定数の小数点である (Q-15)")
    void aPeriodNotFollowedBySpaceIsADecimalPoint() {
        assertEquals("WORD(COMPUTE) WORD(A) WORD(=) WORD(1.5) SEPARATOR(.)",
                shape(tokens("COMPUTE A = 1.5.")));
    }

    @Test
    @DisplayName("空白が続く読点と semicolon も区切り文字になる (Q-15)")
    void commaAndSemicolonSeparateOnlyWhenFollowedByASpace() {
        assertEquals("WORD(CALL) WORD(X) WORD(USING) WORD(A) SEPARATOR(,) WORD(B) SEPARATOR(.)",
                shape(tokens("CALL X USING A, B.")));
    }

    @Test
    @DisplayName("文字定数は引用符を含めて 1 個のトークンになる (ARC-8)")
    void aLiteralIsOneToken() {
        assertEquals("WORD(MOVE) LITERAL('A B.') WORD(TO) WORD(X) SEPARATOR(.)",
                shape(tokens("MOVE 'A B.' TO X.")));
    }

    @Test
    @DisplayName("PICTURE 句の文字列は 1 個の不透明トークンになる (ARC-8, D-14)")
    void aPictureStringIsOneOpaqueToken() {
        assertEquals("WORD(05) WORD(A) WORD(PIC) PICTURE_STRING(S9(5)V99) SEPARATOR(.)",
                shape(tokens("05 A PIC S9(5)V99.")));
    }

    @Test
    @DisplayName("PICTURE の中のピリオドと文末のピリオドを取り違えない (Q-15)")
    void thePeriodInsideAPictureIsNotTheStatementPeriod() {
        // これが「島」を切り出す理由そのものである
        assertEquals("WORD(05) WORD(A) WORD(PIC) PICTURE_STRING(ZZ,ZZ9.99) SEPARATOR(.)",
                shape(tokens("05 A PIC ZZ,ZZ9.99.")));
    }

    @Test
    @DisplayName("PICTURE IS の IS は読み飛ばさず語として残す (ARC-8)")
    void theOptionalIsIsKept() {
        assertEquals("WORD(05) WORD(A) WORD(PICTURE) WORD(IS) PICTURE_STRING(X(3)) SEPARATOR(.)",
                shape(tokens("05 A PICTURE IS X(3).")));
    }

    @Test
    @DisplayName("PICTURE 句のあとに他の句が続いてもよい (ARC-8)")
    void otherClausesMayFollowThePicture() {
        assertEquals("WORD(05) WORD(A) WORD(PIC) PICTURE_STRING(9(3)) WORD(COMP-3) SEPARATOR(.)",
                shape(tokens("05 A PIC 9(3) COMP-3.")));
    }

    @Test
    @DisplayName("文字列のない PICTURE は誤りとして検出する (ARC-8)")
    void aPictureWithoutACharacterStringIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> tokens("05 A PIC."));
        assertTrue(e.getMessage().contains("PICTURE requires"), e.getMessage());
    }

    @Test
    @DisplayName("PICTURE 文字列は文字ごとの出自を保つ (FR-094)")
    void aPictureStringKeepsItsPerCharacterOrigin() {
        SourceToken picture = tokens("05 A PIC S9(5)V99.").get(3);
        assertEquals(SourceTokenKind.PICTURE_STRING, picture.kind());
        // 桁は 1 起点。"       05 A PIC S9(5)V99." の S は 17 桁目
        assertEquals(17, picture.origin().column());
        assertEquals(22, picture.originAt(picture.text().indexOf('V')).column());
    }

    @Test
    @DisplayName("EXEC ブロックは中身ごと 1 個のトークンになる (ARC-8, FR-150)")
    void anExecBlockIsOneToken() {
        List<SourceToken> tokens = tokens(
                "EXEC SQL",
                "  SELECT NAME INTO :WS-NAME FROM T",
                "END-EXEC.");
        assertEquals("EXEC_BLOCK(EXEC SQL SELECT NAME INTO :WS-NAME FROM T END-EXEC) SEPARATOR(.)",
                shape(tokens));
        assertEquals("SQL", tokens.get(0).execProcessor());
    }

    @Test
    @DisplayName("文字定数の中の END-EXEC はブロックを閉じない (Q-15)")
    void anEndExecInsideALiteralDoesNotCloseTheBlock() {
        List<SourceToken> tokens = tokens(
                "EXEC SQL",
                "  SELECT A FROM T WHERE C = 'END-EXEC'",
                "END-EXEC.");
        assertEquals(2, tokens.size());
        assertTrue(tokens.get(0).text().endsWith("'END-EXEC' END-EXEC"), tokens.get(0).text());
    }

    @Test
    @DisplayName("語の一部としての END-EXEC はブロックを閉じない (Q-15)")
    void anEndExecThatIsPartOfAWordDoesNotCloseTheBlock() {
        List<SourceToken> tokens = tokens(
                "EXEC CICS LINK PROGRAM(WS-END-EXEC) END-EXEC.");
        assertEquals("CICS", tokens.get(0).execProcessor());
        assertTrue(tokens.get(0).text().endsWith("PROGRAM(WS-END-EXEC) END-EXEC"),
                tokens.get(0).text());
    }

    @Test
    @DisplayName("閉じられていない EXEC ブロックは誤りとして検出する (ARC-8)")
    void anUnterminatedExecBlockIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> tokens("EXEC SQL SELECT A FROM T"));
        assertTrue(e.getMessage().contains("END-EXEC"), e.getMessage());
    }

    @Test
    @DisplayName("トークン化はプリプロセッサの最後の段である (ARC-8)")
    void tokenizationIsTheLastPreprocessingStage() {
        // コピー句から来た PICTURE 句も、島として切り出される
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 CUST-REC.", "   05 CUST-ID PIC 9(5)."));

        List<SourceToken> tokens = Preprocessor.with(resolver).tokenize(FILE, source("COPY CUSTREC."));

        assertEquals("WORD(01) WORD(CUST-REC) SEPARATOR(.) "
                        + "WORD(05) WORD(CUST-ID) WORD(PIC) PICTURE_STRING(9(5)) SEPARATOR(.)",
                shape(tokens));
        assertEquals("CUSTREC", tokens.get(6).origin().fileName());
    }
}
