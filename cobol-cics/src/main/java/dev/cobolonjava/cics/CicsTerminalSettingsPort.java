package dev.cobolonjava.cics;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端末定義のうち task をまたいで残る設定 (暫定判断 P-130)。
 *
 * <p>{@code SET TERMINAL} は端末の定義を変えるので、task や会話ではなく region が持つ。
 * 今は大文字変換 ({@code UCTRANST}) だけを扱う。
 */
public interface CicsTerminalSettingsPort {

    /** 端末の大文字変換の CVDA。 */
    int uppercaseTranslation(String terminalId);

    /** 端末の大文字変換を変える。値は {@link CicsCvda#isUppercaseTranslation(int)} を満たす。 */
    void setUppercaseTranslation(String terminalId, int cvda);

    /**
     * 1 つの JVM の中で設定を持つ実装。
     *
     * @param defaultUppercase 設定を変えていない端末の値。TYPETERM の既定 (UCTRAN(NO)) なら NOUCTRAN
     */
    static CicsTerminalSettingsPort inMemory(int defaultUppercase) {
        if (!CicsCvda.isUppercaseTranslation(defaultUppercase)) {
            throw new IllegalArgumentException("not an UCTRANST CVDA: " + defaultUppercase);
        }
        Map<String, Integer> settings = new ConcurrentHashMap<>();
        return new CicsTerminalSettingsPort() {
            @Override
            public int uppercaseTranslation(String terminalId) {
                return settings.getOrDefault(Objects.requireNonNull(terminalId, "terminalId"), defaultUppercase);
            }

            @Override
            public void setUppercaseTranslation(String terminalId, int cvda) {
                if (!CicsCvda.isUppercaseTranslation(cvda)) {
                    throw new IllegalArgumentException("not an UCTRANST CVDA: " + cvda);
                }
                settings.put(Objects.requireNonNull(terminalId, "terminalId"), cvda);
            }
        };
    }
}
