package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.Arrays;
import java.util.Objects;

/**
 * 一つのCICS taskに属するEXEC interface block (EIB) の固定長領域。
 *
 * <p>位置と長さはIBMのDFHEIBLKに合わせる。COBOL programには参照専用の暗黙項目として
 * 見せ、値を変更するのはCICS runtimeだけである。未対応fieldはbinary zeroのままにし、
 * hostに存在しない値を推測して設定しない。
 */
public final class CicsEib {

    /** DFHEIBLK全体の長さ。 */
    public static final int SIZE = 0x55;

    public static final int EIBTIME_OFFSET = 0x00;
    public static final int EIBDATE_OFFSET = 0x04;
    public static final int EIBTRNID_OFFSET = 0x08;
    public static final int EIBTRNID_LENGTH = 4;
    public static final int EIBTASKN_OFFSET = 0x0C;
    /** EIBTIME / EIBDATE / EIBTASKN はいずれも PL4 (7桁のpacked decimal) である。 */
    public static final int PACKED_LENGTH = 4;
    public static final int EIBTRMID_OFFSET = 0x10;
    public static final int EIBTRMID_LENGTH = 4;
    public static final int EIBCPOSN_OFFSET = 0x16;
    public static final int EIBCALEN_OFFSET = 0x18;
    public static final int EIBCALEN_LENGTH = 2;
    public static final int EIBAID_OFFSET = 0x1A;
    public static final int EIBFN_OFFSET = 0x1B;
    public static final int EIBFN_LENGTH = 2;
    public static final int EIBRCODE_OFFSET = 0x1D;
    public static final int EIBRCODE_LENGTH = 6;
    public static final int EIBRESP_OFFSET = 0x4C;
    public static final int EIBRESP_LENGTH = 4;
    public static final int EIBRESP2_OFFSET = 0x50;
    public static final int EIBRESP2_LENGTH = 4;

    private final Storage storage = Storage.allocate(SIZE);

