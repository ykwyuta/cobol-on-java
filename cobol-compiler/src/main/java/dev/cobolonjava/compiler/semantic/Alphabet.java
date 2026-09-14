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
            List<List<Byte>> positions = positionsOf(entry, origin, diagnostics);
            if (positions == null) {
                return null;
            }
            for (List<Byte> sharing : positions) {
                for (byte character : sharing) {
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
     * 書かれた 1 項が取る位置の並び (要件 FR-054)。
     *
     * <p>1 項が<b>いくつの位置を取るか</b>は書き方で変わる。ここを取り違えると、
     * 照合順序が丸ごとずれる。
     *
     * <ul>
     *   <li>{@code "A" THRU "D"} — 範囲の 1 文字ずつが<b>別の位置</b>を取る</li>
     *   <li>{@code "A" ALSO "a"} — 並べた文字が<b>1 つの位置を分け合う</b>。
     *       等しいものとして比べられる</li>
     *   <li>{@code "ABCD"} — 2 文字以上の定数は、1 文字ずつが<b>別の位置</b>を取る。
     *       {@code "A" "B" "C" "D"} と並べたのと同じである</li>
     * </ul>
     *
     * @return 位置ごとの文字の並び。読めなければ {@code null}
     */
    private static List<List<Byte>> positionsOf(CobolParser.AlphabetPositionContext entry,
                                                Origin origin, List<Diagnostic> diagnostics) {
        List<List<Byte>> positions = new ArrayList<>();
        if (entry.THROUGH() != null || entry.THRU() != null) {
            Byte first = characterOf(entry.literal(0), origin, diagnostics);
            Byte last = characterOf(entry.literal(1), origin, diagnostics);
            if (first == null || last == null) {
                return null;
            }
            int from = first & 0xFF;
            int to = last & 0xFF;
            int step = from <= to ? 1 : -1;
            for (int value = from; value != to + step; value += step) {
                positions.add(List.of((byte) value));
            }
            return positions;
        }
        if (entry.literal().size() > 1) {
            List<Byte> sharing = new ArrayList<>();
            for (CobolParser.LiteralContext literal : entry.literal()) {
                List<Byte> characters = charactersOf(literal, origin, diagnostics);
                if (characters == null) {
                    return null;
                }
                sharing.addAll(characters);
            }
            positions.add(sharing);
            return positions;
        }
        List<Byte> characters = charactersOf(entry.literal(0), origin, diagnostics);
        if (characters == null) {
            return null;
        }
        for (byte character : characters) {
            positions.add(List.of(character));
        }
        return positions;
    }

    /**
     * {@code CLASS} 句の 1 項が表す文字 (要件 FR-046)。
     *
     * <p>{@code THRU} なら範囲を広げる。並びの表を作るのと同じ読み方をするので、
     * ここに置いてある。
     *
     * @return 読めなければ {@code null}
     */
    public static byte[] charactersOfMember(CobolParser.ClassMemberContext member, Origin origin,
                                            List<Diagnostic> diagnostics) {
        List<Byte> out = new ArrayList<>();
        if (member.THROUGH() == null && member.THRU() == null) {
            List<Byte> characters = charactersOf(member.literal(0), origin, diagnostics);
            if (characters == null) {
                return null;
            }
            out.addAll(characters);
        } else {
            Byte first = characterOf(member.literal(0), origin, diagnostics);
            Byte last = characterOf(member.literal(1), origin, diagnostics);
            if (first == null || last == null) {
                return null;
            }
            int from = first & 0xFF;
            int to = last & 0xFF;
            int step = from <= to ? 1 : -1;
            for (int value = from; value != to + step; value += step) {
                out.add((byte) value);
            }
        }
        byte[] bytes = new byte[out.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = out.get(i);
        }
        return bytes;
    }

    /**
     * 定数 1 個が表す文字の並び。
     *
     * <p>2 文字以上の定数は<b>その文字すべて</b>である。数字定数と figurative constant は
     * 1 文字である。
     *
     * @return 読めなければ {@code null}
     */
    private static List<Byte> charactersOf(CobolParser.LiteralContext context, Origin origin,
                                           List<Diagnostic> diagnostics) {
        LiteralValue value;
        try {
            value = LiteralValue.of(context);
        } catch (RuntimeException e) {
            diagnostics.add(new Diagnostic(origin, "invalid literal: " + context.getText()));
            return null;
        }
        if (value instanceof LiteralValue.Text text && text.isHex()) {
            // 16 進定数はバイトそのものを指す。code page を通さない
            List<Byte> characters = new ArrayList<>();
            for (byte b : text.hex()) {
                characters.add(b);
            }
            return characters;
        }
        if (value instanceof LiteralValue.Text text && text.text().length() > 1) {
            List<Byte> characters = new ArrayList<>();
            for (int i = 0; i < text.text().length(); i++) {
                characters.add(CODE_PAGE.ch(text.text().charAt(i)));
            }
            return characters;
        }
        Byte one = characterOf(context, origin, diagnostics);
        return one == null ? null : List.of(one);
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
        if (value instanceof LiteralValue.Text text && text.isHex() && text.hex().length == 1) {
            return text.hex()[0];
        }
        if (value instanceof LiteralValue.Text text && !text.isHex() && text.text().length() == 1) {
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
