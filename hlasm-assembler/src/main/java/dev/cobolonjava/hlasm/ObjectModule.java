package dev.cobolonjava.hlasm;

import java.util.List;
import java.util.Map;

/**
 * 組み立てた制御節 1 つ。
 *
 * <p>番地は節の先頭からの変位である。実際にどの番地へ置くかは載せる側が決める。
 * これは HLASM のオブジェクトモジュールが再配置可能であることに対応する。
 *
 * @param name         制御節の名前
 * @param text         組み立てたバイト列
 * @param entryOffset  実行を始める変位 ({@code END} の演算項、書かれていなければ 0)
 * @param symbols      定義された記号
 * @param dummySections ダミー節 ({@code DSECT}) の長さ
 * @param listing      1 文ごとの変位と機械語。組み立てそのものを突き合わせるために持つ
 */
public record ObjectModule(String name, byte[] text, int entryOffset,
                           Map<String, Symbol> symbols, Map<String, Integer> dummySections,
                           List<Line> listing) {

    /**
     * 組み立て表の 1 行。
     *
     * @param line   原文の行 (1 起点)
     * @param offset 制御節の先頭からの変位
     * @param bytes  組み立てた機械語。場所だけを取った文 ({@code DS}) では空
     */
    public record Line(int line, int offset, byte[] bytes) {

        public Line {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public String hex() {
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) {
                out.append(String.format("%02X", b));
            }
            return out.toString();
        }
    }

    public ObjectModule {
        text = text.clone();
        symbols = Map.copyOf(symbols);
        dummySections = Map.copyOf(dummySections);
        listing = List.copyOf(listing);
    }

    @Override
    public byte[] text() {
        return text.clone();
    }

    public int length() {
        return text.length;
    }

    /** 変位から 16 進で読む。突き合わせのためにある。 */
    public String hex(int offset, int length) {
        StringBuilder out = new StringBuilder();
        for (int k = offset; k < offset + length; k++) {
            out.append(String.format("%02X", text[k]));
        }
        return out.toString();
    }
}
