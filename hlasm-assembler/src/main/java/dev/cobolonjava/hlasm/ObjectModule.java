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
 * @param relocations  載せたときに番地を足す場所 (オブジェクトモジュールの RLD に当たる)
 */
public record ObjectModule(String name, byte[] text, int entryOffset,
                           Map<String, Symbol> symbols, Map<String, Integer> dummySections,
                           List<Line> listing, List<Relocation> relocations) {

    /**
     * 載せる側が直す番地定数 1 つ。
     *
     * <p>{@code A(記号)} は節の先頭からの変位で組み立ててある。番地になるのは、載せる側が節を置いた
     * 番地を足したときである。{@code V(名前)} は外部の名前を指し、組み立てた値は 0 である。
     * 載せる側が名前を解決して、その入口の番地を足す。
     *
     * @param offset   節の先頭からの変位
     * @param length   定数の長さ (3 か 4)
     * @param external 外部の名前。自分の節を指すなら {@code null}
     */
    public record Relocation(int offset, int length, String external) {
    }

    /** 番地定数を持たない組み立て結果。 */
    public ObjectModule(String name, byte[] text, int entryOffset, Map<String, Symbol> symbols,
                        Map<String, Integer> dummySections, List<Line> listing) {
        this(name, text, entryOffset, symbols, dummySections, listing, List.of());
    }

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
        relocations = List.copyOf(relocations);
    }

    /** 外部の名前の一覧 (重なりなし、現れた順)。 */
    public List<String> externals() {
        return relocations.stream().map(Relocation::external).filter(java.util.Objects::nonNull)
                .distinct().toList();
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
