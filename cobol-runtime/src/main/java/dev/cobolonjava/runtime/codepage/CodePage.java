package dev.cobolonjava.runtime.codepage;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * 実行時の文字コード。要件 FR-050 に従い、既定は EBCDIC である。
 *
 * <p>文字列を Java の {@code String} (UTF-16) として保持せずバイト列として扱うのは、
 * 照合順序 (要件 FR-053: EBCDIC では英字 &lt; 数字であり、ASCII とは逆) と
 * ゾーン10進数のゾーンニブルが、いずれもコードページに直接依存するためである。
 */
public final class CodePage {

    private final String name;
    private final Charset charset;
    private final int zoneNibble;
    private final byte space;

    CodePage(String name, Charset charset, int zoneNibble) {
        this.name = Objects.requireNonNull(name, "name");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.zoneNibble = zoneNibble;
        this.space = " ".getBytes(charset)[0];
    }

    public String name() {
        return name;
    }

    public Charset charset() {
        return charset;
    }

    /**
     * 数字の既定のゾーンニブル。EBCDIC 系では {@code 0xF}、ASCII 系では {@code 0x3}。
     * ゾーン10進数 (USAGE DISPLAY の数値項目) の符号なし表現に用いる。
     */
    public int zoneNibble() {
        return zoneNibble;
    }

    public byte space() {
        return space;
    }

    /** 数字 {@code 0}〜{@code 9} に対応する 1 バイト表現。 */
    public byte digit(int value) {
        if (value < 0 || value > 9) {
            throw new IllegalArgumentException("digit out of range: " + value);
        }
        return (byte) ((zoneNibble << 4) | value);
    }

    /** 指定した ASCII 文字に対応するこのコードページ上の 1 バイト表現。 */
    public byte ch(char c) {
        byte[] b = String.valueOf(c).getBytes(charset);
        if (b.length != 1) {
            throw new IllegalArgumentException("character '" + c + "' is not single-byte in " + name);
        }
        return b[0];
    }

    public byte[] encode(String s) {
        return s.getBytes(charset);
    }

    public String decode(byte[] bytes) {
        return new String(bytes, charset);
    }

    /**
     * このコードページの照合順序による比較。バイトを符号なしとして比較する。
     * EBCDIC のバイト値の並びがそのまま照合順序になる (要件 FR-053)。
     */
    public int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        // 短いほうは空白で埋められたものとして比較する (COBOL の英数字比較の規則)
        for (int i = n; i < a.length; i++) {
            int d = (a[i] & 0xFF) - (space & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        for (int i = n; i < b.length; i++) {
            int d = (space & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return name;
    }
}
