package dev.cobolonjava.runtime.codepage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** コードページで表せる符号位置の一覧。ブラウザが入力を弾く根拠になる。 */
@Tag("V1")
class CodePageRepertoireTest {

    /**
     * 範囲は昇順で重ならないので二分で引く。IBM-930 の DBCS は 4000 を超える範囲になり、
     * 全符号位置を総当たりで引くと試験だけで 1 分かかる。
     */
    private static boolean contains(List<CodePageRepertoire.Range> ranges, int codePoint) {
        int low = 0;
        int high = ranges.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            CodePageRepertoire.Range range = ranges.get(middle);
            if (codePoint < range.from()) {
                high = middle - 1;
            } else if (codePoint > range.to()) {
                low = middle + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("SBCSのコードページは256符号位置だけを持ち、DBCSは持たない")
    void listsTheSingleByteRepertoire() {
        CodePageRepertoire repertoire = CodePageRepertoire.of(CodePages.IBM_1047);

        assertEquals(List.of(new CodePageRepertoire.Range(0, 0xFF)), repertoire.singleByte());
        assertTrue(repertoire.doubleByte().isEmpty());
        assertFalse(repertoire.shifted());
        // IBM-1047 は Latin-1 を全部持つ。日本語は持たない
        assertTrue(contains(repertoire.singleByte(), 'A'));
        assertTrue(contains(repertoire.singleByte(), 0x00E9));
        assertFalse(contains(repertoire.singleByte(), 0x5C71));
    }

    @Test
    @DisplayName("混在コードページはDBCSを2桁の側に置き、半角カタカナは1桁の側に置く")
    void separatesDoubleByteFromSingleByte() {
        CodePageRepertoire repertoire = CodePageRepertoire.of(CodePages.IBM_930);

        assertTrue(repertoire.shifted());
        assertTrue(contains(repertoire.doubleByte(), 0x5C71), "山");
        assertTrue(contains(repertoire.doubleByte(), 0xFF21), "Ａ");
        assertTrue(contains(repertoire.singleByte(), 0xFF71), "ｱ");
        assertTrue(contains(repertoire.singleByte(), 'A'));
        // IBM-930 は Latin-1 のアクセント付きを持たない
        assertFalse(contains(repertoire.singleByte(), 0x00E9));
        assertFalse(contains(repertoire.doubleByte(), 0x00E9));
    }

    @Test
    @DisplayName("一覧は表せる符号位置を余さず覆い、桁数も符号化と一致する")
    void agreesWithTheEncoderEverywhere() {
        for (CodePage codePage : List.of(CodePages.IBM_1047, CodePages.IBM_037,
                CodePages.IBM_930, CodePages.IBM_939)) {
            CodePageRepertoire repertoire = CodePageRepertoire.of(codePage);
            // 符号位置を 110 万個引くので、encoder は 1 つを使い回す。公開の API
            // (canEncode / encode) と同じ判定になることは encoderMatchesThePublicApi で確かめる
            CharsetEncoder encoder = codePage.charset().newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            for (int codePoint : probes(repertoire)) {
                if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                    continue;
                }
                char[] character = Character.toChars(codePoint);
                boolean single = contains(repertoire.singleByte(), codePoint);
                boolean twice = contains(repertoire.doubleByte(), codePoint);
                if (!encoder.canEncode(CharBuffer.wrap(character))) {
                    assertFalse(single || twice, () -> "listed but not encodable: U+"
                            + Integer.toHexString(codePoint));
                    continue;
                }
                // 表せる符号位置は必ずどちらか片方にある。どちらでもない文字があると、
                // client は桁数を数えられず、入る文字を弾いてしまう
                assertTrue(single ^ twice, () -> codePage.name() + " does not classify U+"
                        + Integer.toHexString(codePoint));
                // 一覧の桁数は、実際に符号化した桁数と同じでなければならない。混在コードページでは
                // 単独の符号化がシフト符号を伴うので、その 2 桁を除いて比べる
                int length = encodedLength(encoder, character);
                int cells = length - (twice && repertoire.shifted() ? 2 : 0);
                assertEquals(single ? 1 : 2, cells, () -> codePage.name() + " U+"
                        + Integer.toHexString(codePoint));
            }
        }
    }

    @Test
    @DisplayName("一覧の判定は、実行時が使う CodePage.encode と同じである")
    void encoderMatchesThePublicApi() {
        CodePageRepertoire repertoire = CodePageRepertoire.of(CodePages.IBM_930);
        for (String character : List.of("A", "\u5C71", "\uFF71", "\uFF21", "\u00E9", "\u20AC", "\uD83D\uDE00")) {
            int codePoint = character.codePointAt(0);
            boolean single = contains(repertoire.singleByte(), codePoint);
            boolean twice = contains(repertoire.doubleByte(), codePoint);
            assertEquals(CodePages.IBM_930.canEncode(character), single || twice, character);
            if (single || twice) {
                assertEquals(CodePages.IBM_930.encode(character).length - (twice ? 2 : 0),
                        single ? 1 : 2, character);
            }
        }
    }

    /**
     * 引く符号位置。基本多言語面は全部、追加面は飛ばし飛ばし、そして<b>範囲の両端とその外側</b>。
     *
     * <p>境目は一覧が間違えるとしたらそこなので、1 つも落とさない。
     */
    private static int[] probes(CodePageRepertoire repertoire) {
        java.util.TreeSet<Integer> points = new java.util.TreeSet<>();
        for (int codePoint = 0; codePoint <= Character.MAX_VALUE; codePoint++) {
            points.add(codePoint);
        }
        for (int codePoint = Character.MAX_VALUE + 1; codePoint <= Character.MAX_CODE_POINT; codePoint += 97) {
            points.add(codePoint);
        }
        for (List<CodePageRepertoire.Range> ranges
                : List.of(repertoire.singleByte(), repertoire.doubleByte())) {
            for (CodePageRepertoire.Range range : ranges) {
                points.add(range.from());
                points.add(range.to());
                if (range.from() > 0) {
                    points.add(range.from() - 1);
                }
                if (range.to() < Character.MAX_CODE_POINT) {
                    points.add(range.to() + 1);
                }
            }
        }
        return points.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int encodedLength(CharsetEncoder encoder, char[] character) {
        try {
            return encoder.reset().encode(CharBuffer.wrap(character)).remaining();
        } catch (CharacterCodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test
    @DisplayName("一覧はコードページごとに1度だけ作る")
    void remembersTheScanPerCodePage() {
        assertSame(CodePageRepertoire.of(CodePages.IBM_939), CodePageRepertoire.of(CodePages.IBM_939));
        assertEquals("IBM-939", CodePageRepertoire.of(CodePages.IBM_939).codePageName());
    }
}
