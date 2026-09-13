package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** program/link levelごとのCICS condition table契約。 */
@Tag("V1")
class CicsExecutionConditionTest {

    @Test
    @DisplayName("EXEC CICS LINKの間だけ空のcondition levelへ切り替えて呼出元を復元する")
    void isolatesHandlersByLinkLevel() {
        CicsExecution execution = execution();
        Object owner = new Object();
        execution.handleCondition(owner, CicsResponseCode.PGMIDERR, 7);
        execution.bind((command, task) -> {
            assertTrue(execution.conditionHandler(CicsResponseCode.PGMIDERR).isEmpty());
            execution.ignoreCondition(CicsResponseCode.PGMIDERR);
            return new CicsCommandOutcome(CicsResponseCode.NORMAL, 0,
                    new ContinueControl(CicsPayload.empty()));
        });

        execution.execute(new LinkCommand(
                dev.cobolonjava.runtime.interop.ProgramId.of("CHILD"), CicsPayload.empty()));

        CicsExecution.ConditionHandler restored = execution.conditionHandler(
                CicsResponseCode.PGMIDERR).orElseThrow();
        assertEquals(owner, restored.owner());
        assertEquals(7, restored.target());
    }

    @Test
    @DisplayName("HANDLEのhandler省略に相当するresetは既定処置へ戻す")
    void resetsHandlerToDefault() {
        CicsExecution execution = execution();
        execution.handleCondition(new Object(), CicsResponseCode.PGMIDERR, 3);
        execution.handleCondition(new Object(), CicsResponseCode.ERROR_HANDLER_KEY, 9);

        execution.resetCondition(CicsResponseCode.PGMIDERR);

        assertEquals(CicsExecution.DEFAULT_CONDITION, execution.conditionHandler(
                CicsResponseCode.PGMIDERR).orElseThrow().target());
    }

    @Test
    @DisplayName("個別処置がなければgeneralized ERROR handlerへfallbackする")
    void fallsBackToGeneralizedErrorHandler() {
        CicsExecution execution = execution();
        Object owner = new Object();
        execution.handleCondition(owner, CicsResponseCode.ERROR_HANDLER_KEY, 11);

        CicsExecution.ConditionHandler selected = execution.conditionHandler(
                CicsResponseCode.PGMIDERR).orElseThrow();

        assertEquals(owner, selected.owner());
        assertEquals(11, selected.target());
    }

    @Test
    @DisplayName("PUSH HANDLEとPOP HANDLEはcondition処置一式をLIFO順に復元する")
    void pushesAndPopsConditionHandlersInLifoOrder() {
        CicsExecution execution = execution();
        Object owner = new Object();
        execution.handleCondition(owner, CicsResponseCode.PGMIDERR, 3);
        execution.pushHandle();
        execution.ignoreCondition(CicsResponseCode.PGMIDERR);
        execution.pushHandle();
        execution.resetCondition(CicsResponseCode.PGMIDERR);

        execution.popHandle();
        assertEquals(CicsExecution.IGNORE_CONDITION, execution.conditionHandler(
                CicsResponseCode.PGMIDERR).orElseThrow().target());
        execution.popHandle();
        CicsExecution.ConditionHandler restored = execution.conditionHandler(
                CicsResponseCode.PGMIDERR).orElseThrow();
        assertEquals(owner, restored.owner());
        assertEquals(3, restored.target());
    }

    @Test
    @DisplayName("PUSH HANDLEはPOPまで既存condition処置の効果を停止する")
    void suspendsExistingHandlersUntilPop() {
        CicsExecution execution = execution();
        execution.handleCondition(new Object(), CicsResponseCode.PGMIDERR, 3);

        execution.pushHandle();

        assertTrue(execution.conditionHandler(CicsResponseCode.PGMIDERR).isEmpty());
        execution.popHandle();
        assertEquals(3, execution.conditionHandler(
                CicsResponseCode.PGMIDERR).orElseThrow().target());
    }

    @Test
    @DisplayName("POP HANDLEは現在のLINK levelに対応するPUSHがなければ拒否する")
    void rejectsPopWithoutPushInCurrentLinkLevel() {
        CicsExecution execution = execution();

        CicsTaskStateException failure = assertThrows(
                CicsTaskStateException.class, execution::popHandle);

        assertTrue(failure.getMessage().contains("no matching PUSH HANDLE"));
    }

    @Test
    @DisplayName("PUSH HANDLEの退避stackもLINK levelごとに分離する")
    void isolatesSavedHandlersByLinkLevel() {
        CicsExecution execution = execution();
        execution.pushHandle();
        execution.bind((command, task) -> {
            assertThrows(CicsTaskStateException.class, execution::popHandle);
            return new CicsCommandOutcome(CicsResponseCode.NORMAL, 0,
                    new ContinueControl(CicsPayload.empty()));
        });

        execution.execute(new LinkCommand(
                dev.cobolonjava.runtime.interop.ProgramId.of("CHILD"), CicsPayload.empty()));

        execution.popHandle();
    }

    @Test
    @DisplayName("abend exitは選択時に無効化されRESETで再有効化できる")
    void deactivatesAndResetsAbendHandler() {
        CicsExecution execution = execution();
        Object owner = new Object();
        execution.handleAbend(owner, 5);

        execution.cancelAbendHandler();
        assertTrue(execution.takeAbendHandler().isEmpty());
        execution.resetAbendHandler();

        CicsExecution.ConditionHandler first = execution.takeAbendHandler().orElseThrow();

        assertEquals(owner, first.owner());
        assertEquals(5, first.target());
        assertTrue(execution.takeAbendHandler().isEmpty());
        execution.resetAbendHandler();
        assertEquals(5, execution.takeAbendHandler().orElseThrow().target());
    }

    @Test
    @DisplayName("PUSHとPOPはcondition処置とabend exitを一括で停止・復元する")
    void pushesAndPopsAbendHandlerWithConditions() {
        CicsExecution execution = execution();
        execution.handleAbend(new Object(), 6);

        execution.pushHandle();

        assertTrue(execution.takeAbendHandler().isEmpty());
        execution.popHandle();
        assertEquals(6, execution.takeAbendHandler().orElseThrow().target());
    }

    @Test
    @DisplayName("abend exitは現在levelから上位LINK levelへ検索する")
    void searchesAbendHandlerThroughHigherLinkLevels() {
        CicsExecution execution = execution();
        Object outer = new Object();
        Object inner = new Object();
        execution.handleAbend(outer, 7);
        execution.bind((command, task) -> {
            execution.handleAbend(inner, 8);
            assertEquals(inner, execution.takeAbendHandler().orElseThrow().owner());
            assertEquals(outer, execution.takeAbendHandler().orElseThrow().owner());
            return new CicsCommandOutcome(CicsResponseCode.NORMAL, 0,
                    new ContinueControl(CicsPayload.empty()));
        });

        execution.execute(new LinkCommand(
                dev.cobolonjava.runtime.interop.ProgramId.of("CHILD"), CicsPayload.empty()));

        assertTrue(execution.takeAbendHandler().isEmpty());
    }

    private static CicsExecution execution() {
        return new CicsExecution(new CicsTaskContext(
                new CicsTaskId("task_000000000005"), TransId.of("TX01"),
                "condition-test", Instant.parse("2026-09-10T04:00:00Z")), 4);
    }
}
