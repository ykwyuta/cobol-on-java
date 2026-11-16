package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.file.DataSet;
import dev.cobolonjava.runtime.file.FileStatus;

import java.util.Arrays;

/**
 * 印刷ファイルのレコードの制御文字 (要件 FR-102)。
 *
 * <p>印刷ファイル ({@code WRITE ... ADVANCING} を書いたファイル) のレコードは、FD の
 * レコードより 1 バイト長く、先頭が紙送りの制御文字である (Programming Guide, ADV:
 * "the compiler adds 1 byte to the record length to account for the printer control
 * character")。AFTER だけを使うファイルは ASA 制御文字、BEFORE を使うファイルは機械制御文字で
 * ある (Language Reference, WRITE statement, ADVANCING phrase)。
 *
 * <ul>
 *   <li>ASA (DFSMS Macro Instructions for Data Sets, ISO/ANSI control characters) は
 *       印字の<b>前に</b>送る: 空白は 1 行、{@code 0} は 2 行、{@code -} は 3 行、{@code +} は
 *       送らない、{@code 1} はチャネル 1 (頁の先頭) へ。</li>
 *   <li>機械制御文字は印字の<b>あとで</b>送る ({@code X'01'} 印字だけ、{@code X'09'} /
 *       {@code X'11'} / {@code X'19'} は 1〜3 行、{@code X'89'} はチャネル 1)、または印字せずに
 *       すぐ送る ({@code X'0B'} / {@code X'13'} / {@code X'1B'} は 1〜3 行、{@code X'8B'} は
 *       チャネル 1)。</li>
 * </ul>
 *
 * <p>z/OS と突き合わせていないもの: 4 行以上の送りと、機械制御文字のファイルでの AFTER。
 * 4 行以上は 3 行の送りを空のレコードで重ね、機械制御文字の AFTER は印字しない送りの
 * レコードを先に置く。印字した頁は同じになるが、レコードは実機と違うかもしれない。
 * 手順は Zeus (同じ権利者の処理系) の {@code vm/files.rs} の {@code carriage_records} を移した。
 * 以前は行送りを空のレコードで表していた (暫定判断 P-063)。
 */
final class Carriage {

    static final int ASA = 1;
    static final int MACHINE = 2;

    private static final byte ASA_ONE = 0x40;
    private static final byte ASA_TWO = (byte) 0xF0;
    private static final byte ASA_THREE = 0x60;
    private static final byte ASA_NONE = 0x4E;
    private static final byte ASA_PAGE = (byte) 0xF1;
    private static final byte[] MACHINE_AFTER = {0x01, 0x09, 0x11, 0x19};
    private static final byte[] MACHINE_NOW = {0, 0x0B, 0x13, 0x1B};
    private static final byte MACHINE_PAGE_AFTER = (byte) 0x89;
    private static final byte MACHINE_PAGE_NOW = (byte) 0x8B;

    private Carriage() {
    }

    /**
     * {@code WRITE ... ADVANCING} の 1 回分を書く。
     *
     * @param lines  送る行数。{@link Ops#PAGE} なら頁の先頭へ、{@code -k} は頁の先頭から k 行目へ
     * @param before 書いてから送るか
     * @param width  FD のレコードの長さ (制御文字を除く)
     */
    static String write(DataSet file, int mode, byte[] data, int lines, boolean before, int width) {
        String status = FileStatus.OK;
        if (mode == ASA) {
            int spacing = lines;
            byte code;
            if (lines < 0) {
                if (lines < Ops.PAGE) {
                    // 頁の先頭の空行を置いてから、その頁の k 行目へ送る
                    status = put(file, ASA_PAGE, blank(file, width));
                    spacing = -lines - 1;
                } else {
                    return put(file, ASA_PAGE, data);
                }
            }
            while (spacing > 3 && status.equals(FileStatus.OK)) {
                status = put(file, ASA_THREE, blank(file, width));
                spacing -= 3;
            }
            code = switch (spacing) {
                case 0 -> ASA_NONE;
                case 1 -> ASA_ONE;
                case 2 -> ASA_TWO;
                default -> ASA_THREE;
            };
            return status.equals(FileStatus.OK) ? put(file, code, data) : status;
        }
        if (!before) {
            status = now(file, lines, width);
            return status.equals(FileStatus.OK) ? put(file, MACHINE_AFTER[0], data) : status;
        }
        if (lines == Ops.PAGE) {
            return put(file, MACHINE_PAGE_AFTER, data);
        }
        int first = Math.min(Math.max(lines, 0), 3);
        status = put(file, MACHINE_AFTER[first], data);
        return status.equals(FileStatus.OK) ? now(file, Math.max(lines, 0) - first, width) : status;
    }

    /**
     * LINAGE の 1 行を書く。行の送りは呼ぶ側が空行で表しているので、どの行も 1 行送って
     * 印字する。頁の区切りの行 (頭が改頁の字) は頁の先頭へ送る。印刷ファイルでなければ
     * そのまま書く。
     */
    static String line(DataSet file, int mode, byte[] record) {
        if (mode == 0) {
            return file.write(record);
        }
        byte formFeed = file.attributes().codePage().encode("\f")[0];
        boolean page = record.length > 0 && record[0] == formFeed;
        byte[] data = record;
        if (page) {
            data = record.clone();
            data[0] = file.attributes().codePage().space();
        }
        if (mode == ASA) {
            return put(file, page ? ASA_PAGE : ASA_ONE, data);
        }
        return put(file, page ? MACHINE_PAGE_NOW : MACHINE_AFTER[1], data);
    }

    /** 機械制御文字で、印字せずにすぐ送る。 */
    private static String now(DataSet file, int lines, int width) {
        if (lines == Ops.PAGE) {
            return put(file, MACHINE_PAGE_NOW, blank(file, width));
        }
        String status = FileStatus.OK;
        int remaining = Math.max(lines, 0);
        while (remaining > 0 && status.equals(FileStatus.OK)) {
            int step = Math.min(remaining, 3);
            status = put(file, MACHINE_NOW[step], blank(file, width));
            remaining -= step;
        }
        return status;
    }

    private static byte[] blank(DataSet file, int width) {
        byte[] blank = new byte[Math.max(width, 0)];
        Arrays.fill(blank, file.attributes().codePage().space());
        return blank;
    }

    private static String put(DataSet file, byte code, byte[] data) {
        byte[] record = new byte[data.length + 1];
        record[0] = code;
        System.arraycopy(data, 0, record, 1, data.length);
        return file.write(record);
    }
}
