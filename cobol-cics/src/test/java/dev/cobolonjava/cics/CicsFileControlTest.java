package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WRITE FILE の file control (暫定判断 P-131)。 */
@Tag("V1")
class CicsFileControlTest {

    private static final CodePage CP = CodePages.DEFAULT;

    @TempDir
    Path directory;

    private CicsExecution execution;

    private ProgramContext context(CicsFilePort files) {
        execution = new CicsExecution(new CicsTaskContext(new CicsTaskId("task_file"), TransId.of("TX01"),
                "file-test", Instant.EPOCH), 0, CicsEnvironment.unconfigured().withFiles(files));
        return ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
    }

    private int eib(int offset) {
        return ByteBuffer.wrap(execution.eib(CP).storage().array(), offset, 4).getInt();
    }

    @Test
    @DisplayName("定義したKSDSへ鍵で書き、同じ鍵はDUPREC、定義の無いfileはFILENOTFOUND")
    void writesKeyedRecords() {
        CicsFilePort files = CicsFilePort.dataSets(List.of(
                new CicsFileDefinition("ABNDFILE", directory.resolve("abnd.ksds"), 0, 4, 10, CP)));
        ProgramContext context = context(files);

        CicsRuntimeOps.writeFileCondition(context, "ABNDFILE", null,
                Storage.copyOf(CP.encode("K001record")).whole(), Storage.copyOf(CP.encode("K001")).whole(),
                -1, -1, true);
        assertEquals(CicsResponseCode.NORMAL, eib(CicsEib.EIBRESP_OFFSET));
        assertEquals("ABNDFILE", CP.decode(java.util.Arrays.copyOfRange(execution.eib(CP).storage().array(),
                CicsEib.EIBDS_OFFSET, CicsEib.EIBDS_OFFSET + CicsEib.EIBDS_LENGTH)));

        // 別の task でも同じデータセットに残っている
        ProgramContext next = context(files);
        CicsRuntimeOps.writeFileCondition(next, "ABNDFILE", null,
                Storage.copyOf(CP.encode("K001other ")).whole(), Storage.copyOf(CP.encode("K001")).whole(),
                -1, -1, true);
        assertEquals(CicsResponseCode.DUPREC, eib(CicsEib.EIBRESP_OFFSET));
        assertEquals(150, eib(CicsEib.EIBRESP2_OFFSET));

        CicsRuntimeOps.writeFileCondition(next, null, CP.encode("NOFILE  "),
                Storage.copyOf(CP.encode("K002record")).whole(), Storage.copyOf(CP.encode("K002")).whole(),
                -1, -1, true);
        assertEquals(CicsResponseCode.FILENOTFOUND, eib(CicsEib.EIBRESP_OFFSET));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.writeFileCondition(next, "NOFILE", null,
                Storage.copyOf(CP.encode("K002record")).whole(), Storage.copyOf(CP.encode("K002")).whole(),
                -1, -1, false));
    }

    @Test
    @DisplayName("鍵の食い違いと長さの食い違いは、条件の値を確かめていないので失敗させる")
    void rejectsMismatches() {
        CicsFilePort files = CicsFilePort.dataSets(List.of(
                new CicsFileDefinition("ABNDFILE", directory.resolve("abnd.ksds"), 0, 4, 10, CP)));
        ProgramContext context = context(files);
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.writeFileCondition(context, "ABNDFILE",
                null, Storage.copyOf(CP.encode("K001record")).whole(), Storage.copyOf(CP.encode("K999")).whole(),
                -1, -1, true));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.writeFileCondition(context, "ABNDFILE",
                null, Storage.copyOf(CP.encode("K001rec")).whole(), Storage.copyOf(CP.encode("K001")).whole(),
                -1, -1, true));
    }
}
