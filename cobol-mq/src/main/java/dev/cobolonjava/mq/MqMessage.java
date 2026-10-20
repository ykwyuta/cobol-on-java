package dev.cobolonjava.mq;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.Arrays;
import java.util.Objects;

/**
 * 待ち行列に置かれた電文 1 つ: 電文記述子 (MQMD 2 版、364 byte、z/OS の形) と電文の中身。
 *
 * <p>記述子をプログラムが見る byte 列のまま持つのは、MQGET がそれをそのまま返すからである。欄ごとに Java の値へ
 * 開いて持つと、プログラムが置いた欄 (ApplIdentityData など) を 1 つでも落とせば返す byte 列が変わる。
 * 中身もプログラムの code page の byte 列のままで、変換しない (暫定判断 P-252)。
 */
public final class MqMessage {

    private final byte[] descriptor;
    private final byte[] data;

    MqMessage(byte[] descriptor, byte[] data) {
        if (descriptor.length != Structure.MD_LENGTH) {
            throw new IllegalArgumentException("a message descriptor is " + Structure.MD_LENGTH + " bytes");
        }
        this.descriptor = descriptor.clone();
        this.data = Objects.requireNonNull(data, "data").clone();
    }

    /** 記述子 (MQMD 2 版) の写し。 */
    public byte[] descriptor() {
        return descriptor.clone();
    }

    /** 中身の写し。 */
    public byte[] data() {
        return data.clone();
    }

    int length() {
        return data.length;
    }

    public byte[] messageId() {
        return Arrays.copyOfRange(descriptor, Structure.MD_MSG_ID, Structure.MD_MSG_ID + Structure.ID_LENGTH);
    }

    public byte[] correlationId() {
        return Arrays.copyOfRange(descriptor, Structure.MD_CORREL_ID, Structure.MD_CORREL_ID + Structure.ID_LENGTH);
    }

    public int messageType() {
        return Structure.readInteger(descriptor, Structure.MD_MSG_TYPE);
    }

    public int priority() {
        return Structure.readInteger(descriptor, Structure.MD_PRIORITY);
    }

    public int persistence() {
        return Structure.readInteger(descriptor, Structure.MD_PERSISTENCE);
    }

    public int expiry() {
        return Structure.readInteger(descriptor, Structure.MD_EXPIRY);
    }

    public int backoutCount() {
        return Structure.readInteger(descriptor, Structure.MD_BACKOUT_COUNT);
    }

    public int codedCharSetId() {
        return Structure.readInteger(descriptor, Structure.MD_CCSID);
    }

    public String format(CodePage codePage) {
        return text(codePage, Structure.MD_FORMAT, 8);
    }

    public String replyToQueue(CodePage codePage) {
        return text(codePage, 100, Structure.NAME_LENGTH);
    }

    public String putDate(CodePage codePage) {
        return text(codePage, Structure.MD_PUT_DATE, 8);
    }

    public String putTime(CodePage codePage) {
        return text(codePage, Structure.MD_PUT_TIME, 8);
    }

    /** 中身を code page の文字として読み、後ろの空白を落とす。 */
    public String text(CodePage codePage) {
        return Structure.stripTrailingBlanks(codePage.decode(data));
    }

    private String text(CodePage codePage, int offset, int length) {
        return Structure.stripTrailingBlanks(codePage.decode(Arrays.copyOfRange(descriptor, offset, offset + length)));
    }

    /** 記述子の写しを書き替えた電文。待ち行列の中の電文は変えない。 */
    MqMessage withInteger(int offset, int value) {
        byte[] d = descriptor.clone();
        Structure.writeInteger(d, offset, value);
        return new MqMessage(d, data);
    }

    /**
     * Java の側から待ち行列へ置く電文を組む。記述子の既定値は写し句 {@code CMQMDV} の初期値と同じで、
     * MsgId は置くときに待ち行列管理者が振る。
     */
    public static Builder builder(CodePage codePage) {
        return new Builder(codePage);
    }

