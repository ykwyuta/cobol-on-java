package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.codepage.CollatingSequence;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code SPECIAL-NAMES} の {@code ALPHABET} 句から照合順序の表を作る (要件 FR-054)。
 *
 * <p>作るのは<b>バイト値から位置への表</b>である。256 個の要素を持ち、{@code table[b]} が
 * 値 {@code b} の位置になる。位置は 0 から数える。実行時の
 * {@link CollatingSequence} がこの表をそのまま受け取る。
 *
 * <h2>並べなかった文字は後ろへ回す</h2>
 * <p>文字を並べて書く形 ({@code ALPHABET A IS "F" "U" "N"}) では、<b>書かれた文字が
 * 先頭から順に</b>位置を取り、書かれなかった文字はその後ろに<b>コードページの並びのまま</b>
 * 続く。規格がそう決めている。書かれた文字が 3 つなら、残り 253 文字は 4 番目以降に
 * 元の順で並ぶ。
 *
 * <h2>ALSO は同じ位置である</h2>
 * <p>{@code "F" ALSO "f"} と書けば {@code F} と {@code f} は<b>比較して等しくなる</b>。
 * 位置が同じだからである。
 */
final class Alphabet {

    /** 表の大きさ。 */
    private static final int SIZE = CollatingSequence.SIZE;

    /** 翻訳時のコードページ。定数を並べるときのバイト値はここで決まる。 */
    private static final CodePage CODE_PAGE = CodePages.DEFAULT;

    private Alphabet() {
    }

    /** コードページのバイト値そのものの並びかどうか。 */
    static boolean isNative(byte[] table) {
        for (int i = 0; i < SIZE; i++) {
            if ((table[i] & 0xFF) != i) {
                return false;
            }
        }
        return true;
    }

