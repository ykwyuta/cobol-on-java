package dev.cobolonjava.runtime.file;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * バイト列をレコードへ切る (要件 FR-110, FR-141)。
 *
 * <p>どこでレコードが切れるかは {@link DataSetAttributes} が決める。バイト列の中には
 * 書かれていない。だから<b>切れないことがある</b> — 固定長なのに長さで割り切れない、
 * 可変長なのに {@code RDW} がつながらない、という形である。
 *
 * <h2>切り方と「壊れている」は同じところで決まる</h2>
 * <p>切り方を編成ごとに書くと、壊れているの意味も編成ごとに分かれる。実際そうなっていて、
 * 順編成だけが半端に気付き、相対編成・索引編成・ジョブのユーティリティは黙って短いレコードを
 * 渡していた (暫定判断 P-053)。<b>切る場所を 1 つにすれば、気付く場所も 1 つになる</b>。
 *
 * <h2>壊れた場所は返すだけである</h2>
 * <p>そこで何をするかは編成が決める。順編成と相対編成は<b>そこまでは読める</b> — 位置と
 * バイト列の並びが対応しているからである。索引編成は対応していないので、そうはいかない。
 */
public final class RecordFraming {

    private RecordFraming() {
    }

    /**
     * 切り出した結果。
     *
     * @param records   切れたところまでのレコード
     * @param damagedAt それ以上切れなくなったレコード番号 (0 起点)。{@code -1} は壊れていない
     */
    public record Framed(List<byte[]> records, int damagedAt) {

        /** 形が壊れているか。 */
        public boolean damaged() {
            return damagedAt >= 0;
        }
    }

    /**
     * バイト列をレコードへ切る。
     *
     * @param keepPrefix 可変長で {@code RDW} の 4 バイトをレコードに含めたまま持つか。
     *                   整列の道具は制御文の位置をそれを数に入れて数えるので含める。
     *                   プログラムへ渡すレコードには含めない
     */
    public static Framed split(byte[] bytes, DataSetAttributes attributes, boolean keepPrefix) {
        return switch (attributes.format()) {
            case FIXED -> fixed(bytes, attributes.recordLength());
            case VARIABLE -> variable(bytes, keepPrefix);
            case LINE -> lines(bytes, attributes.codePage().encode("\n")[0]);
        };
    }

    /**
     * 固定長は<b>長さで割り切れなければならない</b>。
     *
     * <p>半端が残るのは、書いている途中で落ちたか、レコード長の違うデータセットを取り違えた
     * ということである。半端をそのまま短いレコードとして渡すと、<b>読めていないデータで
     * 処理が進む</b>。切れるところまでを読めるものとし、その先を壊れた場所とする。
     */
    public static Framed fixed(byte[] bytes, int recordLength) {
        List<byte[]> out = new ArrayList<>();
        if (recordLength <= 0) {
            // 長さが決まっていないものは切りようがない
            return new Framed(out, bytes.length == 0 ? -1 : 0);
        }
        int at = 0;
        while (at + recordLength <= bytes.length) {
            out.add(Arrays.copyOfRange(bytes, at, at + recordLength));
            at += recordLength;
        }
        return new Framed(out, at < bytes.length ? out.size() : -1);
    }

    /**
     * 可変長は 4 バイトの {@code RDW} が先頭に付く。最初の 2 バイトが {@code RDW} を含む
     * 長さである。
     *
     * <p>長さが 4 に満たない、残りより長い、という {@code RDW} はつながらない。半端なバイトが
     * 残るのも同じで、いずれもそこから先は切り分けられない。
     */
    private static Framed variable(byte[] bytes, boolean keepPrefix) {
        List<byte[]> out = new ArrayList<>();
        int at = 0;
        while (at < bytes.length) {
            if (at + 4 > bytes.length) {
                return new Framed(out, out.size());
            }
            int length = ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
            if (length < 4 || at + length > bytes.length) {
                return new Framed(out, out.size());
            }
            out.add(Arrays.copyOfRange(bytes, keepPrefix ? at : at + 4, at + length));
            at += length;
        }
        return new Framed(out, -1);
    }

    /**
     * 行順は改行までが 1 レコードである。
     *
     * <p>行順に<b>壊れた形はない</b>。どこで切れても行は行だからである。最後の改行が
     * なければ、残りも 1 レコードとして扱う。
     */
    private static Framed lines(byte[] bytes, byte newline) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == newline) {
                out.add(Arrays.copyOfRange(bytes, start, i));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            out.add(Arrays.copyOfRange(bytes, start, bytes.length));
        }
        return new Framed(out, -1);
    }
}
