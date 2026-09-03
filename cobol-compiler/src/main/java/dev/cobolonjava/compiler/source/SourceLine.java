package dev.cobolonjava.compiler.source;

/**
 * 固定形式のソース 1 行をカラムで分解したもの (要件 FR-002)。
 *
 * <pre>
 * 1-6   一連番号領域
 * 7     標識領域
 * 8-11  A 領域
 * 12-72 B 領域
 * 73-80 識別領域
 * </pre>
 *
 * <p>本文 (8〜72 桁) は {@link #content()} として取り出す。物理行が 72 桁より短い場合、
 * 内容もそのぶん短くなる。<b>72 桁まで空白で埋めるかどうかは文脈による</b>ため、
 * ここでは埋めない ({@link #contentPaddedToMargin()} を参照)。
 *
 * @param fileName       ファイル名
 * @param lineNumber     行番号 (1 起点)
 * @param raw            物理行の文字列
 * @param indicator      標識領域の種別
 * @param content        本文 (8〜72 桁)
 */
public record SourceLine(String fileName, int lineNumber, String raw,
                         LineIndicator indicator, String content) {

    /** 本文の開始桁 (1 起点)。 */
    public static final int CONTENT_START_COLUMN = 8;
    /** 本文の終了桁 (1 起点、この桁を含む)。 */
    public static final int MARGIN_COLUMN = 72;
    /** 標識領域の桁 (1 起点)。 */
    public static final int INDICATOR_COLUMN = 7;

    /**
     * 本文を 72 桁まで空白で埋めたもの。
     *
     * <p>文字定数を継続する場合、継続される側の行は<b>72 桁まで定数の一部</b>とみなされる。
     * 物理行が短くても空白で埋めた扱いになるため、末尾の空白を落としてはならない。
     * この規則を落とすと、行末の空白を削ったソースと削っていないソースで定数の長さが変わる。
     */
    public String contentPaddedToMargin() {
        int width = MARGIN_COLUMN - CONTENT_START_COLUMN + 1;
        if (content.length() >= width) {
            return content;
        }
        return content + " ".repeat(width - content.length());
    }

    /** 本文中の位置 (0 起点) を、物理行の桁 (1 起点) へ変換する。 */
    public int columnOf(int contentIndex) {
        return CONTENT_START_COLUMN + contentIndex;
    }
}
