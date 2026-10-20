package dev.cobolonjava.cics.tn3270;

import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot.FieldState;
import dev.cobolonjava.runtime.codepage.CodePage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 3270 データストリームの組み立てと分解 (設計 86 §4)。
 *
 * <p>値は 3270 データストリームの公開仕様 (コマンド、WCC、order、AID、buffer address の符号) による。
 * 画面は中立な {@link CicsTerminalScreen} から作り、端末から届いた Read Modified は buffer address と
 * byte 列の組へ分ける。field の名前との照合は {@link Tn3270Server} が直前の画面を使って行う。
 */
public final class Tn3270DataStream {

    /** Erase/Write。画面を既定の大きさ (24 x 80) にして消す。 */
    static final int ERASE_WRITE = 0xF5;
    /** Erase/Write Alternate。画面を代替の大きさにして消す。 */
    static final int ERASE_WRITE_ALTERNATE = 0x7E;
    /** Write。画面を消さずに書く。WCC だけを送ってキーボードを開けるのに使う。 */
    static final int WRITE = 0xF1;

    static final int ORDER_SBA = 0x11;
    static final int ORDER_IC = 0x13;
    static final int ORDER_SF = 0x1D;
    static final int ORDER_SFE = 0x29;

    private static final int WCC_RESET_MDT = 0x01;
    private static final int WCC_RESTORE = 0x02;
    private static final int WCC_ALARM = 0x04;

    private static final int ATTR_PROTECTED = 0x20;
    private static final int ATTR_NUMERIC = 0x10;
    private static final int ATTR_BRIGHT = 0x08;
    private static final int ATTR_DARK = 0x0C;
    private static final int ATTR_MDT = 0x01;

    private static final int EXT_BASIC = 0xC0;
    private static final int EXT_HIGHLIGHT = 0x41;
    private static final int EXT_COLOR = 0x42;

    /**
     * 6 bit の値を印字可能な byte にする表。12 bit の buffer address、WCC、属性 byte が使う。
     * 公開仕様の「6 bit の値と EBCDIC の図形文字の対応」をそのまま並べたもの。
     */
    private static final int[] CODES = {
        0x40, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F,
        0x50, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F,
        0x60, 0x61, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F,
        0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0x7A, 0x7B, 0x7C, 0x7D, 0x7E, 0x7F,
    };

    /** 端末から届いた field 1 つ分。{@code address} は SBA が指したデータの先頭 (属性 byte の次)。 */
    public record InboundField(int address, byte[] data) {

        public InboundField {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    /**
     * 端末から届いた入力。
     *
     * @param aid         AID の byte。{@link BmsAid} に無い値もそのまま持つ
     * @param cursor      cursor の buffer address。short read (CLEAR、PA) では -1
     * @param fields      SBA で区切られた field。書式なしの画面では address が -1 の 1 つになる
     */
    public record Inbound(int aid, int cursor, List<InboundField> fields) {

        public Inbound {
            fields = List.copyOf(fields);
        }

        public Optional<BmsAid> bmsAid() {
            return BmsAid.ofValue(aid);
        }
    }

    private Tn3270DataStream() {
    }

    /**
     * 画面 1 枚を Erase/Write (または Erase/Write Alternate) にする。
     *
     * @param size     端末の画面の大きさ
     * @param extended 拡張属性 (色、強調) を SFE で送ってよいか
     * @throws IllegalArgumentException 画面が端末の既定の大きさにも代替の大きさにも収まらない
     */
    public static byte[] screen(CicsTerminalScreen screen, Tn3270Device size, boolean extended, CodePage codePage) {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(codePage, "codePage");
        if (screen instanceof CicsTerminalScreen.MapScreen map) {
            return map(map.snapshot(), size, extended, codePage);
        }
        CicsTerminalScreen.TextScreen text = (CicsTerminalScreen.TextScreen) screen;
        return text(text.text(), text.keyboardRestored(), text.alarm(), size, codePage);
    }

    /**
     * 書式なしの文を書く。CICS の SEND TEXT と、領域の出す DFHAC の通知がこれを使う。
     *
     * <p>文は buffer の先頭から並べ、画面に入りきらないぶんは落とす。3270 は buffer の終わりで先頭へ
     * 折り返すので、溢れたまま送ると先頭の文字を上書きする。
     */
    public static byte[] text(String text, boolean keyboardRestored, boolean alarm, Tn3270Device size,
                              CodePage codePage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ERASE_WRITE);
        out.write(wcc(keyboardRestored, alarm));
        byte[] data = codePage.encode(dev.cobolonjava.cics.bms.BmsFieldText.truncate(
                text, size.defaultRows() * size.defaultColumns(), codePage));
        if (data.length > 0) {
            out.write(ORDER_SBA);
            writeAddress(out, 0, size.defaultRows() * size.defaultColumns());
            out.writeBytes(data);
        }
        return out.toByteArray();
    }

