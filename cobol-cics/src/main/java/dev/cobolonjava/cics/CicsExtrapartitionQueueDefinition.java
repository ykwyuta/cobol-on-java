package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 区画外 (extrapartition) の一時データのキューの定義 (設計 85 §7.1、暫定判断 P-148)。
 *
 * <p>キューはバッチの順編成のデータセットであり、ジョブがそのまま読み書きできる。属性の意味は CICS TS の TDQUEUE の定義の
 * TYPE(EXTRA)、TYPEFILE、RECORDFORMAT、RECORDSIZE による。
 *
 * @param name         {@code QUEUE('名前')} で指す 1〜4 文字の名前
 * @param path         データセット
 * @param direction    TYPEFILE。INPUT のキューは READQ TD だけ、OUTPUT のキューは WRITEQ TD だけを受ける
 * @param recordLength 固定長ならその長さ、可変長なら最大の長さ
 * @param variable     可変長か
 * @param codePage     データセットの属性に書く code page
 */
public record CicsExtrapartitionQueueDefinition(String name, Path path, Direction direction, int recordLength,
                                                boolean variable, CodePage codePage) {

    /** TYPEFILE。RDBACK は持たない。 */
    public enum Direction {
        INPUT,
        OUTPUT
    }

    private static final Pattern NAME = Pattern.compile("[A-Z0-9@#$]{1,4}");

    public CicsExtrapartitionQueueDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(codePage, "codePage");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("transient data queue name must be 1 to 4 characters: " + name);
        }
        if (recordLength < 1 || recordLength > 32767) {
            throw new IllegalArgumentException("record length must be 1 to 32767: " + name);
        }
    }
}
