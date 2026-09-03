package dev.cobolonjava.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * テストの共通補助。
 *
 * <p><b>検証レベルについて</b> (要件 NFR-043): 本モジュールのテストはすべて JUnit のタグで
 * 検証レベルを表明する。
 * <ul>
 *   <li>{@code V2} — Hercules 上での実行により期待値を採取・照合したもの</li>
 *   <li>{@code V1} — 公開仕様書の記述に基づく期待値。実行による裏取りがない</li>
 * </ul>
 * <b>本モジュール (cobol-runtime) のテストはすべて {@code V1} である。</b>
 * V2 の検証は {@code cobol-oracle} モジュールにあり、Hercules 上で実行した
 * z/Architecture 命令の結果とバイト列で突き合わせている。
 */
public final class TestSupport {

    private TestSupport() {
    }

    /** 期待値を 16 進文字列で表明する。バイト列の差異を読みやすく報告するため。 */
    public static void assertHex(String expectedHex, byte[] actual) {
        assertEquals(expectedHex.replace(" ", "").toUpperCase(), hex(actual));
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /** {@code "F1F2F3"} のような 16 進文字列をバイト列にする。 */
    public static byte[] bytes(String hex) {
        String s = hex.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
