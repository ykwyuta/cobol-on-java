package dev.cobolonjava.compiler.source;

/**
 * ソースの参照形式を読み、正規化済みソースへ変換するもの (要件 FR-002)。
 *
 * <p>固定形式と自由形式の違いは<b>ここで吸収する</b>。後段 ({@code COPY} の展開、
 * {@code REPLACE}、コンパイラ指示文、トークン化) はどちらの形式で書かれたかを知らない。
 */
public interface SourceReader {

    /**
     * ソースを正規化する。
     *
     * @param fileName 診断で示すファイル名
     * @param source   ソースの全文
     */
    NormalizedSource normalize(String fileName, String source);

    /** この読み取り器が読む参照形式。 */
    SourceFormat format();

    /**
     * デバッグ行を生かす同じ読み取り器 (要件 FR-193)。
     *
     * <p>7 桁目の {@code D} は {@code WITH DEBUGGING MODE} が書かれているときだけ
     * 行として読まれる。書かれていなければ注釈と同じである。自由形式には
     * デバッグ行という概念がないので、既定は<b>そのまま自分を返す</b>。
     */
    default SourceReader withDebuggingMode() {
        return this;
    }
}
