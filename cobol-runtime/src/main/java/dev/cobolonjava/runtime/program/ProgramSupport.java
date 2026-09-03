package dev.cobolonjava.runtime.program;

import java.nio.charset.StandardCharsets;

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
}
