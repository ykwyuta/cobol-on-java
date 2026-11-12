package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.file.DataSet;
import java.util.function.BooleanSupplier;

/**
 * SYSPRINT 以外の PRINT ファイル (LRM "PRINT attribute", "Using PRINT files")。
 *
 * <p>行は ASA の制御文字 ('1' は改頁、' ' は 1 行送り) を頭に付けたレコードとして書き出す。
 * 頁の中の今の行 (LINENO) と頁の番号 (PAGENO) を数える。行が PAGESIZE を超えようとすると
 * その頁で 1 度だけ ENDPAGE を起こし、on-unit が無ければ改頁する。on-unit が改頁しなければ、
 * 同じ頁に PAGESIZE を超えて書き続ける (LRM "ENDPAGE condition")。
 *
 * <p>開いた直後はまだどの行にもいない。最初の {@code SKIP(1)} は 1 行目に着くだけである
 * (SYSPRINT の {@link PrintFile} と同じ。暫定判断 P-183)。実機と突き合わせていない。
 */
final class UserPrintFile {

    private final DataSet dataSet;
    private final CodePage codePage;
    private final int lineSize;
    private final int pageSize;
    private final int recordLength;
    /** on-unit があれば動かして真を返す。無ければ偽 (既定の処置は改頁)。 */
    private final BooleanSupplier endPage;

    private int page = 1;
    private int line = 1;
    private boolean positioned;
    private boolean endPageRaised;
    private final StringBuilder current = new StringBuilder();
    private char control = '1';

    UserPrintFile(DataSet dataSet, CodePage codePage, int lineSize, int pageSize,
                  int recordLength, BooleanSupplier endPage) {
        this.dataSet = dataSet;
        this.codePage = codePage;
        this.lineSize = lineSize;
        this.pageSize = pageSize;
        this.recordLength = recordLength;
        this.endPage = endPage;
    }

    int lineNumber() {
        return line;
    }

    int pageNumber() {
        return page;
    }

    void skip(int lines) {
        int newlines = positioned ? lines : lines - 1;
        positioned = true;
        for (int i = 0; i < newlines; i++) {
            newline();
        }
    }

    /** {@code LINE(n)}。n が今の行より前なら改頁してから n 行目へ行く。 */
    void line(int target) {
        if (positioned && target < line) {
            page();
        }
        positioned = true;
        while (line < target) {
            newline();
        }
    }

    void page() {
        if (!positioned) {
            // 開いた直後は 1 頁目の頭にいる
            positioned = true;
            return;
        }
        flush();
        page++;
        line = 1;
        positioned = true;
        endPageRaised = false;
        control = '1';
    }

    void text(String text) {
        positioned = true;
        for (int i = 0; i < text.length(); i++) {
            if (current.length() == lineSize) {
                newline();
            }
            current.append(text.charAt(i));
        }
    }

    void close() {
        if (positioned) {
            flush();
        }
    }

    private void newline() {
        flush();
        if (line + 1 > pageSize && !endPageRaised) {
            endPageRaised = true;
            if (!endPage.getAsBoolean()) {
                page();
                return;
            }
        }
        line++;
    }

    /** 今の行を 1 レコードとして書く。何も書いていなければ空の行を書く。 */
    private void flush() {
        byte[] record = new byte[recordLength];
        java.util.Arrays.fill(record, codePage.space());
        byte[] text = codePage.encode(control + current.toString());
        System.arraycopy(text, 0, record, 0, Math.min(text.length, record.length));
        String status = dataSet.write(record);
        if (!status.startsWith("0")) {
            throw new PliRuntime.PliExecutionException("PRINT file write failed with status "
                    + status);
        }
        current.setLength(0);
        control = ' ';
    }
}
