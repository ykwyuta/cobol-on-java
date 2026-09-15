package dev.cobolonjava.cics;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 非同期 API (RUN TRANSID / FETCH CHILD / FETCH ANY / FREE CHILD) の子の task (暫定判断 P-140)。
 *
 * <p>返す RESP / RESP2 は各命令の頁の値である。
 */
public interface CicsAsyncPort {

    /** 子の token の長さ。 */
    int TOKEN_LENGTH = 16;
    /** FETCH の TIMEOUT の上限 (ミリ秒)。FETCH ANY / FETCH CHILD の頁による。 */
    long MAX_TIMEOUT_MILLIS = 40_800_000L;

    record Result(int response, int response2) {
    }

    /** RUN の結果。NORMAL なら token を持つ。 */
    record Run(int response, int response2, byte[] token) {
    }

    /**
     * FETCH の結果。NORMAL なら token、完了の状態 (CVDA)、abend code (無ければ空の文字列)、reply channel を持つ。
     * reply channel は、子が channel を持たなければ空。
     */
    record Fetched(int response, int response2, byte[] token, int completionStatus, String abendCode,
                   Optional<Map<String, byte[]>> replyChannel) {
    }

    Run run(CicsTaskId parent, CicsAsyncChild child);

    /**
     * 終わった子を取り出す。
     *
     * @param token         FETCH CHILD の token。null なら FETCH ANY
     * @param timeoutMillis TIMEOUT。0 なら無し
     * @param maxWait       task の期限までの長さ。null なら限りなく待つ
     */
    Fetched fetch(CicsTaskId parent, byte[] token, boolean noSuspend, long timeoutMillis, Duration maxWait);

    Result free(CicsTaskId parent, byte[] token);

    /** 親の task の終わりに、子の token をすべて返す (FREE CHILD の頁)。 */
    void releaseTask(CicsTaskId parent);

    /** 非同期 API を構成していない region。命令は失敗させる。 */
    static CicsAsyncPort none() {
        return new CicsAsyncPort() {
            @Override
            public Run run(CicsTaskId parent, CicsAsyncChild child) {
                throw new CicsTaskStateException("RUN TRANSID requires a configured asynchronous API port");
            }

            @Override
            public Fetched fetch(CicsTaskId parent, byte[] token, boolean noSuspend, long timeoutMillis,
                                 Duration maxWait) {
                throw new CicsTaskStateException("FETCH requires a configured asynchronous API port");
            }

            @Override
            public Result free(CicsTaskId parent, byte[] token) {
                throw new CicsTaskStateException("FREE CHILD requires a configured asynchronous API port");
            }

            @Override
            public void releaseTask(CicsTaskId parent) {
            }
        };
    }

    /**
     * 1 つの JVM の中で子の task を別の thread で動かす。
     *
     * @param launcher 子の task を動かし、終わったときの current channel の container を返す。ABEND は {@link CicsAbend} で投げる
     */
    static CicsAsyncPort inMemory(CicsTransactionRegistry transactions, Function<CicsAsyncChild, CicsPayload> launcher) {
        return new InMemoryCicsAsync(transactions, launcher);
    }

    /** coordinator で子の task を起こす launcher。端末と COMMAREA を持たない task になる。 */
    static Function<CicsAsyncChild, CicsPayload> launching(Supplier<CicsTaskCoordinator> coordinator) {
        return child -> coordinator.get().launch(new CicsTaskRequest(child.transaction().value(), child.owner(),
                new CicsPayload(new byte[0], child.containers(), child.channelName().orElse(null)), Optional.empty(),
                new IdempotencyKey("run-" + UUID.randomUUID()), Optional.empty(), Optional.empty(), child.userId(),
                Optional.empty())).payload();
    }
}
