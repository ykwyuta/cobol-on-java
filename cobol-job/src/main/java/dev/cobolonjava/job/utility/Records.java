package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.file.RecordFraming;
import java.io.ByteArrayOutputStream;
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
        return RecordFraming.split(bytes, attributes, true).records();
    }

    /**
     * バイト列が様式どおりに切れるか (要件 FR-141)。
     *
     * <p>切れないものを黙って写すと、<b>壊れたデータセットを写して正常終了する</b>。
     * 切り方と同じところで決めるので、順編成のデータセットと同じ形が壊れているになる
     * (暫定判断 P-053)。
     */
    static boolean damaged(byte[] bytes, DataSetAttributes attributes) {
        return RecordFraming.split(bytes, attributes, true).damaged();
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

}
