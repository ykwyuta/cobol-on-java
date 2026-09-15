package dev.cobolonjava.spring.boot4.cics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsStartData;
import dev.cobolonjava.cics.CicsStartPort;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.runtime.interop.ProgramId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/** START の間隔制御の自動構成 (暫定判断 P-138)。 */
@SpringBootTest(classes = CicsStartAutoConfigurationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CicsStartAutoConfigurationTest {

    private static final BlockingQueue<CicsTaskContext> STARTED = new LinkedBlockingQueue<>();

    @SpringBootApplication
    static class TestApplication {

        @Bean
        CicsTransactionRegistry transactions() {
            return new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(TransId.of("TX02"),
                    ProgramId.of("STARTED"), Duration.ofSeconds(5), 0, 0, 0, 0, true)));
        }

        @Bean
        CicsTaskProgramPort programs() {
            return (definition, input, task, syncpoints) -> {
                STARTED.add(task);
                return new TaskCompletion(Optional.empty(), CicsPayload.empty());
            };
        }
    }

    @Autowired
    CicsStartPort starts;

    @Test
    @DisplayName("STARTのbeanは満了したSTARTのtaskをcoordinatorで起こし、STARTのデータを持たせる。定義の無いTRANSIDはTRANSIDERR")
    void startsTasksThroughCoordinator() throws InterruptedException {
        CicsStartData data = new CicsStartData("REQ1", TransId.of("TX02"), new byte[] {1, 2}, Optional.empty(),
                Optional.empty(), Optional.empty(), "start-test", Optional.empty());
        assertThat(starts.start(Instant.now(), data).response()).isEqualTo(CicsResponseCode.NORMAL);

        CicsTaskContext task = STARTED.poll(5, TimeUnit.SECONDS);
        assertThat(task).isNotNull();
        assertThat(task.transactionId()).isEqualTo(TransId.of("TX02"));
        assertThat(task.start()).containsSame(data);

        CicsStartData undefined = new CicsStartData("REQ2", TransId.of("NOPE"), null, Optional.empty(),
                Optional.empty(), Optional.empty(), "start-test", Optional.empty());
        assertThat(starts.start(Instant.now(), undefined).response()).isEqualTo(CicsResponseCode.TRANSIDERR);
    }
}
