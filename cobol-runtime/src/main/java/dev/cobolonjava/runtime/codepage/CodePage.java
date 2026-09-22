package dev.cobolonjava.runtime.codepage;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
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

    /**
     * このコードページでこの文字列を表せるか。
     *
     * <p>表せない文字がある位置を呼び出し側が診断にしたいときに使う。判定のためだけに
     * {@link #encode} の例外を捕まえなくて済むようにしてある。
     */
    public boolean canEncode(String s) {
        return charset.newEncoder().canEncode(s);
    }

    /**
     * 文字列をこのコードページのバイト列にする。
     *
     * <p><b>表せない文字は断る</b> (要件 FR-181)。{@code String.getBytes(Charset)} の既定は
     * 置換文字へ倒すことであり、IBM-1047 で {@code "山田太郎"} を符号化すると診断なしに
     * {@code X'3F3F3F3F'} になる。翻訳も実行も成功したまま、書かれていた 4 文字だけが
     * 消える。<b>黙って近い値を返すくらいなら断る</b>という方針をここにも通した。
     *
     * @throws UnrepresentableCharacterException 表せない文字があった場合
     */
    public byte[] encode(String s) {
        try {
            ByteBuffer encoded = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(s));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw new UnrepresentableCharacterException(firstUnrepresentable(s), name, failure);
        }
    }

    /**
     * 表せなかった最初の文字。診断に載せるためだけに使う。
     *
     * <p>符号位置の単位で見る。補助面の文字は {@code char} 2 個で 1 文字であり、
     * 片割れだけを引用すると文面が壊れる。
     */
    private String firstUnrepresentable(String s) {
        CharsetEncoder probe = charset.newEncoder();
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            String one = new String(Character.toChars(codePoint));
            if (!probe.canEncode(one)) {
                return one;
            }
            i += Character.charCount(codePoint);
        }
        // 1 文字ずつなら通るのに全体では通らない。連なりの側の誤りなので、全体を引く
        return s;
    }

    /**
     * バイト列をこのコードページの文字として読む。{@code DISPLAY} が外へ出すときに通る。
     *
     * <p><b>符号化と対称ではない</b>。IBM-1047 / IBM-037 は 256 バイトすべてに文字が
     * 当たっているので往復しても崩れない。IBM-930 / IBM-939 は違う。混在コードページでは
     * シフトアウト {@code X'0E'} とシフトイン {@code X'0F'} の間が 2 バイト 1 文字であり、
     * 組が閉じていないバイト列や奇数バイトで切れた {@code PIC X} の内容は読めない。
     *
     * <p>読めないバイトをここで断らないのは、断ると<b>資産が壊した値を見る手段が消える</b>
     * ためである。{@code DISPLAY} は調べるための文であり、内容が壊れていても出せなければ
     * ならない。バイトを持ち回る経路はこの変換を通らない (要件 FR-050)。
     */
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
