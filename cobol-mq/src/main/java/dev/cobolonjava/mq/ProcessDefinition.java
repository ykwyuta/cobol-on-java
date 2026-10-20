package dev.cobolonjava.mq;

import java.util.Objects;

/**
 * 処理の定義 (MQSC の {@code DEFINE PROCESS APPLICID APPLTYPE ENVRDATA USERDATA})。起動電文 (MQTM) の ApplId などに写る。
 *
 * <p>CICS の trigger monitor は ApplId の先頭を起こす transaction の名前として読む。
 *
 * @param name            処理の名前 (48 文字まで)
 * @param applicationId   APPLICID (256 文字まで)
 * @param applicationType APPLTYPE (MQAT_CICS など)
 * @param environmentData ENVRDATA (128 文字まで)
 * @param userData        USERDATA (128 文字まで)
 */
public record ProcessDefinition(String name, String applicationId, int applicationType, String environmentData,
                                String userData) {

    public ProcessDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(environmentData, "environmentData");
        Objects.requireNonNull(userData, "userData");
        if (name.isBlank() || name.length() > Structure.NAME_LENGTH) {
            throw new IllegalArgumentException("a process name is 1 to 48 characters: " + name);
        }
        if (applicationId.length() > 256 || environmentData.length() > 128 || userData.length() > 128) {
            throw new IllegalArgumentException("APPLICID is up to 256, ENVRDATA and USERDATA up to 128 characters");
        }
    }

    /** CICS の transaction を起こす処理。 */
    public static ProcessDefinition cics(String name, String transactionId) {
        return new ProcessDefinition(name, transactionId, MqConstants.MQAT_CICS, "", "");
    }
}
