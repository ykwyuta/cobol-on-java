package dev.cobolonjava.compiler.source;

/**
 * 解決されたコピー句。
 *
 * @param fileName 診断で示すファイル名。展開後もこの名前で位置を報告する
 * @param text     コピー句の内容 (固定形式の生テキスト)
 */
public record CopyBook(String fileName, String text) {
}
