package dev.cobolonjava.ims.jms;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 電文のセグメントと、キューを流れるバイト列の相互変換 (ADR-0014、暫定判断 P-162)。
 *
 * <p>ホストのキューと同じく、セグメントを {@code LL}(2 byte、LL と ZZ を含む長さ) と {@code ZZ}(2 byte) の
 * 前置きを付けて並べる。プログラムが I/O 域で見る形と同じなので、キューの中身をそのまま渡せる。
 * {@code ZZ} は読むときに捨てる (I/O PCB が渡すときに 0 を置く)。
 */
public final class MessageSegments {

    /** LL と ZZ の長さ。 */
    static final int PREFIX = 4;

    private MessageSegments() {
    }

    /** セグメントの本体を LL / ZZ 付きで並べる。 */
    public static byte[] encode(List<byte[]> segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] segment : segments) {
            int length = segment.length + PREFIX;
            if (length > 0xFFFF) {
                throw new IllegalArgumentException("a message segment is longer than 65535 bytes");
            }
            out.write(length >>> 8);
            out.write(length);
            out.write(0);
            out.write(0);
            out.writeBytes(segment);
        }
        return out.toByteArray();
    }

    /**
     * LL / ZZ 付きで並んだバイト列をセグメントの本体に戻す。
     *
     * @throws IllegalArgumentException 長さが合わないとき。黙って途中まで読むと、電文が部分的に正しく見える壊れ方をする
     */
    public static List<byte[]> decode(byte[] bytes) {
        List<byte[]> segments = new ArrayList<>();
        int at = 0;
        while (at < bytes.length) {
            if (at + PREFIX > bytes.length) {
                throw new IllegalArgumentException("a message segment prefix is cut off at byte " + at);
            }
            int length = ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
            if (length < PREFIX || at + length > bytes.length) {
                throw new IllegalArgumentException("a message segment of " + length + " bytes does not fit at byte "
                        + at + " of " + bytes.length);
            }
            byte[] segment = new byte[length - PREFIX];
            System.arraycopy(bytes, at + PREFIX, segment, 0, segment.length);
            segments.add(segment);
            at += length;
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("a message has no segment");
        }
        return segments;
    }
}
