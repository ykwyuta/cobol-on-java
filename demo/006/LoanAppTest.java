package demo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.junit.CobolExtension;
import dev.cobolonjava.junit.CobolProgramMock;
import dev.cobolonjava.junit.CobolSectionMock;
import dev.cobolonjava.junit.CobolTestResult;
import dev.cobolonjava.junit.ProgramInvocation;
import dev.cobolonjava.runtime.interop.Termination;
import dev.cobolonjava.runtime.procedure.ProcedureOutcome;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * JUnit 5 による COBOL 単体テスト & モッキングのデモ。
 * 外部サブルーチン SCOREAPI の Java モック化、および EVALUATE-LIMIT セクションの Spy 検証。
 */
public class LoanAppTest {

    @RegisterExtension
    final CobolExtension cobol = CobolExtension.builder()
            .source(Path.of("LOAN-APP.cbl"))
            .build();

    @Test
    @DisplayName("高スコア顧客 (850点) の場合: 限度額2倍 (1000万円) で承認 (A) されること")
    void approvesHighCreditCustomerWithDoubleLimit() {
        // 1. 外部サブルーチン SCOREAPI を Java 実装へモック（期待値: 850点, 'OK'）
        CobolProgramMock scoreMock = cobol.expectProgram("SCOREAPI")
                .times(1)
                .thenAnswer((context, arguments) -> {
                    // arguments[0]: LNK-CUSTOMER-ID (PIC X(6))
                    // arguments[1]: WS-SCORE-RESULT (PIC 9(3)) -> "850"
                    // arguments[2]: WS-SCORE-STATUS (PIC X(2)) -> "OK"
                    arguments.get(1).setBytes(context.codePage().encode("850"));
                    arguments.get(2).setBytes(context.codePage().encode("OK"));
                });

        // 2. 内部セクション EVALUATE-LIMIT の実行を Spy で監視
        CobolSectionMock sectionSpy = cobol.spySection("LOAN-APP", "EVALUATE-LIMIT")
                .times(1);

        // 3. 引数の準備 (CUSTOMER-ID: "CUST01", APPROVED-AMT: 0, DECISION: " ")
        Storage custId = Storage.copyOf(cobol.codePage().encode("CUST01"));
        Storage approvedAmt = Storage.copyOf(cobol.codePage().encode("00000000"));
        Storage decision = Storage.copyOf(cobol.codePage().encode(" "));

        // 4. COBOL プログラム呼び出し
        CobolTestResult result = cobol.program("LOAN-APP")
                .byReference(custId.whole(), approvedAmt.whole(), decision.whole())
                .call();

        // 5. 検証 (アサーション)
        assertEquals(Termination.RETURNED, result.termination());
        assertEquals(0, result.returnCode());

        // 外部サブルーチンの呼び出し回数・引数確認
        assertEquals(1, scoreMock.count());
        ProgramInvocation invocation = scoreMock.invocations().get(0);
        assertArrayEquals(cobol.codePage().encode("CUST01"), invocation.argumentsBefore().get(0));

        // 内部セクションが正常に実実行されたことの検証
        assertEquals(ProcedureOutcome.REAL_RETURN, sectionSpy.invocations().get(0).outcome());

        // 判定結果の検証: 決定 'A', 限度額 10,000,000 円 ("10000000")
        assertEquals("A", cobol.codePage().decode(decision.array()));
        assertEquals("10000000", cobol.codePage().decode(approvedAmt.array()));
    }

    @Test
    @DisplayName("低スコア顧客 (400点) の場合: 否認 (R) かつ限度額 0 円になること")
    void rejectsLowCreditCustomer() {
        cobol.expectProgram("SCOREAPI")
                .times(1)
                .thenAnswer((context, arguments) -> {
                    arguments.get(1).setBytes(context.codePage().encode("400"));
                    arguments.get(2).setBytes(context.codePage().encode("OK"));
                });

        Storage custId = Storage.copyOf(cobol.codePage().encode("CUST02"));
        Storage approvedAmt = Storage.copyOf(cobol.codePage().encode("00000000"));
        Storage decision = Storage.copyOf(cobol.codePage().encode(" "));

        CobolTestResult result = cobol.program("LOAN-APP")
                .byReference(custId.whole(), approvedAmt.whole(), decision.whole())
                .call();

        assertEquals(0, result.returnCode());
        assertEquals("R", cobol.codePage().decode(decision.array()));
        assertEquals("00000000", cobol.codePage().decode(approvedAmt.array()));
    }
}
