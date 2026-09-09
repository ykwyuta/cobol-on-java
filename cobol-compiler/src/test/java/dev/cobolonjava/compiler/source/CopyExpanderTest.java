package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CopyExpanderTest {

    private static final String FILE = "MAIN.cbl";

    /** 一連番号領域を空白にし、標識と本文を置いた 1 行を作る。 */
    private static String line(String content) {
        return "       " + content;
    }

    private static String source(String... contents) {
        StringBuilder sb = new StringBuilder();
        for (String content : contents) {
            sb.append(line(content)).append('\n');
        }
        return sb.toString();
    }

    private static NormalizedSource expand(MapCopyBookResolver resolver, String... contents) {
        NormalizedSource normalized =
                FixedFormatReader.standard().normalize(FILE, source(contents));
        return new CopyExpander(resolver).expand(normalized);
    }

    @Test
    @DisplayName("COPY はコピー句の内容で置き換えられる (FR-090)")
    void copyIsReplacedByTheCopybook() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 CUST-REC.", "   05 CUST-ID PIC 9(5)."));

        assertEquals("MOVE A TO B. 01 CUST-REC. 05 CUST-ID PIC 9(5). MOVE C TO D.",
                expand(resolver, "MOVE A TO B.", "COPY CUSTREC.", "MOVE C TO D.").text());
    }

    @Test
    @DisplayName("PICTURE の括弧の前後に空白が入らない (FR-090)")
    void parenthesesKeepTheirSpacing() {
        // 語の間の空白の有無を保たないと PIC 9(5) が PIC 9 ( 5 ) になってしまう
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("05 F PIC S9(7)V99 COMP-3."));
        assertEquals("05 F PIC S9(7)V99 COMP-3.", expand(resolver, "COPY REC.").text());
    }

    @Test
    @DisplayName("OF / IN でライブラリを指定できる (FR-090)")
    void libraryQualification() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("PROD", "REC", source("01 PROD-REC."))
                .put("TEST", "REC", source("01 TEST-REC."));

        assertEquals("01 PROD-REC.", expand(resolver, "COPY REC OF PROD.").text());
        assertEquals("01 TEST-REC.", expand(resolver, "COPY REC IN TEST.").text());
    }

    @Test
    @DisplayName("REPLACING は語単位で置き換える (FR-090)")
    void replacingOperatesOnWords() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("01 PREFIX-REC.", "   05 PREFIX-ID PIC 9(5)."));

        // PREFIX-REC と PREFIX-ID は別の語である。片方だけを指定してももう片方は残る
        assertEquals("01 CUST-REC. 05 PREFIX-ID PIC 9(5).",
                expand(resolver, "COPY REC REPLACING PREFIX-REC BY CUST-REC.").text());
    }

    @Test
    @DisplayName("置換の相手は修飾できる (FR-090)")
    void aReplacementOperandMayBeQualified() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("MOVE FALSE-DATA TO AREA-1."));

        // 一意名は 1 語とは限らない。1 語しか読まないと続く OF を次の相手と読んでしまい、
        // 「BY が無い」と断ってしまう (SM202A)
        assertEquals("MOVE TRUE-Q-04 OF TRUE-Q-03 IN TRUE-Q-02 TO AREA-1.",
                expand(resolver,
                        "COPY REC REPLACING FALSE-DATA BY TRUE-Q-04 OF TRUE-Q-03",
                        "   IN TRUE-Q-02.").text());
    }

    @Test
    @DisplayName("置換の相手に添字を書ける (FR-090)")
    void aReplacementOperandMayBeSubscripted() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("MOVE FALSE-DATA TO AREA-3."));

        assertEquals("MOVE Z (2, 1, 1) TO AREA-3.",
                expand(resolver, "COPY REC REPLACING FALSE-DATA BY Z (2, 1, 1).").text());
    }

    @Test
    @DisplayName("数の途中の小数点は区切りではない (FR-090)")
    void aDecimalPointInsideANumberIsNotASeparator() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("MOVE FALSE-DATA TO AREA-4. GO TO NEXT-PARA."));

        // 「+000004.99.」の最初の点は数の一部である。切ってしまうと残った「.99」が
        // 次の文へ紛れ込む (SM202A がそれで壊れていた)
        assertEquals("MOVE +000004.99 TO AREA-4. GO TO NEXT-PARA.",
                expand(resolver, "COPY REC REPLACING FALSE-DATA BY +000004.99.").text());
    }

    @Test
    @DisplayName("擬似テキストは語の並びを指定する (FR-090)")
    void pseudoTextMatchesASequenceOfWords() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("01 WS-REC.", "   05 WS-ID PIC 9(5)."));

        // == と == の間に書いた語の並びが照合の単位になる
        assertEquals("01 CUST-REC. 05 CUST-ID PIC 9(5).",
                expand(resolver,
                        "COPY REC REPLACING ==WS-REC== BY ==CUST-REC==",
                        "   ==WS-ID== BY ==CUST-ID==.").text());
    }

    @Test
    @DisplayName("擬似テキストは複数の語にわたって置き換えられる (FR-090)")
    void pseudoTextCanSpanSeveralWords() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("05 F PIC X(10) VALUE SPACE."));

        assertEquals("05 F PIC X(10) VALUE ZERO.",
                expand(resolver, "COPY REC REPLACING ==VALUE SPACE== BY ==VALUE ZERO==.").text());
    }

    @Test
    @DisplayName("置換の照合は大文字と小文字を区別しない (FR-090)")
    void replacingIsCaseInsensitiveForWords() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("01 ws-rec."));

        assertEquals("01 CUST-REC.",
                expand(resolver, "COPY REC REPLACING ==WS-REC== BY ==CUST-REC==.").text());
    }

    @Test
    @DisplayName("置換は書かれた順に試され、最初に一致したものが使われる (FR-090)")
    void replacementsAreTriedInOrder() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("01 A B."));

        // ==A B== のほうが先に書かれているので、こちらが優先される
        assertEquals("01 X.",
                expand(resolver, "COPY REC REPLACING ==A B== BY ==X== ==A== BY ==Y==.").text());
        // 順序を入れ替えると結果が変わる
        assertEquals("01 Y B.",
                expand(resolver, "COPY REC REPLACING ==A== BY ==Y== ==A B== BY ==X==.").text());
    }

    @Test
    @DisplayName("コピー句の中の COPY も展開される (FR-090)")
    void nestedCopyIsExpanded() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("OUTER", source("01 OUTER-REC.", "COPY INNER."))
                .put("INNER", source("05 INNER-ID PIC 9(3)."));

        assertEquals("01 OUTER-REC. 05 INNER-ID PIC 9(3).",
                expand(resolver, "COPY OUTER.").text());
    }

    @Test
    @DisplayName("循環する COPY は誤りとして検出する (FR-090)")
    void recursiveCopyIsDetected() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("A", source("COPY B."))
                .put("B", source("COPY A."));

        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> expand(resolver, "COPY A."));
        assertTrue(e.getMessage().contains("recursive"), e.getMessage());
    }

    @Test
    @DisplayName("見つからないコピー句は誤りとして検出する (FR-090)")
    void missingCopybookIsReported() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> expand(new MapCopyBookResolver(), "COPY NOSUCH."));
        assertTrue(e.getMessage().contains("NOSUCH"), e.getMessage());
        assertTrue(e.getMessage().contains("MAIN.cbl:1"), e.getMessage());
    }

    @Test
    @DisplayName("終止符のない COPY は誤りとして検出する (FR-090)")
    void copyMustBeTerminatedByAPeriod() {
        MapCopyBookResolver resolver = new MapCopyBookResolver().put("REC", source("01 R."));
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> expand(resolver, "COPY REC"));
        assertTrue(e.getMessage().contains("period"), e.getMessage());
    }

    @Test
    @DisplayName("閉じていない擬似テキストは誤りとして検出する (FR-090)")
    void unterminatedPseudoTextIsReported() {
        MapCopyBookResolver resolver = new MapCopyBookResolver().put("REC", source("01 R."));
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> expand(resolver, "COPY REC REPLACING ==A== BY ==B."));
        assertTrue(e.getMessage().contains("pseudo-text"), e.getMessage());
    }

    @Test
    @DisplayName("展開後の語はコピー句のファイルと行を指す (FR-094)")
    void expandedWordsPointAtTheCopybook() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 CUST-REC.", "   05 CUST-ID PIC 9(5)."));

        NormalizedSource result = expand(resolver, "MOVE A TO B.", "COPY CUSTREC.");
        int index = result.text().indexOf("CUST-ID");
        Origin origin = result.originOf(index);

        assertEquals("CUSTREC", origin.fileName(), "コピー句のファイル名を指す");
        assertEquals(2, origin.line(), "コピー句の 2 行目を指す");
    }

    @Test
    @DisplayName("置換で差し込まれた語は COPY を書いた側のファイルと行を指す (FR-094)")
    void replacementWordsPointAtTheCopyStatement() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("01 WS-REC."));

        NormalizedSource result =
                expand(resolver, "COPY REC REPLACING ==WS-REC== BY ==CUST-REC==.");
        int index = result.text().indexOf("CUST-REC");
        Origin origin = result.originOf(index);

        // 差し込まれた語はコピー句ではなく COPY 文に書かれている。
        // 同じ展開結果の中に 2 つのファイルの位置が混在するが、それが実際の出自である
        assertEquals(FILE, origin.fileName());
        assertEquals(1, origin.line());
    }
    @Test
    @DisplayName("SUPPRESS は展開結果を変えない (FR-090)")
    void suppressDoesNotChangeTheExpansion() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 CUST-REC."));
        assertEquals("01 CUST-REC.", expand(resolver, "COPY CUSTREC SUPPRESS.").text());
        assertEquals("01 CUST-REC.", expand(resolver, "COPY CUSTREC SUPPRESS PRINTING.").text());
    }

    @Test
    @DisplayName("SUPPRESS は REPLACING と併せて書ける (FR-090)")
    void suppressMayPrecedeReplacing() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 OLD-REC."));
        assertEquals("01 NEW-REC.", expand(resolver,
                "COPY CUSTREC SUPPRESS REPLACING ==OLD-REC== BY ==NEW-REC==.").text());
    }

    /** 7 桁目に {@code D} を置いたデバッグ行。 */
    private static String debugLine(String content) {
        return "      D" + content;
    }

    @Test
    @DisplayName("原本のデバッグ行の語も置換の照合に加わる (FR-090, FR-193)")
    void wordsOnADebugLineTakePartInTheMatching() {
        // 85 規格 XII 2.4 は「7 桁目の D が無いものとして照合に参加する」と決めている。
        // 注釈行 (PST-TEST-007) とは扱いが違う (SM206A PST-TEST-009)
        MapCopyBookResolver resolver = new MapCopyBookResolver().put("KP008",
                line("PERFORM FAIL.") + "\n"
                + debugLine("    THIS IS GARBAGE.") + "\n"
                + line("SUBTRACT 1 FROM ERROR-COUNTER.") + "\n");

        assertEquals("PERFORM PASS.", expand(resolver,
                "COPY KP008 REPLACING",
                "==FAIL. THIS IS GARBAGE. SUBTRACT 1 FROM ERROR-COUNTER. ==",
                "BY ==PASS. ==.").text());
    }

    @Test
    @DisplayName("置換で消えなかったデバッグ行は落とす (FR-090, FR-193)")
    void aDebugLineThatSurvivesTheReplacementIsDropped() {
        // 照合のあいだだけ生かしておく。WITH DEBUGGING MODE が書かれていなければ
        // デバッグ行は注釈と同じであり、ふつうの文としてプログラムへ入ってはならない
        MapCopyBookResolver resolver = new MapCopyBookResolver().put("KP008",
                line("PERFORM FAIL.") + "\n"
                + debugLine("    THIS IS GARBAGE.") + "\n"
                + line("SUBTRACT 1 FROM ERROR-COUNTER.") + "\n");

        assertEquals("PERFORM FAIL. SUBTRACT 1 FROM ERROR-COUNTER.",
                expand(resolver, "COPY KP008.").text());
    }

    @Test
    @DisplayName("注釈行の語は照合に加わらない (FR-090)")
    void wordsOnACommentLineDoNotTakePartInTheMatching() {
        // KP007 がこれを試している (SM206A PST-TEST-007)
        MapCopyBookResolver resolver = new MapCopyBookResolver().put("KP007",
                line("PERFORM FAIL.") + "\n"
                + "      *    THIS COMMENT SHOULD NOT AFFECT MATCHING." + "\n"
                + line("SUBTRACT 1 FROM ERROR-COUNTER.") + "\n");

        assertEquals("PERFORM PASS.", expand(resolver,
                "COPY KP007 REPLACING",
                "==FAIL. SUBTRACT 1 FROM ERROR-COUNTER. ==",
                "BY ==PASS. ==.").text());
    }

    @Test
    @DisplayName("SUPPRESS はリストから落とすコピー句を示す (FR-090, FR-094)")
    void suppressNamesTheCopybooksToLeaveOutOfTheListing() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("OUTER", source("01 OUTER-REC.", "COPY INNER."))
                .put("INNER", source("05 INNER-ID PIC 9(5)."));

        CopyExpander expander = new CopyExpander(resolver);
        expander.expand(FixedFormatReader.standard()
                .normalize(FILE, source("COPY OUTER SUPPRESS.")));

        // 入れ子のコピー句も一緒に抑止される
        assertEquals(java.util.Set.of("OUTER", "INNER"), expander.suppressedFiles());
    }
}