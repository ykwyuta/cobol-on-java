package dev.cobolonjava.runtime.file;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * @param emptySlots   相対編成の空きスロットの番号 (1 起点)。ほかの編成では空
 */
public record DataSetAttributes(RecordFormat format, int recordLength, CodePage codePage,
                                List<Integer> emptySlots) {

    public DataSetAttributes {
        emptySlots = List.copyOf(emptySlots);
    }

    /** 空きスロットを持たない構成。順編成と行順編成はこちらである。 */
    public DataSetAttributes(RecordFormat format, int recordLength, CodePage codePage) {
        this(format, recordLength, codePage, List.of());
    }

    /** サイドカーがないときの既定。固定長 80 バイト、IBM-1047。 */
    public static DataSetAttributes standard() {
        return new DataSetAttributes(RecordFormat.FIXED, 80, CodePages.DEFAULT);
    }

    /** レコード長だけを差し替える。 */
    public DataSetAttributes withRecordLength(int value) {
        return new DataSetAttributes(format, value, codePage, emptySlots);
    }

    /**
     * 空きスロットを差し替える (要件 FR-100)。
     *
     * <p>相対編成では<b>どのスロットが使われているか</b>がバイト列から分からない。
     * 消したスロットと空白だけのレコードは同じバイトになる。VSAM は制御情報として持っており、
     * レコードのバイト列の外にある。したがってサイドカーに持つ (暫定判断 P-039)。
     */
    public DataSetAttributes withEmptySlots(List<Integer> values) {
        return new DataSetAttributes(format, recordLength, codePage, values);
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
        return read(data, standard());
    }

    /**
     * サイドカーを読む。なければ与えられた既定を返す。
     *
     * <p>既定に使うのは<b>プログラムが宣言した様式</b>である。{@code OPEN OUTPUT} で新しく
     * 作るファイルにはサイドカーがなく、そのときに拠れるのは宣言だけである。逆にサイドカーが
     * あればそちらが勝つ。そこに置かれているバイト列を実際に切り分けたのはその属性であり、
     * 宣言と食い違っていてもバイト列の事実は変わらないためである。
     */
    public static DataSetAttributes read(Path data, DataSetAttributes fallback) {
        Path sidecar = sidecarOf(data);
        if (!Files.isReadable(sidecar)) {
            return fallback;
        }
        DataSetAttributes attributes = fallback;
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
                    attributes.recordLength(), attributes.codePage(), attributes.emptySlots());
            case "lrecl" -> attributes.withRecordLength(Integer.parseInt(value));
            case "codepage" -> new DataSetAttributes(attributes.format(),
                    attributes.recordLength(), CodePages.forName(value), attributes.emptySlots());
            case "empty" -> attributes.withEmptySlots(slotsOf(value));
            default -> attributes;
        };
    }

    /** {@code empty=2,5} の並びを読む。空きが 1 つもなければ行そのものを書かない。 */
    private static List<Integer> slotsOf(String value) {
        List<Integer> slots = new ArrayList<>();
        for (String part : value.split(",")) {
            String text = part.trim();
            if (!text.isEmpty()) {
                slots.add(Integer.valueOf(text));
            }
        }
        return slots;
    }

    private static String join(List<Integer> slots) {
        StringBuilder sb = new StringBuilder();
        for (int slot : slots) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(slot);
        }
        return sb.toString();
    }

    /** サイドカーを書く。データセットを作った側が属性を残すために使う。 */
    public void write(Path data) {
        String text = "recfm=" + switch (format) {
            case FIXED -> "F";
            case VARIABLE -> "V";
            case LINE -> "LINE";
        } + System.lineSeparator()
                + "lrecl=" + recordLength + System.lineSeparator()
                + "codepage=" + codePage.name() + System.lineSeparator()
                + (emptySlots.isEmpty() ? "" : "empty=" + join(emptySlots) + System.lineSeparator());
        try {
            Files.writeString(sidecarOf(data), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + sidecarOf(data), e);
        }
    }
}
