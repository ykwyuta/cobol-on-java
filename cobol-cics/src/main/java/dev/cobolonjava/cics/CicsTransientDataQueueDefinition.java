package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 区画内の一時データのキューの定義 (暫定判断 P-137)。
 *
 * @param name            {@code QUEUE('名前')} で指す 1〜4 文字の名前
 * @param maxRecordLength 1 つの record の最大の長さ。越える WRITEQ TD は LENGERR
 */
public record CicsTransientDataQueueDefinition(String name, int maxRecordLength) {

    private static final Pattern NAME = Pattern.compile("[A-Z0-9@#$]{1,4}");

    public CicsTransientDataQueueDefinition {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("transient data queue name must be 1 to 4 characters: " + name);
        }
        if (maxRecordLength < 1 || maxRecordLength > 32767) {
            throw new IllegalArgumentException("record length must be 1 to 32767: " + name);
        }
    }
}