    /** 画面を変えずに WCC だけを送る。キーボードを開け、警告音を鳴らすのに使う。 */
    public static byte[] restoreKeyboard(boolean alarm) {
        return new byte[] {(byte) WRITE, (byte) wcc(true, alarm)};
    }

    private static byte[] map(BmsScreenSnapshot snapshot, Tn3270Device device, boolean extended,
                              CodePage codePage) {
        boolean alternate;
        int rows;
        int columns;
        if (snapshot.rows() <= device.defaultRows() && snapshot.columns() <= device.defaultColumns()) {
            alternate = false;
            rows = device.defaultRows();
            columns = device.defaultColumns();
        } else if (snapshot.rows() <= device.alternateRows() && snapshot.columns() <= device.alternateColumns()) {
            alternate = true;
            rows = device.alternateRows();
            columns = device.alternateColumns();
        } else {
            throw new IllegalArgumentException("map " + snapshot.map() + " (" + snapshot.rows() + " x "
                    + snapshot.columns() + ") does not fit the terminal " + device.name());
        }
        int bufferSize = rows * columns;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(alternate ? ERASE_WRITE_ALTERNATE : ERASE_WRITE);
        out.write(wcc(snapshot.keyboardRestored(), snapshot.alarm()));
        for (FieldState field : snapshot.fields()) {
            int attribute = attributeAddress(field, columns);
            out.write(ORDER_SBA);
            writeAddress(out, attribute, bufferSize);
            int basic = attributeByte(field.attributes(), field.modified()
                    || field.attributes().contains(BasicAttribute.FSET));
            List<int[]> pairs = new ArrayList<>();
            if (extended) {
                field.highlight().ifPresent(value -> pairs.add(new int[] {EXT_HIGHLIGHT, highlight(value)}));
                field.color().ifPresent(value -> pairs.add(new int[] {EXT_COLOR, color(value)}));
            }
            if (pairs.isEmpty()) {
                out.write(ORDER_SF);
                out.write(basic);
            } else {
                out.write(ORDER_SFE);
                out.write(pairs.size() + 1);
                out.write(EXT_BASIC);
                out.write(basic);
                for (int[] pair : pairs) {
                    out.write(pair[0]);
                    out.write(pair[1]);
                }
            }
            // 消した画面は null で埋まっている。データの無い位置を null のまま残すのは BMS と同じで、
            // 端末は null を送り返さないので、入力した値の後ろに空白が付かない
            out.writeBytes(codePage.encode(field.data()));
        }
        if (snapshot.cursorOffset() >= 0) {
            int cursor = snapshot.cursorOffset() / snapshot.columns() * columns
                    + snapshot.cursorOffset() % snapshot.columns();
            out.write(ORDER_SBA);
            writeAddress(out, cursor, bufferSize);
            out.write(ORDER_IC);
        }
        return out.toByteArray();
    }

    /** 端末の buffer の上での field の属性 byte の位置。map の行・桁を端末の桁数で並べ直す。 */
    static int attributeAddress(FieldState field, int columns) {
        return (field.position().row() - 1) * columns + field.position().column() - 1;
    }

    static int wcc(boolean keyboardRestored, boolean alarm) {
        int bits = WCC_RESET_MDT | (keyboardRestored ? WCC_RESTORE : 0) | (alarm ? WCC_ALARM : 0);
        return CODES[bits];
    }

