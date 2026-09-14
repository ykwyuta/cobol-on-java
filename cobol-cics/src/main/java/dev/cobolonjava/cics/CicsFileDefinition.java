package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * CICS の file 定義 (FILE resource) のうち、固定長の索引編成 (KSDS) を表すもの (暫定判断 P-131)。
 *
 * @param name         {@code FILE('名前')} で指す 1〜8 文字の名前
 * @param path         レコードを置くデータセット。バッチの {@code IndexedDataSet} と同じ形で持つ
 * @param keyOffset    レコードの中の主鍵の位置 (0 起点)
 * @param keyLength    主鍵の長さ
 * @param recordLength 固定長レコードの長さ
 * @param codePage     データセットの属性に書く code page
 */
public record CicsFileDefinition(
        String name, Path path, int keyOffset, int keyLength, int recordLength, CodePage codePage) {

    private static final Pattern NAME = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,7}");

    public CicsFileDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(codePage, "codePage");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("file name must be 1 to 8 characters: " + name);
        }
        if (keyOffset < 0 || keyLength < 1 || recordLength < 1 || keyOffset + keyLength > recordLength) {
            throw new IllegalArgumentException("key must lie within the fixed-length record: " + name);
        }
    }
}