    /** {@link #builder} の組み立て。 */
    public static final class Builder {

        private final CodePage codePage;
        private final byte[] descriptor;
        private byte[] data = new byte[0];

        private Builder(CodePage codePage) {
            this.codePage = Objects.requireNonNull(codePage, "codePage");
            descriptor = defaultDescriptor(codePage);
        }

        public Builder data(byte[] value) {
            data = value.clone();
            return this;
        }

        public Builder text(String value) {
            data = codePage.encode(value);
            return this;
        }

        public Builder format(String value) {
            System.arraycopy(Structure.padded(codePage, value, 8), 0, descriptor, Structure.MD_FORMAT, 8);
            return this;
        }

        public Builder messageType(int value) {
            Structure.writeInteger(descriptor, Structure.MD_MSG_TYPE, value);
            return this;
        }

        public Builder priority(int value) {
            Structure.writeInteger(descriptor, Structure.MD_PRIORITY, value);
            return this;
        }

        public Builder persistence(int value) {
            Structure.writeInteger(descriptor, Structure.MD_PERSISTENCE, value);
            return this;
        }

        public Builder expiry(int tenthsOfSeconds) {
            Structure.writeInteger(descriptor, Structure.MD_EXPIRY, tenthsOfSeconds);
            return this;
        }

        public Builder correlationId(byte[] value) {
            if (value.length != Structure.ID_LENGTH) {
                throw new IllegalArgumentException("CorrelId is 24 bytes");
            }
            System.arraycopy(value, 0, descriptor, Structure.MD_CORREL_ID, Structure.ID_LENGTH);
            return this;
        }

        public Builder replyToQueue(String value) {
            System.arraycopy(Structure.padded(codePage, value, Structure.NAME_LENGTH), 0, descriptor, 100,
                    Structure.NAME_LENGTH);
            return this;
        }

        public MqMessage build() {
            return new MqMessage(descriptor, data);
        }
    }

    /** 写し句 CMQMDV の初期値 (暫定判断 P-250) と同じ記述子。 */
    static byte[] defaultDescriptor(CodePage codePage) {
        byte[] d = new byte[Structure.MD_LENGTH];
        byte space = codePage.space();
        System.arraycopy(Structure.padded(codePage, "MD", 4), 0, d, 0, 4);
        Structure.writeInteger(d, 4, 2);
        Structure.writeInteger(d, Structure.MD_MSG_TYPE, 8);
        Structure.writeInteger(d, Structure.MD_EXPIRY, MqConstants.MQEI_UNLIMITED);
        Structure.writeInteger(d, Structure.MD_ENCODING, MqConstants.MQENC_NATIVE);
        Arrays.fill(d, Structure.MD_FORMAT, Structure.MD_FORMAT + 8, space);
        Structure.writeInteger(d, Structure.MD_PRIORITY, MqConstants.MQPRI_PRIORITY_AS_Q_DEF);
        Structure.writeInteger(d, Structure.MD_PERSISTENCE, MqConstants.MQPER_PERSISTENCE_AS_Q_DEF);
        // ReplyToQ、ReplyToQMgr、UserIdentifier
        Arrays.fill(d, 100, Structure.MD_ACCOUNTING_TOKEN, space);
        // ApplIdentityData
        Arrays.fill(d, Structure.MD_APPL_IDENTITY_DATA, Structure.MD_PUT_APPL_TYPE, space);
        // PutApplName、PutDate、PutTime、ApplOriginData
        Arrays.fill(d, Structure.MD_PUT_APPL_NAME, Structure.MD_GROUP_ID, space);
        Structure.writeInteger(d, Structure.MD_MSG_SEQ_NUMBER, 1);
        Structure.writeInteger(d, Structure.MD_ORIGINAL_LENGTH, -1);
        return d;
    }
}
