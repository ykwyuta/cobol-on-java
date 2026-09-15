package dev.cobolonjava.cics;

import dev.cobolonjava.cics.bms.BmsMapsetCatalog;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * taskが実行時に参照するregionの構成 (設計 79 §3.2)。
 *
 * <p>生成COBOLとCICS runtime操作は時計、端末、region名を直接持たない。adapterが構成した値を
 * ここから得る。構成されていない値を使う命令は、推測値で進まず実行時に失敗する。
 *
 * @param applid           {@code ASSIGN APPLID}が返すregionのapplication ID
 * @param clock            {@code ASKTIME}と{@code DELAY}の期限判定が読む時計。地方時は
 *                         {@link CicsTaskContext#hostZone()}で決める
 * @param interval         {@code DELAY}の待ち
 * @param mapsets          {@code SEND MAP} / {@code RECEIVE MAP}が引くmapsetの定義
 * @param enqueues         {@code ENQ} / {@code DEQ}の資源の排他。regionのtaskどうしで分け合う
 * @param terminals        端末定義のうちtaskをまたいで残る設定 ({@code SET TERMINAL})
 * @param files            file control。定義の無いregionではFILENOTFOUNDになる
 * @param temporaryStorage 一時記憶のキュー ({@code WRITEQ TS} 等)。定義を要らない
 * @param transientData    一時データのキュー ({@code WRITEQ TD} 等)。定義の無いキューはQIDERRになる
 * @param starts           {@code START} / {@code CANCEL}の間隔制御。構成しなければ命令は失敗する
 * @param async            非同期 API の子の task。構成しなければ命令は失敗する
 * @param networkId        端末が属するnetworkのID ({@code INQUIRE ASSOCIATION ODNETWORKID})
 */
public record CicsEnvironment(
        Optional<String> applid,
        Clock clock,
        CicsIntervalPort interval,
        Optional<BmsMapsetCatalog> mapsets,
        CicsEnqueuePort enqueues,
        CicsTerminalSettingsPort terminals,
        CicsFilePort files,
        CicsTemporaryStoragePort temporaryStorage,
        CicsTransientDataPort transientData,
        CicsStartPort starts,
        CicsAsyncPort async,
        Optional<String> networkId) {

    /** APPLIDはVTAMの名前規則に合わせ、1〜8文字の英大文字・数字・国別文字に限る。 */
    private static final Pattern APPLID = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,7}");

    public CicsEnvironment {
        Objects.requireNonNull(applid, "applid");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(mapsets, "mapsets");
        Objects.requireNonNull(enqueues, "enqueues");
        Objects.requireNonNull(terminals, "terminals");
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(temporaryStorage, "temporaryStorage");
        Objects.requireNonNull(transientData, "transientData");
        Objects.requireNonNull(starts, "starts");
        Objects.requireNonNull(async, "async");
        Objects.requireNonNull(networkId, "networkId");
        applid.ifPresent(value -> {
            if (!APPLID.matcher(value).matches()) {
                throw new IllegalArgumentException("APPLID has an unsupported format: " + value);
            }
        });
        networkId.ifPresent(value -> {
            if (!APPLID.matcher(value).matches()) {
                throw new IllegalArgumentException("network ID has an unsupported format: " + value);
            }
        });
    }

    /**
     * 何も構成していないregion。構成を要する命令は実行時に失敗する。
     *
     * <p>時計はUTCのsystem clockとする。時計の値そのものは地方時に依らず、地方時の
     * 解釈はtaskのhostZoneが決めるので、ここで推測は起きない。資源の排他と端末の設定は
     * 1つのJVMの中で効き、端末の大文字変換はTYPETERMの既定と同じNOUCTRANから始まる。
     * fileと一時データのキューは1つも定義しない。一時記憶のキューは定義を要らないので1つのJVMの中で持つ。
     * STARTはtaskを起こす先 (coordinator) を知らないので構成しない。
     */
    public static CicsEnvironment unconfigured() {
        return new CicsEnvironment(Optional.empty(), Clock.systemUTC(),
                CicsIntervalPort.sleeping(), Optional.empty(), CicsEnqueuePort.inMemory(),
                CicsTerminalSettingsPort.inMemory(CicsCvda.NOUCTRAN), CicsFilePort.none(),
                CicsTemporaryStoragePort.inMemory(), CicsTransientDataPort.none(), CicsStartPort.none(),
                CicsAsyncPort.none(), Optional.empty());
    }

    public static CicsEnvironment withApplid(String applid) {
        return unconfigured().withApplidValue(applid);
    }

    private CicsEnvironment withApplidValue(String value) {
        return new CicsEnvironment(Optional.of(value), clock, interval, mapsets, enqueues, terminals, files,
                temporaryStorage, transientData, starts, async, networkId);
    }

    /** 時計だけを替えた構成。試験で時刻を固定するときに使う。 */
    public CicsEnvironment withClock(Clock value) {
        return new CicsEnvironment(applid, value, interval, mapsets, enqueues, terminals, files,
                temporaryStorage, transientData, starts, async, networkId);
    }

    /** 待ちだけを替えた構成。 */
    public CicsEnvironment withInterval(CicsIntervalPort value) {
        return new CicsEnvironment(applid, clock, value, mapsets, enqueues, terminals, files,
                temporaryStorage, transientData, starts, async, networkId);
    }

    /** mapsetの定義を持たせた構成。 */
    public CicsEnvironment withMapsets(BmsMapsetCatalog value) {
        return new CicsEnvironment(applid, clock, interval, Optional.of(value), enqueues, terminals, files,
                temporaryStorage, transientData, starts, async, networkId);
    }

    /** 資源の排他を替えた構成。複数のJVMで分け合うときに使う。 */
    public CicsEnvironment withEnqueues(CicsEnqueuePort value) {
        return new CicsEnvironment(applid, clock, interval, mapsets, value, terminals, files,
                temporaryStorage, transientData, starts, async, networkId);
    }

    /** 端末の設定を替えた構成。 */
    public CicsEnvironment withTerminals(CicsTerminalSettingsPort value) {
        return new CicsEnvironment(applid, clock, interval, mapsets, enqueues, value, files,
                temporaryStorage, transientData, starts, async, networkId);
    }

    /** file controlを替えた構成。 */
    public CicsEnvironment withFiles(CicsFilePort value) {
        return new CicsEnvironment(applid, clock, interval, mapsets, enqueues, terminals, value,
                temporaryStorage, transientData, starts, async, networkId);
    }

    /** 一時記憶のキューを替えた構成。複数のJVMで分け合うときに使う。 */
    public CicsEnvironment withTemporaryStorage(CicsTemporaryStoragePort value) {
        return new CicsEnvironment(applid, clock, interval, mapsets, enqueues, terminals, files,
                value, transientData, starts, async, networkId);
    }

    /** 一時データのキューを替えた構成。 */
    public CicsEnvironment withTransientData(CicsTransientDataPort value) {
        return new CicsEnvironment(applid, clock, interval, mapsets, enqueues, terminals, files,
                temporaryStorage, value, starts, async, networkId);
    }

    /** STARTの間隔制御を持たせた構成。 */
    public CicsEnvironment withStarts(CicsStartPort value) {
        return new CicsEnvironment(applid, clock, interval, mapsets, enqueues, terminals, files,
                temporaryStorage, transientData, value, async, networkId);
    }

    /** 非同期 API (RUN TRANSID / FETCH) の子の task を持たせた構成。 */
    public CicsEnvironment withAsync(CicsAsyncPort value) {
        return new CicsEnvironment(applid, clock, interval, mapsets, enqueues, terminals, files,
                temporaryStorage, transientData, starts, value, networkId);
    }

    /** 端末が属するnetworkのIDを持たせた構成。 */
    public CicsEnvironment withNetworkId(String value) {
        return new CicsEnvironment(applid, clock, interval, mapsets, enqueues, terminals, files,
                temporaryStorage, transientData, starts, async, Optional.of(value));
    }
}
