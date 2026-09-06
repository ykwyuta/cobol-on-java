package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.RecordFormat;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * データセットとレコードの並びの行き来 (要件 FR-137)。
 *
 * <p>どこでレコードが切れるかは {@link DataSetAttributes} が決める。バイト列の中には
 * 書かれていない。整列の道具はどれもこの切り方を使うので、<b>1 か所に置く</b>。
 */
final class Records {

    private Records() {
    }

    /**
     * バイト列をレコードへ切る。
     *
     * <p>可変長では長さの 4 バイト (RDW) を<b>レコードに含めたまま</b>持つ。制御文の位置は
     * それを数に入れるからである。切り離してから数え直すと、実資産の
     * {@code SORT FIELDS=(5,...)} が 1 つずつずれる。
     */
    static List<byte[]> split(byte[] bytes, DataSetAttributes attributes) {
        List<byte[]> out = new ArrayList<>();
        switch (attributes.format()) {
            case FIXED -> {
                int length = Math.max(attributes.recordLength(), 1);
                for (int at = 0; at < bytes.length; at += length) {
                    out.add(Arrays.copyOfRange(bytes, at, Math.min(at + length, bytes.length)));
                }
            }
            case VARIABLE -> {
                int at = 0;
                while (at + 4 <= bytes.length) {
                    int length = ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
                    if (length < 4 || at + length > bytes.length) {
                        break;
                    }
                    out.add(Arrays.copyOfRange(bytes, at, at + length));
                    at += length;
                }
            }
            case LINE -> out.addAll(lines(bytes, attributes.codePage()));
            default -> throw new IllegalStateException("unknown format " + attributes.format());
        }
        return out;
    }

    /** 切り出したレコードと、そのバイト列を書き戻すための属性。 */
    record Framed(byte[] bytes, DataSetAttributes attributes) {
    }

    /**
     * レコードの並びをバイト列へ戻す。
     *
     * @param reformatted 組み直したあとか。そのときはレコード長が変わる
     */
    static Framed join(List<byte[]> records, DataSetAttributes attributes, CodePage codePage,
                       boolean reformatted) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int width = 0;
        for (byte[] record : records) {
            width = Math.max(width, record.length);
        }
        return switch (attributes.format()) {
            case FIXED -> {
                int length = reformatted ? width : attributes.recordLength();
                for (byte[] record : records) {
                    buffer.writeBytes(SortField.padded(record, length, codePage.space()));
                }
                yield new Framed(buffer.toByteArray(),
                        new DataSetAttributes(RecordFormat.FIXED, length, codePage));
            }
            case VARIABLE -> {
                for (byte[] record : records) {
                    buffer.writeBytes(record);
                }
                yield new Framed(buffer.toByteArray(), attributes);
            }
            case LINE -> {
                byte newline = codePage.encode("\n")[0];
                for (byte[] record : records) {
                    buffer.writeBytes(record);
                    buffer.write(newline);
                }
                yield new Framed(buffer.toByteArray(),
                        new DataSetAttributes(RecordFormat.LINE, Math.max(width, 1), codePage));
            }
            default -> throw new IllegalStateException("unknown format " + attributes.format());
        };
    }

    /** バイト列を行へ切る。区切りはコードページの改行である。 */
    private static List<byte[]> lines(byte[] bytes, CodePage codePage) {
        byte newline = codePage.encode("\n")[0];
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == newline) {
                out.add(Arrays.copyOfRange(bytes, start, i));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            out.add(Arrays.copyOfRange(bytes, start, bytes.length));
        }
        return out;
    }
}
