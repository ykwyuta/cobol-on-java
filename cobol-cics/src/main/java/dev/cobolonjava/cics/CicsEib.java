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

    public static final int EIBTRNID_OFFSET = 0x08;
    public static final int EIBTRNID_LENGTH = 4;
    public static final int EIBCALEN_OFFSET = 0x18;
    public static final int EIBCALEN_LENGTH = 2;
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
