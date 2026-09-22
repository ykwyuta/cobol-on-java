package dev.cobolonjava.runtime.codepage;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static dev.cobolonjava.runtime.TestSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 日本語コードページの符号化 (要件 FR-051, FR-052)。
 *
 * <p>Hercules + MVS 3.8j には日本語コードページが無く、CCVS85 にも日本語の試験が無い。
 * だから<b>外から測れない</b> (要件定義書 4.3 節)。測れないものを測れるふりで固定すると、
 * 数そのものが信用できなくなる。ここで表明するのは絶対値ではなく、
 * <b>入力から導ける性質</b>だけである。
 *
 * <ul>
 *   <li><b>往復</b> — 符号化して復号すると元に戻る。期待値は入力そのものなので、
 *       参照実装が無くても書ける</li>
 *   <li><b>構造</b> — 2 バイト文字はシフトコードの組に入り、1 文字 2 バイトを占める</li>
 *   <li><b>断る</b> — 表せない文字は黙って置換せず、例外にする</li>
 * </ul>
 */
@Tag("V1")
class JapaneseCodePageTest {

    @Test
    @DisplayName("IBM-930 は全角文字を往復できる (FR-051)")
    void ibm930RoundTripsFullWidthCharacters() {
        CodePage cp = CodePages.IBM_930;
        assertEquals(JapaneseFixtures.NAME, cp.decode(cp.encode(JapaneseFixtures.NAME)));
        assertEquals(JapaneseFixtures.ITEM, cp.decode(cp.encode(JapaneseFixtures.ITEM)));
    }

    @Test
    @DisplayName("IBM-939 は全角文字を往復できる (FR-051)")
    void ibm939RoundTripsFullWidthCharacters() {
        CodePage cp = CodePages.IBM_939;
        assertEquals(JapaneseFixtures.CITY, cp.decode(cp.encode(JapaneseFixtures.CITY)));
    }

    @Test
    @DisplayName("全角文字はシフトアウトとシフトインの組に入り、1 文字 2 バイトを占める (FR-052)")
    void fullWidthCharactersAreWrappedInShiftCodes() {
        byte[] encoded = CodePages.IBM_930.encode(JapaneseFixtures.NAME);

        // ここで確かめているのは表の中身ではなく混在データの組み立て方である。
        // どの変換表を使っても、この構造は変わらない
        assertEquals(0x0E, encoded[0] & 0xFF, "先頭はシフトアウトでなければならない");
        assertEquals(0x0F, encoded[encoded.length - 1] & 0xFF,
                "末尾はシフトインでなければならない");
        assertEquals(2 + JapaneseFixtures.NAME.length() * 2, encoded.length,
                "全角 1 文字は 2 バイト。シフトコードが前後に 1 バイトずつ付く");
    }

    @Test
    @DisplayName("半角カタカナは 1 バイトで表され、シフトコードを伴わない (FR-051)")
    void halfWidthKatakanaIsSingleByte() {
        byte[] encoded = CodePages.IBM_930.encode(JapaneseFixtures.HALFWIDTH);

        assertEquals(JapaneseFixtures.HALFWIDTH.length(), encoded.length,
                "半角カタカナは 1 文字 1 バイトである");
        assertNotEquals(0x0E, encoded[0] & 0xFF, "1 バイト文字にシフトコードは付かない");
        assertEquals(JapaneseFixtures.HALFWIDTH, CodePages.IBM_930.decode(encoded));
    }

    @Test
    @DisplayName("検体のバイトは JDK の変換表と一致する (P-012: CDRA とは未照合)")
    void fixtureBytesMatchTheJdkTable() {
        // この 1 本だけが絶対値を見ている。JDK の表が変わったことに気付くための印であり、
        // 「実機がこうである」とは言っていない。CDRA と突き合わせたら V1 から上げ直す
        assertHex(JapaneseFixtures.NAME_930_HEX,
                CodePages.IBM_930.encode(JapaneseFixtures.NAME));
        assertHex(JapaneseFixtures.ITEM_930_HEX,
                CodePages.IBM_930.encode(JapaneseFixtures.ITEM));
        assertHex(JapaneseFixtures.CITY_939_HEX,
                CodePages.IBM_939.encode(JapaneseFixtures.CITY));
    }

    @Test
    @DisplayName("空白・数字・英大文字は 3 つのコードページで同じバイトになる (FR-051)")
    void theEbcdicInvariantPartIsSharedAcrossCodePages() {
        // 日本語を混ぜた検査が期待値に書いてよいのはこの範囲だけである。
        // 生成コードが CodePages.DEFAULT を焼き込んでいても、埋め草と照合順序が
        // 日本語コードページと食い違わないのはこれが理由である (設計 28 章)
        for (CodePage cp : new CodePage[] {CodePages.IBM_1047, CodePages.IBM_930,
                CodePages.IBM_939}) {
            assertEquals((byte) 0x40, cp.space(), cp.name() + " の空白");
            assertHex("F0F9", cp.encode("09"), cp.name() + " の数字");
            assertHex("C1E9", cp.encode("AZ"), cp.name() + " の英大文字");
        }
    }

    @Test
    @DisplayName("IBM-1047 は日本語を表せないので符号化を断る (FR-181)")
    void ibm1047RefusesJapanese() {
        // 直す前はここが X'3F3F3F3F' (EBCDIC の SUB) を黙って返していた。
        // 翻訳も実行も成功したまま、書かれていた 4 文字だけが消えていた
        assertFalse(CodePages.IBM_1047.canEncode(JapaneseFixtures.NAME));

        UnrepresentableCharacterException failure = assertThrows(
                UnrepresentableCharacterException.class,
                () -> CodePages.IBM_1047.encode(JapaneseFixtures.NAME));
        assertEquals("山", failure.character(), "表せなかった最初の文字を名指しする");
        assertEquals("IBM-1047", failure.codePageName());
        assertTrue(failure.getMessage().contains("U+5C71"),
                () -> "診断に符号位置が要る: " + failure.getMessage());
    }

    @Test
    @DisplayName("日本語コードページは日本語を表せると答える (FR-051)")
    void japaneseCodePagesCanEncodeJapanese() {
        assertTrue(CodePages.IBM_930.canEncode(JapaneseFixtures.NAME));
        assertTrue(CodePages.IBM_939.canEncode(JapaneseFixtures.CITY));
    }

    @Test
    @DisplayName("IBM-1047 は 256 バイトすべてを往復できる。DISPLAY が値を隠さない根拠である")
    void ibm1047IsATotalSingleByteTable() {
        // decode は符号化と対称ではない。1047 でだけは全単射なので、
        // DISPLAY に出した値が読めなくなることがない
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        assertHex(dev.cobolonjava.runtime.TestSupport.hex(all),
                CodePages.IBM_1047.encode(CodePages.IBM_1047.decode(all)));
    }

    @Test
    @DisplayName("シフトコードの組が閉じていないバイト列は往復しない。だから断らずに読む")
    void unpairedShiftCodesDoNotRoundTrip() {
        // PIC X の項目を奇数バイトで切ると混在データは壊れる。壊れるのはホストでも同じであり、
        // 処理系が直す筋合いのものではない。ここで固定するのは
        // 「壊れた値でも DISPLAY は出せる」ことである (CodePage.decode の注記)
        byte[] truncated = bytes("0E45654563");
        String shown = CodePages.IBM_930.decode(truncated);
        assertNotEquals(JapaneseFixtures.NAME, shown);
        assertFalse(shown.isEmpty(), "読めなくても何かは出す。出せないと調べる手段が消える");
    }
}
