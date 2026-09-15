package dev.cobolonjava.cics;

import dev.cobolonjava.cics.bms.BmsTerminalInput;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 同じ冪等キーの要求が同じ内容かを見分ける要約 (暫定判断 P-142)。
 *
 * <p>TRANSID、会話の ID と版、COMMAREA、channel の名前と container、端末の名前、端末入力 (AID、cursor、field) を
 * SHA-256 でまとめる。owner は冪等キーの置き場の鍵に入るので含めない。
 */
public final class CicsRequestFingerprint {

    private CicsRequestFingerprint() {
    }

    public static String of(CicsTaskRequest request) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
        text(digest, request.transactionId());
        text(digest, request.conversation().map(ref -> ref.id().value() + "#" + ref.expectedVersion()).orElse(""));
        // 続ける会話の COMMAREA と container は会話の版が決める。再送では会話がもう進んでいて payload を読めないので含めない
        if (request.conversation().isEmpty()) {
            bytes(digest, request.payload().commarea());
            text(digest, request.payload().channelName().orElse(""));
            Map<String, byte[]> containers = new TreeMap<>(request.payload().containers());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(containers.size()).array());
            containers.forEach((name, value) -> {
                text(digest, name);
                bytes(digest, value);
            });
        }
        text(digest, request.terminalId().orElse(""));
        if (request.terminalInput().isPresent()) {
            BmsTerminalInput input = request.terminalInput().orElseThrow();
            text(digest, input.aid().name());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(input.cursorOffset()).array());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(input.fields().size()).array());
            for (BmsTerminalInput.FieldInput field : input.fields()) {
                text(digest, field.name() + "#" + field.occurrence());
                text(digest, field.value());
            }
        } else {
            text(digest, "");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 長さを前に置いて、境目のずれで別の要求が同じ要約にならないようにする。 */
    private static void text(MessageDigest digest, String value) {
        bytes(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }
}
