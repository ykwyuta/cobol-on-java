package dev.cobolonjava.cics;

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
 * @param applid {@code ASSIGN APPLID}が返すregionのapplication ID
 * @param clock  {@code ASKTIME}が読む時計。地方時は{@link CicsTaskContext#hostZone()}で決める
 */
public record CicsEnvironment(Optional<String> applid, Clock clock) {

    /** APPLIDはVTAMの名前規則に合わせ、1〜8文字の英大文字・数字・国別文字に限る。 */
    private static final Pattern APPLID = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,7}");

    public CicsEnvironment {
        Objects.requireNonNull(applid, "applid");
        Objects.requireNonNull(clock, "clock");
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
        return new CicsEnvironment(Optional.empty(), Clock.systemUTC());
    }

    public static CicsEnvironment withApplid(String applid) {
        return new CicsEnvironment(Optional.of(applid), Clock.systemUTC());
    }

    /** 時計だけを替えた構成。試験で時刻を固定するときに使う。 */
    public CicsEnvironment withClock(Clock value) {
        return new CicsEnvironment(applid, value);
    }
}
