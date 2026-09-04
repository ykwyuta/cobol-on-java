package dev.cobolonjava.runtime.file;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * データセットの属性 (要件 FR-110)。
 *
 * <p>データ本体とは<b>別に持つ</b>。ホストではデータセットのラベルにある情報であり、
 * バイト列の中には書かれていない。変換して保存すれば属性は要らなくなるが、
 * それでは L3 互換が原理的に成立しない。
 *
 * <p>サイドカーは {@code 本体のパス + ".meta"} に置く。書式は {@code 名前=値} の並びである。
 *
 * <pre>
 * recfm=F
 * lrecl=80
 * codepage=IBM-1047
 * </pre>
 *
 * @param format       レコード様式
 * @param recordLength レコード長。行順では最大長として使う
 * @param codePage     文字の解釈。区切りの改行もここから引く
 */
public record DataSetAttributes(RecordFormat format, int recordLength, CodePage codePage) {

    /** サイドカーがないときの既定。固定長 80 バイト、IBM-1047。 */
    public static DataSetAttributes standard() {
        return new DataSetAttributes(RecordFormat.FIXED, 80, CodePages.DEFAULT);
    }

    /** レコード長だけを差し替える。 */
    public DataSetAttributes withRecordLength(int value) {
        return new DataSetAttributes(format, value, codePage);
    }

    /** サイドカーのパス。 */
    public static Path sidecarOf(Path data) {
        return data.resolveSibling(data.getFileName() + ".meta");
    }

    /**
     * サイドカーを読む。なければ既定を返す。
     *
     * <p>属性が分からないまま読むと<b>レコードの切れ目が違う</b>。それでも既定で進めるのは、
     * 移行の途中でサイドカーを持たないファイルを扱えるようにするためである。
     */
    public static DataSetAttributes read(Path data) {
        Path sidecar = sidecarOf(data);
        if (!Files.isReadable(sidecar)) {
            return standard();
        }
        DataSetAttributes attributes = standard();
        try {
            for (String line : Files.readAllLines(sidecar, StandardCharsets.UTF_8)) {
                attributes = apply(attributes, line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + sidecar, e);
        }
        return attributes;
    }

    private static DataSetAttributes apply(DataSetAttributes attributes, String line) {
        String text = line.trim();
        int equals = text.indexOf('=');
        if (text.isEmpty() || text.startsWith("#") || equals < 0) {
            return attributes;
        }
        String name = text.substring(0, equals).trim().toLowerCase(Locale.ROOT);
        String value = text.substring(equals + 1).trim();
        return switch (name) {
            case "recfm" -> new DataSetAttributes(RecordFormat.of(value),
                    attributes.recordLength(), attributes.codePage());
            case "lrecl" -> attributes.withRecordLength(Integer.parseInt(value));
            case "codepage" -> new DataSetAttributes(attributes.format(),
                    attributes.recordLength(), CodePages.forName(value));
            default -> attributes;
        };
    }

    /** サイドカーを書く。データセットを作った側が属性を残すために使う。 */
    public void write(Path data) {
        String text = "recfm=" + switch (format) {
            case FIXED -> "F";
            case VARIABLE -> "V";
            case LINE -> "LINE";
        } + System.lineSeparator()
                + "lrecl=" + recordLength + System.lineSeparator()
                + "codepage=" + codePage.name() + System.lineSeparator();
        try {
            Files.writeString(sidecarOf(data), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + sidecarOf(data), e);
        }
    }
}
