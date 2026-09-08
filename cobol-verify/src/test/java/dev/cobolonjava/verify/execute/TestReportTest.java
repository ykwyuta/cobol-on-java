package dev.cobolonjava.verify.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 検査プログラムが印字した報告の読み取り (暫定判断 P-062)。
 *
 * <p>道具そのものにも検査を仕込む。読み違えると<b>処理系の成績が信用できなくなる</b>。
 */
@Tag("V1")
class TestReportTest {

    private static final String CLEAN = """
                                                END OF TEST-  NC101A

                                                093 OF 093  TESTS WERE EXECUTED SUCCESSFULLY
                                                NO  TEST(S) FAILED
                                                NO  TEST(S) DELETED
                                                NO  TEST(S) REQUIRE INSPECTION
            """;

    private static final String MIXED = """
                                                END OF TEST-  NC104A

                                                040 OF 052  TESTS WERE EXECUTED SUCCESSFULLY
                                                007 TEST(S) FAILED
                                                003 TEST(S) DELETED
                                                002 TEST(S) REQUIRE INSPECTION
            """;

    @Test
    @DisplayName("成績の 4 行を読む (P-062)")
    void theFourSummaryLinesAreRead() {
        assertEquals(93, TestReport.executed(CLEAN).orElseThrow());
        assertEquals(93, TestReport.total(CLEAN).orElseThrow());
        assertEquals(0, TestReport.failed(CLEAN));
        assertEquals(0, TestReport.deleted(CLEAN));
        assertEquals(0, TestReport.inspected(CLEAN));
    }

    @Test
    @DisplayName("NO は 0 である。数が書かれていればその数である (P-062)")
    void writtenCountsAreRead() {
        assertEquals(40, TestReport.executed(MIXED).orElseThrow());
        assertEquals(52, TestReport.total(MIXED).orElseThrow());
        assertEquals(7, TestReport.failed(MIXED));
        assertEquals(3, TestReport.deleted(MIXED));
        assertEquals(2, TestReport.inspected(MIXED));
    }

    @Test
    @DisplayName("END OF TEST- が無ければ、最後まで行っていない (P-062)")
    void anUnfinishedReportIsNotComplete() {
        assertTrue(TestReport.isComplete(CLEAN));
        // 途中で止まったものを「落ちた検査が 0 だから合格」と数えてはならない
        assertFalse(TestReport.isComplete(" MULTIPLY BY          PASS  MPY-TEST-F1-1"));
    }

    /** 印字 1 行ぶん。報告のデータセットは<b>改行を持たない</b>ので、桁で並べる。 */
    private static String printed(String feature, String result, String paragraph,
                                  String remark) {
        return " " + pad(feature, 20) + " " + pad(result, 5) + " " + pad(paragraph, 20)
                + " ".repeat(10) + pad(remark, 61);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text.substring(0, width)
                : text + " ".repeat(width - text.length());
    }

    @Test
    @DisplayName("落ちた検査を 1 件ずつ取り出す (P-062)")
    void failingChecksAreListed() {
        // 報告のデータセットは決まった長さのレコードの並びであり、改行を持たない。
        // 行頭に錨を打つと 1 件も見つからない
        String report = printed("CREATE-FILE-FD1", "FAIL*", "WRITE-TEST-GF-01", "IX-41;WRONG")
                + printed("MULTIPLY BY", "PASS", "MPY-TEST-F1-1", "")
                + printed("START REDF REC-KEY", "FAIL*", "START-TEST-GF-01", "WRONG RECORD");
        assertEquals(2, TestReport.failures(report).size());
        assertEquals("CREATE-FILE-FD1", TestReport.failures(report).get(0).feature());
        assertEquals("WRITE-TEST-GF-01", TestReport.failures(report).get(0).paragraph());
        assertEquals("IX-41;WRONG", TestReport.failures(report).get(0).remark());
        assertEquals("START REDF REC-KEY", TestReport.failures(report).get(1).feature());
    }

    @Test
    @DisplayName("機能名の中の空白で切らない (P-062)")
    void aFeatureNameMayContainSpaces() {
        // 空白で切ると「START REDF REC-KEY」が 3 件に散る。桁で切る
        String report = printed("START REDF REC-KEY", "FAIL*", "START-TEST-GF-01", "WRONG");
        assertEquals("START REDF REC-KEY", TestReport.failures(report).get(0).feature());
    }

    @Test
    @DisplayName("見出しの PASS を数えない (P-062)")
    void theColumnHeadingIsNotCounted() {
        // 1 行ずつ数えると「FEATURE PASS PARAGRAPH-NAME」まで数えてしまう。
        // だからプログラム自身が書いた成績の行だけを読む
        String heading = " FEATURE              PASS  PARAGRAPH-NAME       REMARKS\n";
        assertFalse(TestReport.isComplete(heading));
        assertEquals(0, TestReport.failed(heading));
    }
}
