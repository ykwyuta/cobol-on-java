package dev.cobolonjava.runtime.program;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 生成コードの下支え。 */
public final class ProgramSupport {

    private ProgramSupport() {
    }

    /**
     * 翻訳時に決まったバイト列を、クラスファイルの文字列定数から復元する。
     *
     * <p>ISO-8859-1 は 0〜255 をそのまま 1 バイトへ写すため、<b>任意のバイト列を
     * 文字列定数 1 個で運べる</b>。初期イメージや文字定数をバイトごとに組み立てる
     * バイトコードを吐かずに済む。
     */
    public static byte[] bytes(String latin1) {
        return latin1.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * COBOL のプログラム名から Java のクラス名を作る。
     *
     * <p>翻訳して名前を付けるのはコンパイラだが、{@code CALL} で名前から探すのは
     * ランタイムである。<b>両者がずれれば呼び先が見つからない</b>ため、規則は
     * ここ 1 か所に置く。
     *
     * <p>Java の識別子に使えない文字は下線に読み替える。COBOL のプログラム名は
     * 大文字と小文字を区別しないので、大文字へ揃える。
     */
    public static String classNameOf(String programName) {
        String upper = programName.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return GENERATED_PACKAGE + "." + sb;
    }

    /** 生成したクラスを置くパッケージ。 */
    public static final String GENERATED_PACKAGE = "cobol.generated";
}
