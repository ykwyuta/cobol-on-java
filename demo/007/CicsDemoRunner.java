package demo;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskId;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CobolCicsTaskProgram;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;

import java.time.Duration;
import java.time.Instant;

/**
 * CICS トランザクション & 疑似会話デモ実行クラス。
 * EXEC CICS LINK / XCTL / RETURN TRANSID(...) の動作を検証します。
 */
public class CicsDemoRunner {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println(" [CICS] cobol-on-java DEMO #007 (CICS Transactions)");
        System.out.println("==================================================");

        // 1. ProgramCatalog にコンパイル済み CICS COBOL クラスを登録
        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("demo-007-r1")
                .cobolProgram("CICSMENU", () -> loadProgram("cobol.generated.CICSMENU"))
                .cobolProgram("INQPROG", () -> loadProgram("cobol.generated.INQPROG"))
                .cobolProgram("FINPROG", () -> loadProgram("cobol.generated.FINPROG"))
                .build();

        CobolRuntime runtime = CobolRuntime.builder(catalog)
                .classLoader(CicsDemoRunner.class.getClassLoader())
                .build();

        // 2. トランザクション定義 (TRANSID: MENU -> PROGRAM: CICSMENU, COMMAREA 上限: 32 bytes)
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("MENU"), ProgramId.of("CICSMENU"), Duration.ofSeconds(5),
                32, 0, 0, 0, true);

        CicsTaskContext task = new CicsTaskContext(
                new CicsTaskId("task_demo_cics_01"), TransId.of("MENU"),
                "demo-operator", Instant.now());

        // 初期 COMMAREA (16 bytes 空白)
        byte[] initialCommarea = CodePages.IBM_1047.encode("INIT-COMMAREA   ");

        // 3. CobolCicsTaskProgram による CICS タスク実行
        CobolCicsTaskProgram executor = new CobolCicsTaskProgram(runtime, 32);

        System.out.println("[CICS Runner] Starting CICS Task: TRANSID='MENU'...");
        TaskCompletion completion = executor.execute(
                definition,
                CicsPayload.ofCommarea(initialCommarea),
                task,
                (action, payload) -> System.out.println("  [Syncpoint Callback] " + action)
        );

        // 4. 結果の検証
        System.out.println("==================================================");
        System.out.println("[CICS Runner] CICS Task Completed Successfully!");
        System.out.println("  - Returned COMMAREA: '" +
                CodePages.IBM_1047.decode(completion.payload().commarea()) + "'");
        System.out.println("  - Next TRANSID (Pseudo-Conversation): " +
                completion.nextTransaction().map(TransId::value).orElse("(none)"));
        System.out.println("==================================================");
    }

    private static dev.cobolonjava.runtime.program.CobolProgram loadProgram(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (dev.cobolonjava.runtime.program.CobolProgram)
                    clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to instantiate " + className, e);
        }
    }
}
