package dev.cobolonjava.runtime.codepage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 表で引く EBCDIC の混在コードページ (1 バイト文字と、SO / SI で囲んだ 2 バイト文字)。
 *
 * <p>JDK は CCSID 1390 と 1399 (930 と 939 に英小文字・ユーロ記号などを足したもの) を
 * 持っていない。表は ICU の UCM から作ったもので、Zeus (同じ権利者の処理系) の
 * {@code crates/zeus-cobol/codepages} から移した (Unicode License V3、
 * {@code LICENSE-ICU})。行は {@code K EBCDIC UNICODE} で、K は ICU の精度
 * (0 往復、1 EBCDIC への片道、2 置換文字、3 Unicode への片道) である。
 * 読み方は Zeus の {@code src/codepage.rs} と同じにした。
 *
 * <p>表に無い字は書けない (符号化の誤り)。読むときに表に無い並びは置換文字 U+FFFD にする。
 */
final class TableCharset extends Charset {

    private static final byte SO = 0x0E;
    private static final byte SI = 0x0F;

    /** 1 バイト → 字。 */
    private final String[] single = new String[256];
    /** 2 バイト → 字。 */
    private final Map<Integer, String> doubles = new HashMap<>();
    /** 字 → 1 バイト (0〜255) か 2 バイト (0x10000 を足した値)。 */
    private final Map<String, Integer> encode = new HashMap<>();
    /** 2 つの符号位置からなる字 (結合列) の頭。符号化のときに先を見る。 */
    private final Map<Integer, Boolean> combiningHeads = new HashMap<>();

    TableCharset(String name, String sbcsResource, String dbcsResource) {
        super(name, null);
        load(sbcsResource, false);
        load(dbcsResource, true);
    }

    private void load(String resource, boolean dbcs) {
        try (InputStream in = TableCharset.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("code page table is missing: " + resource);
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            Map<String, Integer> fallbacks = new HashMap<>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.trim().split("\\s+");
                int precision = Integer.parseInt(fields[0]);
                int code = Integer.parseInt(fields[1], 16);
                StringBuilder text = new StringBuilder();
                for (String point : fields[2].split("\\+")) {
                    text.appendCodePoint(Integer.parseInt(point, 16));
                }
                String value = text.toString();
                if (value.codePointCount(0, value.length()) > 1) {
                    combiningHeads.put(value.codePointAt(0), true);
                }
                int key = dbcs ? 0x10000 + code : code;
                if (precision == 0 || precision == 3) {
                    if (dbcs) {
                        doubles.putIfAbsent(code, value);
                    } else if (single[code] == null) {
                        single[code] = value;
                    }
                }
                if (precision == 0) {
                    encode.putIfAbsent(value, key);
                } else if (precision == 1) {
                    fallbacks.putIfAbsent(value, key);
                }
            }
            fallbacks.forEach(encode::putIfAbsent);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean contains(Charset other) {
        return other.equals(this);
    }

    @Override
    public CharsetDecoder newDecoder() {
        return new Decoder();
    }

    @Override
    public CharsetEncoder newEncoder() {
        return new Encoder();
    }

    private final class Decoder extends CharsetDecoder {
        private boolean shifted;

        Decoder() {
            super(TableCharset.this, 1f, 2f);
        }

        @Override
        protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
            while (in.hasRemaining()) {
                int mark = in.position();
                byte b = in.get();
                if (b == SO) {
                    shifted = true;
                    continue;
                }
                if (b == SI) {
                    shifted = false;
                    continue;
                }
                String text;
                if (shifted) {
                    if (!in.hasRemaining()) {
                        in.position(mark);
                        return CoderResult.UNDERFLOW;
                    }
                    int code = ((b & 0xFF) << 8) | (in.get() & 0xFF);
                    text = doubles.getOrDefault(code, "�");
                } else {
                    text = single[b & 0xFF] == null ? "�" : single[b & 0xFF];
                }
                if (out.remaining() < text.length()) {
                    in.position(mark);
                    return CoderResult.OVERFLOW;
                }
                out.put(text);
            }
            return CoderResult.UNDERFLOW;
        }

        @Override
        protected void implReset() {
            shifted = false;
        }
    }

    private final class Encoder extends CharsetEncoder {
        private boolean shifted;

        Encoder() {
            super(TableCharset.this, 1.5f, 4f, new byte[] {0x3F});
        }

        @Override
        protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
            while (in.hasRemaining()) {
                int mark = in.position();
                int point = Character.codePointAt(in, 0);
                int width = Character.charCount(point);
                String text = new String(Character.toChars(point));
                if (combiningHeads.containsKey(point) && in.remaining() > width) {
                    int next = Character.codePointAt(in, width);
                    String pair = text + new String(Character.toChars(next));
                    if (encode.containsKey(pair)) {
                        text = pair;
                        width += Character.charCount(next);
                    }
                }
                Integer code = encode.get(text);
                if (code == null) {
                    return CoderResult.unmappableForLength(width);
                }
                boolean dbcs = code >= 0x10000;
                int need = (dbcs ? 2 : 1) + (dbcs != shifted ? 1 : 0);
                if (out.remaining() < need) {
                    in.position(mark);
                    return CoderResult.OVERFLOW;
                }
                if (dbcs != shifted) {
                    out.put(dbcs ? SO : SI);
                    shifted = dbcs;
                }
                if (dbcs) {
                    out.put((byte) (code >> 8));
                    out.put((byte) code.intValue());
                } else {
                    out.put((byte) code.intValue());
                }
                in.position(mark + width);
            }
            return CoderResult.UNDERFLOW;
        }

        @Override
        protected CoderResult implFlush(ByteBuffer out) {
            if (shifted) {
                if (!out.hasRemaining()) {
                    return CoderResult.OVERFLOW;
                }
                out.put(SI);
                shifted = false;
            }
            return CoderResult.UNDERFLOW;
        }

        @Override
        protected void implReset() {
            shifted = false;
        }
    }
}
