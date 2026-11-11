package dev.cobolonjava.runtime.data;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * DBCS の項目 ({@code PIC G}、{@code USAGE DISPLAY-1}) の転記と変換。
 *
 * <p>DBCS の項目は 2 バイト文字だけを並べ、SO ({@code X'0E'}) と SI ({@code X'0F'}) で囲まない。
 * 英数字の項目に日本語を書くと SO と SI で囲まれる (混在の形) ので、コードページで字に
 * 直すときは囲んでから直し、字から作るときは囲みを外す。空白は {@code X'4040'} である
 * (Enterprise COBOL プログラミングの手引き "DBCS")。
 */
public final class Dbcs {

    /** 2 バイト文字の始まり (shift-out)。 */
    public static final byte SO = 0x0E;
    /** 2 バイト文字の終わり (shift-in)。 */
    public static final byte SI = 0x0F;
    /** DBCS の空白の 1 バイト。2 つで 1 字である。 */
    public static final byte SPACE = 0x40;

    private Dbcs() {
    }

    /**
     * DBCS の受取項目へ移す。左詰めで、余りは DBCS の空白で埋め、長いほうは右を切る。
     * 字の途中で切らないように、長さは 2 の倍数で扱う。
     */
    public static void move(byte[] source, Storage storage, int offset, int length) {
        int copied = Math.min(source.length, length) & ~1;
        byte[] target = storage.array();
        for (int i = 0; i < length; i++) {
            target[offset + i] = i < copied ? source[i] : SPACE;
        }
    }

    /** 字 (コードページの外の世界の文字列) を DBCS のバイト列にする。2 バイト文字でない字は断る。 */
    public static byte[] fromText(String text, CodePage codePage) {
        byte[] mixed = codePage.encode(text);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean inside = false;
        for (byte b : mixed) {
            if (b == SO) {
                inside = true;
            } else if (b == SI) {
                inside = false;
            } else if (inside) {
                out.write(b);
            } else if (b == SPACE && text.isBlank()) {
                // 空白だけの定数は DBCS の空白にする
                out.write(SPACE);
                out.write(SPACE);
            } else {
                throw new IllegalArgumentException("a DBCS literal must consist of double-byte"
                        + " characters of the code page " + codePage.name() + ": " + text);
            }
        }
        return out.toByteArray();
    }

    /** DBCS のバイト列を字にする。SO と SI で囲んでからコードページで直す。 */
    public static String toText(byte[] dbcs, CodePage codePage) {
        byte[] mixed = new byte[dbcs.length + 2];
        mixed[0] = SO;
        System.arraycopy(dbcs, 0, mixed, 1, dbcs.length);
        mixed[mixed.length - 1] = SI;
        return codePage.decode(mixed);
    }

    /** DBCS のバイト列を国字 (UTF-16) にする。DBCS の空白は和字の空白 (U+3000) になる。 */
    public static byte[] toNational(byte[] dbcs, CodePage codePage) {
        return toText(dbcs, codePage).getBytes(StandardCharsets.UTF_16BE);
    }
}
