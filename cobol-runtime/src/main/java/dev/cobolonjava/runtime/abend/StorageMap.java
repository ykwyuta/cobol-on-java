package dev.cobolonjava.runtime.abend;

import dev.cobolonjava.runtime.item.Usage;
import java.util.ArrayList;
import java.util.List;

/**
 * 作業場所の割り付け (要件 FR-142)。
 *
 * <p>どのバイトがどの項目かを知っているのは翻訳の側である。実行時に手元にあるのは
 * バイト列だけなので、<b>割り付けを生成クラスへ埋めて持ち歩く</b>。異常終了の覚え書きが
 * 「16 進の羅列」ではなく「項目名と値」を書けるのはこれがあるからである。
 *
 * <h2>1 本の文字列に畳んで運ぶ</h2>
 * <p>項目ごとにバイトコードを吐くと、項目の多いプログラムでクラスファイルが膨らむ。
 * 初期イメージと同じ考えで、<b>文字列定数 1 個</b>に畳んでから実行時にほどく。
 */
public record StorageMap(List<Entry> entries) {

    public StorageMap {
        entries = List.copyOf(entries);
    }

    /** 割り付けを持たないプログラム。手で書いたものと、翻訳前のものがこれである。 */
    public static final StorageMap EMPTY = new StorageMap(List.of());

    /** 項目の種類。値の見せ方を決める。 */
    public enum Kind {

        /** 下位の項目を持つ集団項目。値そのものは持たない。 */
        GROUP,

        /** 文字。コードページで読んで見せる。 */
        TEXT,

        /** 数値。{@link #picture} と {@link #usage} で読んで見せる。 */
        NUMBER,

        /** 指標。{@code INDEXED BY} で作られる。 */
        INDEX
    }

    /**
     * 項目 1 個。
     *
     * @param depth   入れ子の深さ。見せ方を揃えるためだけのもの
     * @param level   レベル番号。COBOL を書いた人が読む番号である
     * @param offset  作業場所の先頭からのバイト位置
     * @param length  1 回分のバイト長
     * @param occurs  反復の回数。{@code OCCURS} を書いていなければ 1
     * @param picture {@code PIC}。集団項目では空
     * @param usage   {@code USAGE}。集団項目では {@code null}
     */
    public record Entry(int depth, int level, String name, int offset, int length, int occurs,
                        Kind kind, String picture, Usage usage) {
    }

    /** 割り付けを文字列 1 本に畳む。 */
    public String encoded() {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            sb.append(entry.depth()).append('\t')
                    .append(entry.level()).append('\t')
                    .append(entry.name()).append('\t')
                    .append(entry.offset()).append('\t')
                    .append(entry.length()).append('\t')
                    .append(entry.occurs()).append('\t')
                    .append(entry.kind()).append('\t')
                    .append(entry.picture() == null ? "" : entry.picture()).append('\t')
                    .append(entry.usage() == null ? "" : entry.usage().name()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 畳んだ文字列をほどく。
     *
     * <p>読めない行は<b>読み飛ばす</b>。割り付けは覚え書きのためのものであり、
     * ここで実行を止める理由がない。
     */
    public static StorageMap parse(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return EMPTY;
        }
        List<Entry> out = new ArrayList<>();
        for (String line : encoded.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 9) {
                continue;
            }
            try {
                out.add(new Entry(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        parts[2],
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        Integer.parseInt(parts[5]),
                        Kind.valueOf(parts[6]),
                        parts[7],
                        parts[8].isEmpty() ? null : Usage.valueOf(parts[8])));
            } catch (IllegalArgumentException e) {
                // 読めない行は落とす。覚え書きが少し粗くなるだけである
            }
        }
        return new StorageMap(out);
    }

    /** 割り付けを持たないか。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