    public CicsEib(CicsTaskContext task, int commareaLength, CodePage codePage) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(codePage, "codePage");
        if (commareaLength < 0 || commareaLength > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "EIBCALEN must fit a signed halfword: " + commareaLength);
        }
        byte[] transId = codePage.encode(task.transactionId().value());
        if (transId.length > EIBTRNID_LENGTH) {
            throw new IllegalArgumentException("encoded TRANSID exceeds EIBTRNID");
        }
        byte[] padded = new byte[EIBTRNID_LENGTH];
        Arrays.fill(padded, codePage.space());
        System.arraycopy(transId, 0, padded, 0, transId.length);
        storage.view(EIBTRNID_OFFSET, EIBTRNID_LENGTH).setBytes(padded);
        putHalfword(EIBCALEN_OFFSET, commareaLength);
        // 値の出どころを持たないfieldはbinary zeroのままにする。packed decimalとして
        // 読めない値なので、読んだ文は推測値で進まず失敗する (暫定判断 P-113)
        task.taskNumber().ifPresent(number -> putPacked(EIBTASKN_OFFSET, number));
        task.hostZone().ifPresent(zone -> setDateTime(task.startedAt().atZone(zone).toLocalDateTime()));
        // EIBAID は task を起こした端末入力で決まり、RECEIVE より前から読める
        task.terminalInput().ifPresent(input -> setTerminalInput(
                input.aid().value(), Math.max(input.cursorOffset(), 0)));
    }

    /** 端末入力の AID と cursor 位置を置く。 */
    public void setTerminalInput(int aid, int cursorPosition) {
        if (aid < 0 || aid > 0xFF) {
            throw new IllegalArgumentException("EIBAID must be one byte: " + aid);
        }
        if (cursorPosition < 0 || cursorPosition > Short.MAX_VALUE) {
            throw new IllegalArgumentException("EIBCPOSN must fit a halfword: " + cursorPosition);
        }
        storage.array()[EIBAID_OFFSET] = (byte) aid;
        putHalfword(EIBCPOSN_OFFSET, cursorPosition);
    }

    /**
     * EIBDATE / EIBTIMEを地方時の日時で置き換える。task開始時と{@code ASKTIME}が使う。
     */
    public void setDateTime(java.time.LocalDateTime local) {
        Objects.requireNonNull(local, "local");
        // 0CYYDDD: Cは1900年からの世紀、DDDは年の通日
        int century = (local.getYear() - 1900) / 100;
        if (century < 0 || century > 9) {
            throw new IllegalArgumentException("EIBDATE cannot represent year " + local.getYear());
        }
        putPacked(EIBDATE_OFFSET,
                century * 100_000 + local.getYear() % 100 * 1000 + local.getDayOfYear());
        // 0HHMMSS
        putPacked(EIBTIME_OFFSET,
                local.getHour() * 10_000 + local.getMinute() * 100 + local.getSecond());
    }

    /**
     * 7桁の正の整数をPL4へ置く。
     *
     * <p>符号の半byteは{@code C}とする。hostのEIBが{@code C}と{@code F}のどちらを置くかは
     * 確かめていない。COBOLの読み取りではどちらも正である (暫定判断 P-113)。
     */
    private void putPacked(int offset, int value) {
        if (value < 0 || value > 9_999_999) {
            throw new IllegalArgumentException("PL4 EIB field is out of range: " + value);
        }
        String digits = String.format("%07d", value);
        byte[] bytes = storage.array();
        for (int k = 0; k < PACKED_LENGTH; k++) {
            int high = Character.digit(digits.charAt(k * 2), 10);
            int low = k * 2 + 1 < digits.length() ? Character.digit(digits.charAt(k * 2 + 1), 10) : 0xC;
            bytes[offset + k] = (byte) (high << 4 | low);
        }
    }

    /** 生成COBOLが参照するtask-local領域。 */
    public Storage storage() {
        return storage;
    }

    /** 完了したEXEC CICS commandのRESP / RESP2を即時に反映する。 */
    public void updateResponse(int responseCode, int responseCode2) {
        putFullword(EIBRESP_OFFSET, responseCode);
        putFullword(EIBRESP2_OFFSET, responseCode2);
    }

    /** 完了したcommandのfunction codeと、現在分類できる応答表現を一括反映する。 */
    public void completeCommand(int functionCode, int responseCode, int responseCode2) {
        if (functionCode < 0 || functionCode > 0xFFFF) {
            throw new IllegalArgumentException(
                    "EIBFN must be an unsigned halfword: " + functionCode);
        }
        byte[] response = new byte[EIBRCODE_LENGTH];
        if (responseCode == CicsResponseCode.PGMIDERR) {
            if ((functionCode & 0xFF00) != 0x0E00) {
                throw new IllegalArgumentException(
                        "PGMIDERR EIBRCODE is only classified for program control commands");
            }
            response[0] = 0x01;
        } else if (responseCode == CicsResponseCode.MAPFAIL) {
            // BMS 群の EIBRCODE の byte は公開情報から確定できない。fidelity UNAVAILABLE として
            // binary zero を置く (設計 79 §3.3)。資産は RESP で判定している
            if ((functionCode & 0xFF00) != 0x1800) {
                throw new IllegalArgumentException("MAPFAIL is only classified for BMS commands");
            }
        } else if (responseCode == CicsResponseCode.LENGERR
                || responseCode == CicsResponseCode.CONTAINERERR
                || responseCode == CicsResponseCode.CHANNELERR) {
            // channel 命令の EIBRCODE も公開情報から確定できないので、MAPFAIL と同じく binary zero を置く
            // (暫定判断 P-125)。LENGERR は他の群でも起きるが、分類したのは channel 命令だけである
            if ((functionCode & 0xFF00) != 0x3400) {
                throw new IllegalArgumentException(
                        "RESP=" + responseCode + " EIBRCODE is only classified for channel commands");
            }
        } else if (responseCode != CicsResponseCode.NORMAL) {
            throw new IllegalArgumentException(
                    "EIBRCODE mapping is not defined for RESP=" + responseCode);
        }
        putHalfword(EIBFN_OFFSET, functionCode);
        storage.view(EIBRCODE_OFFSET, EIBRCODE_LENGTH).setBytes(response);
        updateResponse(responseCode, responseCode2);
    }

    private void putHalfword(int offset, int value) {
        byte[] bytes = storage.array();
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private void putFullword(int offset, int value) {
        byte[] bytes = storage.array();
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
