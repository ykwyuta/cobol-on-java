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
}
