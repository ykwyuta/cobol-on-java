package dev.cobolonjava.runtime.data;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * 国字項目 ({@code PIC N}、{@code USAGE NATIONAL}) の表現と変換 (要件 FR-050、設計 28)。
 *
 * <p>国字は UTF-16 のビッグエンディアンであり、1 文字 (符号単位) が 2 バイトを占める。
 * 空白は {@code X'0020'}、{@code ZERO} は {@code X'0030'}、{@code HIGH-VALUE} は
 * {@code X'FFFF'} である。比較は符号単位の 2 進の値で行い、照合順序は使わない。
 *
 * <p>英数字から国字への変換は、英数字のバイトをプログラムのコードページ (または指定した
 * CCSID) の文字として読む。読めないバイト (混在コードページで組の閉じていない
 * シフトアウトなど) は断る。国字から英数字への変換 ({@code DISPLAY-OF}) も、表せない文字を
 * 断る。実機の Unicode 変換サービスは置換文字 (SUB) へ倒すと言われているが、確かめていない
 * (暫定判断 P-012、z/OS probe の {@code CBLCP})。確かめるまでは黙って近い値を返さない。
 * {@code DISPLAY} だけは内容を見る手段なので、表せない文字を置換して出す
 * ({@link #toDisplay})。
 */
public final class National {

    /** 国字の空白。 */
    public static final char SPACE = 0x0020;

    private National() {
    }

    /** 英数字のバイト列を国字にする。 */
    public static byte[] fromAlphanumeric(byte[] bytes, CodePage codePage) {
        return fromAlphanumeric(bytes, codePage.charset());
    }

    /** 英数字のバイト列を、指定した文字コードの文字として読み、国字にする。 */
    public static byte[] fromAlphanumeric(byte[] bytes, Charset charset) {
        try {
            CharBuffer chars = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString().getBytes(StandardCharsets.UTF_16BE);
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("the alphanumeric data cannot be converted to"
                    + " national characters from " + charset.name(), failure);
        }
    }

    /** 国字を英数字のバイト列にする。表せない文字は断る。 */
    public static byte[] toAlphanumeric(byte[] national, Charset charset) {
        String text = new String(national, StandardCharsets.UTF_16BE);
        try {
            ByteBuffer encoded = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] out = new byte[encoded.remaining()];
            encoded.get(out);
            return out;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("the national data cannot be converted to "
                    + charset.name(), failure);
        }
    }

    /** 英数字を CCSID の文字として読み、国字にする ({@code NATIONAL-OF} の第 2 引数)。 */
    public static byte[] fromCcsid(byte[] bytes, int ccsid) {
        return fromAlphanumeric(bytes, charsetOf(ccsid));
    }

    /** 国字をコードページの英数字にする ({@code DISPLAY-OF})。表せない文字は断る。 */
    public static byte[] toAlphanumeric(byte[] national, CodePage codePage) {
        return toAlphanumeric(national, codePage.charset());
    }

    /** 国字を CCSID の英数字にする ({@code DISPLAY-OF} の第 2 引数)。 */
    public static byte[] toCcsid(byte[] national, int ccsid) {
        return toAlphanumeric(national, charsetOf(ccsid));
    }

    /** 項目に入った CCSID で読む。持っていない CCSID は実行時に断る。 */
    public static byte[] fromCcsid(byte[] bytes, Decimal ccsid) {
        return fromCcsid(bytes, ccsid.toBigDecimal().intValueExact());
    }

    /** 項目に入った CCSID の英数字にする。 */
    public static byte[] toCcsid(byte[] national, Decimal ccsid) {
        return toCcsid(national, ccsid.toBigDecimal().intValueExact());
    }

    /** 国字の文字 (符号単位) の数。{@code FUNCTION LENGTH} が国字に使う。 */
    public static Decimal characters(byte[] national) {
        return Decimal.of(national.length / 2, 0);
    }

    /** {@code DISPLAY} のための英数字。表せない文字はコードページの置換文字にする。 */
    public static byte[] toDisplay(byte[] national, CodePage codePage) {
        return new String(national, StandardCharsets.UTF_16BE).getBytes(codePage.charset());
    }

    /**
     * 国字の転記。左に詰め、余りを国字の空白で埋め、長ければ右を切る。
     * {@code JUSTIFIED} なら右に詰め、左を切る。長さはバイト数であり、偶数である。
     */
    public static void move(byte[] source, Storage storage, int offset, int length,
                            boolean justifiedRight) {
        byte[] out = new byte[length];
        for (int k = 0; k + 1 < length; k += 2) {
            out[k] = 0x00;
            out[k + 1] = 0x20;
        }
        int copy = Math.min(source.length, length) & ~1;
        if (justifiedRight) {
            System.arraycopy(source, source.length - copy, out, length - copy, copy);
        } else {
            System.arraycopy(source, 0, out, 0, copy);
        }
        storage.view(offset, length).setBytes(out);
    }

    /** 1 文字を {@code length} バイトぶん並べる。図形定数を広げるのに使う。 */
    public static byte[] repeat(char unit, int length) {
        byte[] out = new byte[length];
        for (int k = 0; k + 1 < length; k += 2) {
            out[k] = (byte) (unit >> 8);
            out[k + 1] = (byte) unit;
        }
        return out;
    }

    /** 国字の並びを {@code length} バイトぶん繰り返す。{@code ALL N'..'} に使う。 */
    public static byte[] repeat(byte[] unit, int length) {
        byte[] out = new byte[length];
        for (int k = 0; k < length; k++) {
            out[k] = unit[k % unit.length];
        }
        return out;
    }

    /**
     * 国字どうしの比較。短いほうを国字の空白で埋めて、符号単位の値で比べる。
     *
     * @return 負なら左が小さく、0 なら等しく、正なら左が大きい
     */
    public static int compare(byte[] left, byte[] right) {
        int length = Math.max(left.length, right.length);
        for (int k = 0; k < length; k += 2) {
            int a = unit(left, k);
            int b = unit(right, k);
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        return 0;
    }

    private static int unit(byte[] bytes, int at) {
        if (at + 1 >= bytes.length) {
            return SPACE;
        }
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    /**
     * CCSID から文字コードを引く ({@code NATIONAL-OF} と {@code DISPLAY-OF} の 2 つ目の引数)。
     *
     * <p>1390 と 1399 (日本語の拡張) は JDK に無い。近い 930 / 939 で読むと、拡張した文字の
     * 符号位置が違うので断る (暫定判断 P-002)。
     *
     * @throws UnsupportedCharsetException 持っていない CCSID
     */
    public static Charset charsetOf(int ccsid) {
        switch (ccsid) {
            case 1208:
                return StandardCharsets.UTF_8;
            case 1200:
            case 1201:
            case 13488:
                return StandardCharsets.UTF_16BE;
            case 367:
                return StandardCharsets.US_ASCII;
            case 819:
                return StandardCharsets.ISO_8859_1;
            case 1390:
            case 1399:
                throw new UnsupportedCharsetException("CCSID " + ccsid
                        + " is not supported (P-002)");
            default:
                break;
        }
        for (String name : new String[] {"IBM" + pad(ccsid), "x-IBM" + ccsid, "IBM" + ccsid}) {
            if (Charset.isSupported(name)) {
                return Charset.forName(name);
            }
        }
        throw new UnsupportedCharsetException("CCSID " + ccsid + " is not supported");
    }

    private static String pad(int ccsid) {
        return ccsid < 100 ? String.format("%03d", ccsid) : Integer.toString(ccsid);
    }
}
