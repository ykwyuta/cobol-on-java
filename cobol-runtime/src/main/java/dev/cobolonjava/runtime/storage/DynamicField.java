package dev.cobolonjava.runtime.storage;

import dev.cobolonjava.runtime.abend.Abend;
import dev.cobolonjava.runtime.abend.AbendCode;
import dev.cobolonjava.runtime.data.Utf8;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigDecimal;
import java.util.Arrays;

/** Content owned by a DYNAMIC LENGTH item in a program's working storage. */
public final class DynamicField {
    private byte[] buffer = new byte[0];
    private int length;

    DynamicField() {
    }

    public static DynamicField at(Storage storage, int offset) {
        return storage.dynamicField(offset);
    }

    public synchronized byte[] read() {
        return Arrays.copyOf(buffer, length);
    }

    public synchronized byte[] read(int start, int count, boolean toEnd) {
        int size = sliceLength(start, count, toEnd);
        return Arrays.copyOfRange(buffer, start - 1, start - 1 + size);
    }

    public synchronized Decimal length() {
        return Decimal.of(length, 0);
    }

    public synchronized Decimal length(int start, int count, boolean toEnd) {
        return Decimal.of(sliceLength(start, count, toEnd), 0);
    }

    public synchronized void resize(Decimal requested, int limit) {
        int size = requested.toBigDecimal().max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(limit)).intValue();
        ensureCapacity(size);
        length = size;
    }

    public synchronized void move(byte[] source, int limit, boolean utf8, boolean repeat,
                                  boolean highUtf8) {
        int size = repeat ? Math.min(limit, Math.max(length, 1))
                : utf8 ? Utf8.prefixLength(source, limit) : Math.min(source.length, limit);
        ensureCapacity(size);
        if (repeat && utf8) {
            byte[] bytes = highUtf8 ? Utf8.high(size, 0) : Utf8.fit(source, size, 0, true);
            System.arraycopy(bytes, 0, buffer, 0, size);
        } else {
            write(source, 0, size, repeat, (byte) 0);
        }
        length = size;
    }

    public synchronized void move(byte[] source, int start, int count, boolean toEnd,
                                  boolean repeat, byte space) {
        write(source, start - 1, sliceLength(start, count, toEnd), repeat, space);
    }

    public static byte[] comparisonPattern(int length, byte[] pattern, boolean utf8,
                                           boolean highUtf8, boolean figurative) {
        if (utf8) {
            int size = Math.max(1, length);
            return highUtf8 ? Utf8.high(size, 0) : Utf8.fit(pattern, size, 0, true);
        }
        if (length == 0) return figurative ? Arrays.copyOf(pattern, 1) : pattern;
        byte[] result = new byte[length];
        if (pattern.length != 0) {
            for (int i = 0; i < length; i++) result[i] = pattern[i % pattern.length];
        }
        return result;
    }

    private int sliceLength(int start, int count, boolean toEnd) {
        if (start >= 1 && start <= length) {
            int remaining = length - start + 1;
            if (toEnd) return remaining;
            if (count > 0 && count <= remaining) return count;
        }
        throw new Abend(AbendCode.S0C4, "dynamic-length reference modification outside current storage");
    }

    private void ensureCapacity(int size) {
        if (size > buffer.length) buffer = Arrays.copyOf(buffer, size);
    }

    private void write(byte[] source, int offset, int size, boolean repeat, byte space) {
        if (repeat && source.length != 0) {
            for (int i = 0; i < size; i++) buffer[offset + i] = source[i % source.length];
        } else {
            int copied = Math.min(size, source.length);
            System.arraycopy(source, 0, buffer, offset, copied);
            Arrays.fill(buffer, offset + copied, offset + size, space);
        }
    }
}
