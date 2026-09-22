package dev.cobolonjava.runtime.codepage;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * このコードページで表せる符号位置の一覧 (要件 FR-051)。
 *
 * <p><b>なぜ要るか</b>: 表せない文字を断れるのは符号化するときであり、それは端末の入力が
 * COBOL の記憶域へ届く直前である。利用者から見れば「打てたのに、送ったら失敗した」になる。
 * 実機の 3270 は、そもそもコードページに無い文字をキーボードが出さない。ブラウザで同じことを
 * するには、client が<b>どの文字が入るか</b>を知っている必要がある。その一覧をここで作る。
 *
 * <p>一覧は<b>符号化して数えて</b>作る。charset の対応表を写さず、実行時に使うのと同じ
 * {@link java.nio.charset.Charset} に聞くので、server が受ける文字と client が通す文字が
 * ずれない。
 *
 * <p>符号位置は占める画面位置 (cell) で 2 つに分かれる。
 *
 * <ul>
 *   <li>{@link #singleByte()}: 1 byte = 1 桁の文字</li>
 *   <li>{@link #doubleByte()}: 2 byte = 2 桁の文字 (DBCS)</li>
 * </ul>
 *
 * <p>EBCDIC の混在コードページは、DBCS の連なりをシフトアウト (X'0E') とシフトイン (X'0F') で
 * 囲む。囲みも 1 桁ずつ占める ({@link #shifted()} が真)。IBM-930 / 939 / 1047 / 037 では、
 * 表せる符号位置はすべてこの 2 つのどちらかに入る (`CodePageRepertoireTest` で確かめている)。
 * どちらでもない符号位置 (1 でも 2 byte でもない文字) は一覧に載せない。載せると client が
 * 桁数を数えられない。
 *
 * <p>走査はコードページごとに 1 度だけ行って覚える。
 */
public final class CodePageRepertoire {

    /** 符号位置の範囲。両端を含む。 */
    public record Range(int from, int to) {
        public Range {
            if (from < 0 || to < from) {
                throw new IllegalArgumentException("bad code point range: " + from + ".." + to);
            }
        }
    }

    private static final byte SHIFT_OUT = 0x0E;
    private static final byte SHIFT_IN = 0x0F;

    private static final Map<String, CodePageRepertoire> CACHE = new ConcurrentHashMap<>();

    private final String codePageName;
    private final List<Range> singleByte;
    private final List<Range> doubleByte;
    private final boolean shifted;

    private CodePageRepertoire(String codePageName, List<Range> singleByte, List<Range> doubleByte,
                               boolean shifted) {
        this.codePageName = codePageName;
        this.singleByte = Collections.unmodifiableList(singleByte);
        this.doubleByte = Collections.unmodifiableList(doubleByte);
        this.shifted = shifted;
    }

    /** このコードページの一覧。コードページごとに 1 度だけ作る。 */
    public static CodePageRepertoire of(CodePage codePage) {
        Objects.requireNonNull(codePage, "codePage");
        return CACHE.computeIfAbsent(codePage.name(), ignored -> scan(codePage));
    }

    public String codePageName() {
        return codePageName;
    }

    /** 1 桁を占める符号位置の範囲。昇順で重ならない。 */
    public List<Range> singleByte() {
        return singleByte;
    }

    /** 2 桁を占める符号位置 (DBCS) の範囲。昇順で重ならない。 */
    public List<Range> doubleByte() {
        return doubleByte;
    }

    /** DBCS の連なりをシフトアウトとシフトインで囲むか。囲みも 1 桁ずつ占める。 */
    public boolean shifted() {
        return shifted;
    }

    /**
     * 符号位置を端から端まで符号化して分ける。
     *
     * <p><b>なぜ byte の側から数えないか</b>: コードページは byte から文字への表なので、
     * 256 通りと 65536 通りの byte を復号するほうが 10 倍速い。しかし<b>対応は 1 対 1 ではない</b>。
     * IBM-930 では U+2014 (em dash) を符号化できるのに、その byte 列は U+2015 に復号される。
     * byte の側から数えると、こういう文字が 50 個あまり一覧から落ちる。落ちた文字は client が
     * 弾くが server は受けるので、<b>打てるはずの文字が打てなくなる</b>。測って分かったことである。
     *
     * <p>符号位置は 110 万個ある。1 つずつ確保していると 1 秒近くかかるので、char の入れ物と
     * byte の入れ物は使い回す。判定そのものは {@link CodePage#encode} と同じ設定の encoder で行う
     * (混在コードページは閉じるシフトインが {@code flush} で出るので、そこまでを 1 文字と数える)。
     */
    private static CodePageRepertoire scan(CodePage codePage) {
        CharsetEncoder encoder = codePage.charset().newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        char[] characters = new char[2];
        CharBuffer in = CharBuffer.wrap(characters);
        ByteBuffer out = ByteBuffer.allocate(16);
        SortedSet<Integer> single = new TreeSet<>();
        SortedSet<Integer> twice = new TreeSet<>();
        boolean shifted = false;
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            // 代用符号は単独では文字でない
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                continue;
            }
            int length = Character.toChars(codePoint, characters, 0);
            in.limit(length).position(0);
            out.clear();
            if (encoder.reset().encode(in, out, true).isError()) {
                continue;
            }
            // 混在コードページの encoder はシフトの状態を持つ。閉じるシフトインは flush で出る
            if (encoder.flush(out).isError()) {
                continue;
            }
            int bytes = out.position();
            if (bytes == 1) {
                single.add(codePoint);
            } else if (bytes == 2) {
                twice.add(codePoint);
            } else if (bytes == 4 && out.get(0) == SHIFT_OUT && out.get(3) == SHIFT_IN) {
                twice.add(codePoint);
                shifted = true;
            }
            // 1 でも 2 byte でもない文字は一覧に載せない。桁数を決められないからである
        }
        return new CodePageRepertoire(codePage.name(), ranges(single), ranges(twice), shifted);
    }

    private static List<Range> ranges(SortedSet<Integer> codePoints) {
        List<Range> out = new ArrayList<>();
        int from = -1;
        int previous = -2;
        for (int codePoint : codePoints) {
            if (codePoint != previous + 1) {
                if (from >= 0) {
                    out.add(new Range(from, previous));
                }
                from = codePoint;
            }
            previous = codePoint;
        }
        if (from >= 0) {
            out.add(new Range(from, previous));
        }
        return out;
    }
}
