package dev.cobolonjava.cics;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * START / CANCEL の間隔制御 (暫定判断 P-138、設計 83 §5)。region の構成が持ち、満了した START の task を起こす。
 *
 * <p>返す RESP2 は、START / CANCEL の頁が値を示さないものは 0 とする。
 */
public interface CicsStartPort {

    record Result(int response, int response2) {
    }

    /**
     * START を登録する。満了すれば task を起こす。
     *
     * @return NORMAL、TRANSIDERR (transaction が定義されていない)、IOERR (FROM を持つ START の REQID が未満了の START と重なる)、
     *         TERMIDERR (TERMID の端末が無いか、START を出した task と owner が違う)
     */
    Result start(Instant expiration, CicsStartData data);

    /**
     * START を登録できるかだけを確かめる。PROTECT の START は命令の時点で条件を返し、同期点で {@link #start} する。
     * 既定は NORMAL。
     */
    default Result check(CicsStartData data) {
        return new Result(CicsResponseCode.NORMAL, 0);
    }

    /** 未満了の START を取り消す。無ければ NOTFND。 */
    Result cancel(String requestId);

    /**
     * RETRIEVE WAIT (設計 83 §5)。{@code started} の task が起きたあとに満了した、同じ端末と TRANSID の START を取り出す。
     * 無ければ空。既定は端末を知らないので断る。
     */
    default java.util.List<CicsStartData> retrieveMore(CicsStartData started) {
        throw new CicsTaskStateException("RETRIEVE WAIT requires a start port that knows the terminals");
    }

    /** REQID を書かない START のために、区別できる名前を作る。 */
    String newRequestId();

    /** 間隔制御を構成していない region。START と CANCEL は推測で進まず失敗させる。 */
    static CicsStartPort none() {
        return new CicsStartPort() {
            @Override
            public Result start(Instant expiration, CicsStartData data) {
                throw new CicsTaskStateException("START requires a configured interval control port");
            }

            @Override
            public Result check(CicsStartData data) {
                throw new CicsTaskStateException("START requires a configured interval control port");
            }

            @Override
            public Result cancel(String requestId) {
                throw new CicsTaskStateException("CANCEL requires a configured interval control port");
            }

            @Override
            public String newRequestId() {
                throw new CicsTaskStateException("START requires a configured interval control port");
            }
        };
    }

    /**
     * 1 つの JVM の中で満了を待ち、task を起こす。端末の登録を持たないので TERMID の START は断る。
     *
     * @param defined  transaction が定義されているか。されていなければ TRANSIDERR
     * @param launcher 満了した START の task を起こす。START を出した task とは別の thread で呼ぶ
     */
    static CicsStartPort inMemory(Clock clock, Predicate<TransId> defined, Consumer<CicsStartData> launcher) {
        return new InMemoryCicsStarts(clock, defined, launcher);
    }

    /**
     * coordinator で task を起こす launcher。端末と COMMAREA を持たない task になる。
     *
     * <p>coordinator は region の構成 (この port を含む) から作られるので、作ったあとに渡せるよう Supplier で受ける。
     */
    static Consumer<CicsStartData> launching(Supplier<CicsTaskCoordinator> coordinator) {
        Function<CicsStartData, CicsTerminalTasks.Outcome> conversing = conversing(coordinator);
        return conversing::apply;
    }

    /**
     * coordinator で task を起こし、端末の次の疑似会話を返す launcher (設計 83 §5)。
     *
     * <p>TERMID の START は端末を principal facility にして起こし、RETURN IMMEDIATE なら端末の入力なしで次の task を
     * 続ける (ブラウザの入口と同じく 8 回まで)。端末の無い START は task を 1 つ起こすだけで、会話は返さない。
     */
    static Function<CicsStartData, CicsTerminalTasks.Outcome> conversing(
            Supplier<CicsTaskCoordinator> coordinator) {
        return data -> {
            if (data.terminalId().isPresent()) {
                return CicsTerminalTasks.run(coordinator.get(), data.transaction(), data.owner(), data.userId(),
                        data.terminalId().orElseThrow(), Optional.of(data), "start");
            }
            coordinator.get().launch(new CicsTaskRequest(data.transaction().value(), data.owner(), data.payload(),
                    Optional.empty(), new IdempotencyKey("start-" + UUID.randomUUID()), Optional.empty(),
                    Optional.empty(), data.userId(), Optional.of(data)));
            return CicsTerminalTasks.Outcome.none();
        };
    }
}
