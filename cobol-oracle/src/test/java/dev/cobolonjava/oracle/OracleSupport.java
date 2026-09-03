package dev.cobolonjava.oracle;

import dev.cobolonjava.oracle.run.HerculesRunner;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * オラクル (Hercules) を用いるテストの共通補助。
 *
 * <p>Hercules が見つからない環境ではテストをスキップする。オラクルの有無で
 * ビルドが壊れないようにするためである。環境変数 {@code HERCULES} で所在を指定できる。
 */
public final class OracleSupport {

    private OracleSupport() {
    }

    /** Hercules が使えなければ、理由を示してテストをスキップする。 */
    public static HerculesRunner requireHercules() {
        HerculesRunner.Detection detection = HerculesRunner.detect(workDir());
        if (detection instanceof HerculesRunner.Detection.Unavailable unavailable) {
            Assumptions.abort("V2 検証をスキップする: " + unavailable.reason());
        }
        return ((HerculesRunner.Detection.Available) detection).runner();
    }

    static Path workDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "cobol-on-java-oracle");
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static byte[] bytes(String hex) {
        String s = hex.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