    /** 基本属性 byte。ASKIP は保護と数字を合わせたものである。 */
    static int attributeByte(Set<BasicAttribute> attributes, boolean mdt) {
        int bits = 0;
        if (attributes.contains(BasicAttribute.ASKIP)) {
            bits |= ATTR_PROTECTED | ATTR_NUMERIC;
        } else {
            if (attributes.contains(BasicAttribute.PROT)) {
                bits |= ATTR_PROTECTED;
            }
            if (attributes.contains(BasicAttribute.NUM)) {
                bits |= ATTR_NUMERIC;
            }
        }
        if (attributes.contains(BasicAttribute.DRK)) {
            bits |= ATTR_DARK;
        } else if (attributes.contains(BasicAttribute.BRT)) {
            bits |= ATTR_BRIGHT;
        }
        if (mdt) {
            bits |= ATTR_MDT;
        }
        return CODES[bits];
    }

    private static int color(Color color) {
        return switch (color) {
            case DEFAULT -> 0x00;
            case BLUE -> 0xF1;
            case RED -> 0xF2;
            case PINK -> 0xF3;
            case GREEN -> 0xF4;
            case TURQUOISE -> 0xF5;
            case YELLOW -> 0xF6;
            case NEUTRAL -> 0xF7;
        };
    }

    private static int highlight(Highlight highlight) {
        return switch (highlight) {
            case OFF -> 0xF0;
            case BLINK -> 0xF1;
            case REVERSE -> 0xF2;
            case UNDERLINE -> 0xF4;
        };
    }

    /**
     * buffer address を書く。4096 未満の画面は 12 bit (6 bit を 2 つ、{@link #CODES} で印字可能な byte に)、
     * それ以上は 14 bit の 2 進で書く。
     */
    static void writeAddress(ByteArrayOutputStream out, int address, int bufferSize) {
        if (address < 0 || address >= bufferSize) {
            throw new IllegalArgumentException("buffer address " + address + " is outside the screen");
        }
        if (bufferSize <= 4096) {
            out.write(CODES[(address >> 6) & 0x3F]);
            out.write(CODES[address & 0x3F]);
        } else {
            out.write((address >> 8) & 0x3F);
            out.write(address & 0xFF);
        }
    }

    /** buffer address を読む。上 2 bit が 0 なら 14 bit、そうでなければ 12 bit である。 */
    static int readAddress(int first, int second) {
        if ((first & 0xC0) == 0) {
            return ((first & 0x3F) << 8) | (second & 0xFF);
        }
        return ((first & 0x3F) << 6) | (second & 0x3F);
    }

    /**
     * 端末から届いた Read Modified (または short read) を分ける。
     *
     * <p>AID の次に cursor の address が 2 byte、そのあとは SBA と field のデータが並ぶ。書式なしの画面では
     * SBA が無く、データがそのまま続く。CLEAR と PA は AID だけの short read である。
     */
    public static Inbound inbound(byte[] record) {
        Objects.requireNonNull(record, "record");
        if (record.length == 0) {
            throw new IllegalArgumentException("an inbound 3270 record must carry an AID");
        }
        int aid = record[0] & 0xFF;
        if (record.length < 3) {
            return new Inbound(aid, -1, List.of());
        }
        int cursor = readAddress(record[1] & 0xFF, record[2] & 0xFF);
        List<InboundField> fields = new ArrayList<>();
        int address = -1;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean open = false;
        for (int i = 3; i < record.length; i++) {
            int b = record[i] & 0xFF;
            if (b == ORDER_SBA) {
                if (i + 2 >= record.length) {
                    throw new IllegalArgumentException("SBA at the end of the inbound record");
                }
                if (open) {
                    fields.add(new InboundField(address, data.toByteArray()));
                }
                address = readAddress(record[i + 1] & 0xFF, record[i + 2] & 0xFF);
                data.reset();
                open = true;
                i += 2;
            } else {
                // 書式なしの画面のデータは SBA を持たない
                open = true;
                data.write(b);
            }
        }
        if (open) {
            fields.add(new InboundField(address, data.toByteArray()));
        }
        return new Inbound(aid, cursor, fields);
    }
}
