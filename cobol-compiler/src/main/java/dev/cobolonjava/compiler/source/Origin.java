package dev.cobolonjava.compiler.source;

/**
 * 正規化後の文字が、元のソースのどこから来たかを表す (要件 FR-094)。
 *
 * <p>プリプロセッサは固定形式のカラムを取り除き、継続行を連結し、{@code COPY} を展開する。
 * その結果、正規化後のテキストは元のソースとは似ても似つかない形になる。
 * 診断メッセージが「どのファイルの何行目の何桁目か」を指せなければ、要件 FR-183 が求める
 * 診断の品質は成立しない。したがって<b>1 文字ごとに出自を保持する</b>。
 *
 * @param fileName ファイル名
 * @param line     行番号 (1 起点)
 * @param column   桁 (1 起点)
 */
public record Origin(String fileName, int line, int column) {

    @Override
    public String toString() {
        return fileName + ":" + line + ":" + column;
    }
}
