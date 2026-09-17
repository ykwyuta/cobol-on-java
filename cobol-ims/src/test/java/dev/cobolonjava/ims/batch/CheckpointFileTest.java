package dev.cobolonjava.ims.batch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 記号 CHKP が退避した域をデータセットに置く形 (暫定判断 P-164)。 */
@Tag("V1")
class CheckpointFileTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    @TempDir
    private Path directory;

    private CheckpointFile file() {
        return new CheckpointFile(directory.resolve("IMS.CKPT"), "IMSCKPT", EBCDIC);
    }

    @Test
    @DisplayName("書いた検査点を読み直せる。域の長さと並びはそのままで、長さ 0 の域も保つ")
    void checkpointsSurviveAWriteAndRead() {
        Map<String, List<byte[]>> written = new LinkedHashMap<>();
        written.put("BANKPSB/CHKP0001", List.of(EBCDIC.encode("00000042"), EBCDIC.encode("0001")));
        written.put("BANKPSB/CHKP0002", List.of(new byte[0]));
        written.put("OTHERPSB/CHKP0001", List.of(EBCDIC.encode("X")));
        file().write(written);

        Map<String, List<byte[]>> read = file().read();

        assertEquals(List.of("BANKPSB/CHKP0001", "BANKPSB/CHKP0002", "OTHERPSB/CHKP0001"),
                List.copyOf(read.keySet()));
        assertArrayEquals(EBCDIC.encode("00000042"), read.get("BANKPSB/CHKP0001").get(0));
        assertArrayEquals(EBCDIC.encode("0001"), read.get("BANKPSB/CHKP0001").get(1));
        assertEquals(0, read.get("BANKPSB/CHKP0002").get(0).length);
        // 同じ ID でも PSB が違えば別の検査点である
        assertArrayEquals(EBCDIC.encode("X"), read.get("OTHERPSB/CHKP0001").get(0));
    }

    @Test
    @DisplayName("無いデータセットは検査点が無いということであり、壊れたものは読まない")
    void missingIsEmptyAndDamagedIsRefused() throws IOException {
        assertTrue(file().read().isEmpty());

        // 件の長さが中身より長い
        Files.write(directory.resolve("IMS.CKPT"), new byte[] {0, 0, 0, 120, 0, 0});
        ImsBatchException damaged = assertThrows(ImsBatchException.class, () -> file().read());
        assertTrue(damaged.getMessage().contains("DD IMSCKPT is damaged"), damaged.getMessage());
    }
}