    /** バイト値がそのまま位置になる表。 */
    static byte[] nativeTable() {
        byte[] table = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            table[i] = (byte) i;
        }
        return table;
    }

    /**
     * {@code ALPHABET 名前 IS ...} の右辺を読む。
     *
     * @return 読めなければ {@code null}
     */
    static byte[] of(CobolParser.AlphabetSpecificationContext context, Origin origin,
                     List<Diagnostic> diagnostics) {
        if (context.NATIVE() != null || context.EBCDIC() != null) {
            // コードページが EBCDIC なので、どちらもバイト値そのものの並びである
            return nativeTable();
        }
        if (context.STANDARD_1() != null || context.STANDARD_2() != null) {
            return standardTable();
        }
        return listedTable(context, origin, diagnostics);
    }

    /**
     * {@code STANDARD-1} / {@code STANDARD-2} の並び。
     *
     * <p>どちらも ISO/IEC 646 (ASCII) の並びである。コードページの各バイトを 1 文字へ
     * 復号し、その文字の ASCII 番号を位置とする。<b>ASCII に無い文字は後ろへ回す</b>。
     * 規格は集合の外の文字の位置を決めていないので、コードページの並びのまま続ける
     * (暫定判断 P-042)。
     */
    private static byte[] standardTable() {
        List<Integer> listed = new ArrayList<>();
        List<Integer> rest = new ArrayList<>();
        int[] ascii = new int[SIZE];
        for (int value = 0; value < SIZE; value++) {
            char decoded = CODE_PAGE.decode(new byte[] { (byte) value }).charAt(0);
            ascii[value] = decoded < 128 ? decoded : -1;
            (ascii[value] < 0 ? rest : listed).add(value);
        }
        listed.sort((a, b) -> Integer.compare(ascii[a], ascii[b]));
        byte[] table = new byte[SIZE];
        int position = 0;
        for (int value : listed) {
            table[value] = (byte) position++;
        }
        for (int value : rest) {
            table[value] = (byte) position++;
        }
        return table;
    }

    /**
     * 定数を並べて書く形。
     *
     * @return 読めなければ {@code null}
     */
    private static byte[] listedTable(CobolParser.AlphabetSpecificationContext context,
                                      Origin origin, List<Diagnostic> diagnostics) {
        int[] table = new int[SIZE];
        boolean[] placed = new boolean[SIZE];
        int position = 0;
        for (CobolParser.AlphabetPositionContext entry : context.alphabetPosition()) {
            List<Byte> characters = charactersOf(entry, origin, diagnostics);
            if (characters == null) {
                return null;
            }
            for (byte character : characters) {
                int value = character & 0xFF;
                if (placed[value]) {
                    diagnostics.add(new Diagnostic(origin,
                            "a character appears twice in the alphabet"));
                    return null;
                }
                placed[value] = true;
                table[value] = position;
            }
            position++;
        }
        // 書かれなかった文字は、コードページの並びのまま後ろへ続く
        for (int value = 0; value < SIZE; value++) {
            if (!placed[value]) {
                table[value] = position++;
            }
        }
        if (position > SIZE) {
            diagnostics.add(new Diagnostic(origin, "the alphabet has more than "
                    + SIZE + " positions"));
            return null;
        }
        byte[] result = new byte[SIZE];
        for (int value = 0; value < SIZE; value++) {
            result[value] = (byte) table[value];
        }
        return result;
    }

    /**
     * 1 つの位置に置く文字。{@code ALSO} で並べたものと {@code THRU} の範囲を展開する。
     *
     * @return 読めなければ {@code null}
     */
    private static List<Byte> charactersOf(CobolParser.AlphabetPositionContext entry,
                                           Origin origin, List<Diagnostic> diagnostics) {
        List<Byte> characters = new ArrayList<>();
        Byte first = characterOf(entry.literal(0), origin, diagnostics);
        if (first == null) {
            return null;
        }
        if (entry.THROUGH() == null && entry.THRU() == null) {
            characters.add(first);
            for (int i = 1; i < entry.literal().size(); i++) {
                // ALSO で並べた文字はこの位置を分け合う
                Byte also = characterOf(entry.literal(i), origin, diagnostics);
                if (also == null) {
                    return null;
                }
                characters.add(also);
            }
            return characters;
        }
        Byte last = characterOf(entry.literal(1), origin, diagnostics);
        if (last == null) {
            return null;
        }
        // 範囲はコードページの並びで数える。THRU の 1 文字ずつが別の位置を取る形は
        // 呼ぶ側が展開できないので、ここでは 1 つの位置にまとめず順に返す
        int from = first & 0xFF;
        int to = last & 0xFF;
        int step = from <= to ? 1 : -1;
        for (int value = from; value != to + step; value += step) {
            characters.add((byte) value);
        }
        return characters;
    }

    /**
     * 定数 1 個を 1 バイトへ落とす。
     *
     * @return 1 文字でなければ {@code null}
     */
    private static Byte characterOf(CobolParser.LiteralContext context, Origin origin,
                                    List<Diagnostic> diagnostics) {
        LiteralValue value;
        try {
            value = LiteralValue.of(context);
        } catch (RuntimeException e) {
            diagnostics.add(new Diagnostic(origin, "invalid literal: " + context.getText()));
            return null;
        }
        if (value instanceof LiteralValue.Figure figure) {
            return switch (figure.constant()) {
                case HIGH_VALUE -> (byte) 0xFF;
                case LOW_VALUE -> (byte) 0x00;
                case SPACE -> CODE_PAGE.space();
                case QUOTE -> CODE_PAGE.ch('"');
                case ZERO -> CODE_PAGE.digit(0);
                default -> null;
            };
        }
        if (value instanceof LiteralValue.Text text && text.text().length() == 1) {
            return CODE_PAGE.ch(text.text().charAt(0));
        }
        if (value instanceof LiteralValue.Number number) {
            // 数字定数は「照合順序の何番目か」を表す。1 から数える
            int position = number.value().toBigDecimal().intValue();
            if (position >= 1 && position <= SIZE) {
                return (byte) (position - 1);
            }
        }
        diagnostics.add(new Diagnostic(origin,
                "an alphabet takes one-character literals: " + context.getText()));
        return null;
    }
}
