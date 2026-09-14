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
 * @param applid   {@code ASSIGN APPLID}が返すregionのapplication ID
 * @param clock    {@code ASKTIME}と{@code DELAY}の期限判定が読む時計。地方時は
 *                 {@link CicsTaskContext#hostZone()}で決める
 * @param interval {@code DELAY}の待ち
 * @param mapsets  {@code SEND MAP} / {@code RECEIVE MAP}が引くmapsetの定義
 */
public record CicsEnvironment(
        Optional<String> applid,
        Clock clock,
        CicsIntervalPort interval,
        Optional<BmsMapsetCatalog> mapsets) {

    /** APPLIDはVTAMの名前規則に合わせ、1〜8文字の英大文字・数字・国別文字に限る。 */
    private static final Pattern APPLID = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,7}");

    public CicsEnvironment {
        Objects.requireNonNull(applid, "applid");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(mapsets, "mapsets");
        applid.ifPresent(value -> {
            if (!APPLID.matcher(value).matches()) {
                throw new IllegalArgumentException("APPLID has an unsupported format: " + value);
            }
        });
    }

    /**
     * 何も構成していないregion。構成を要する命令は実行時に失敗する。
     *
     * <p>時計はUTCのsystem clockとする。時計の値そのものは地方時に依らず、地方時の
     * 解釈はtaskのhostZoneが決めるので、ここで推測は起きない。
     */
    public static CicsEnvironment unconfigured() {
        return new CicsEnvironment(Optional.empty(), Clock.systemUTC(),
                CicsIntervalPort.sleeping(), Optional.empty());
    }

    public static CicsEnvironment withApplid(String applid) {
        return unconfigured().withApplidValue(applid);
    }

    private CicsEnvironment withApplidValue(String value) {
        return new CicsEnvironment(Optional.of(value), clock, interval, mapsets);
    }

    /** 時計だけを替えた構成。試験で時刻を固定するときに使う。 */
    public CicsEnvironment withClock(Clock value) {
        return new CicsEnvironment(applid, value, interval, mapsets);
    }

    /** 待ちだけを替えた構成。 */
    public CicsEnvironment withInterval(CicsIntervalPort value) {
        return new CicsEnvironment(applid, clock, value, mapsets);
    }

    /** mapsetの定義を持たせた構成。 */
    public CicsEnvironment withMapsets(BmsMapsetCatalog value) {
        return new CicsEnvironment(applid, clock, interval, Optional.of(value));
    }
}
