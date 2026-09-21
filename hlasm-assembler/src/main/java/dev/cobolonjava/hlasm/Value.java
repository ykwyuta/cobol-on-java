package dev.cobolonjava.hlasm;

/**
 * 式の値と再配置属性。
 *
 * <p>HLASM の式は「絶対」か「単純再配置可能」のどちらかでなければならない。
 * 後者は制御節の先頭からの変位であり、どの節に属するかを {@code section} が持つ。
 * 節が {@code null} なら絶対値である。
 *
 * <p>節の異なる記号を足したり、再配置可能な値を 2 つ足したりした式は受け取らない。
 * 番地の意味を持たない数を番地として使ってしまうと、組み立ては通るのに実行時に
 * 別の場所を読み書きするためである。
 *
 * @param value   節の先頭からの変位、または絶対値
 * @param section 属する制御節の名前。絶対値なら {@code null}
 */
public record Value(int value, String section) {

    public static Value absolute(int value) {
        return new Value(value, null);
    }

    public static Value in(String section, int value) {
        return new Value(value, section);
    }

    public boolean isAbsolute() {
        return section == null;
    }

    public boolean isRelocatable() {
        return section != null;
    }
}
