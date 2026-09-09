package dev.cobolonjava.runtime.abend;

import java.util.Locale;

/**
 * 診断出力の細かさ (要件 FR-143)。
 *
 * <p>ホストの言語環境では実行時オプション {@code TERMTHDACT} が決める。異常終了のたびに
 * 記憶域の中身まで書き出すと、量が多すぎて<b>肝心の 1 行が埋もれる</b>。一方で本番で
 * 追い切れない事故が起きたときには中身が要る。だから切り替えられるようになっている。
 */
public enum DumpLevel {

    /** 何も出さない。 */
    QUIET(false, false),

    /** 覚え書きの行だけ。 */
    MSG(false, false),

    /** 覚え書きと呼び出し履歴。既定である。 */
    TRACE(true, false),

    /** 覚え書きと呼び出し履歴と記憶域の中身。 */
    DUMP(true, true);

    private final boolean traceback;
    private final boolean storage;

    DumpLevel(boolean traceback, boolean storage) {
        this.traceback = traceback;
        this.storage = storage;
    }

    /** 覚え書きの行を出すか。 */
    public boolean message() {
        return this != QUIET;
    }

    /** 呼び出し履歴を出すか。 */
    public boolean traceback() {
        return traceback;
    }

    /** 記憶域の中身を出すか。 */
    public boolean storage() {
        return storage;
    }

    /**
     * {@code TERMTHDACT} の綴りから読む。
     *
     * <p>ホストには {@code UAONLY} / {@code UATRACE} / {@code UADUMP} / {@code UAIMM} も
     * ある。どれも「利用者が受け止めなかった異常だけ」を区別するもので、ここでは受け止め手の
     * 有無で打ち切るかどうかがすでに決まっているため、対応するものへ読み替える
     * (暫定判断 P-051)。
     *
     * @return 知らない綴りは {@code null}
     */
    public static DumpLevel of(String written) {
        if (written == null) {
            return null;
        }
        return switch (written.trim().toUpperCase(Locale.ROOT)) {
            case "QUIET" -> QUIET;
            case "MSG", "UAONLY" -> MSG;
            case "TRACE", "UATRACE", "UAIMM" -> TRACE;
            case "DUMP", "UADUMP" -> DUMP;
            default -> null;
        };
    }
}
