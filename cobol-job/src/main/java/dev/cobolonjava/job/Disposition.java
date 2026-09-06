package dev.cobolonjava.job;

import java.util.Locale;

/**
 * データセットの処置 (要件 FR-131, FR-133)。
 *
 * <p>JCL の {@code DISP=(状態, 正常終了時, 異常終了時)} である。3 つとも意味が違う。
 * 1 つ目は<b>ステップが始まるときに何を期待するか</b>、あとの 2 つは<b>ステップが終わった
 * ときに何を残すか</b>である。
 *
 * <h2>省略したときの既定はホストの規則に従う</h2>
 * <p>2 つ目を書かなければ、新しく作るデータセットは {@code DELETE}、もとからあるものは
 * {@code KEEP} である。<b>作ったものは消える</b>のが既定であり、残したければ書かねば
 * ならない。3 つ目を書かなければ 2 つ目と同じになる。ただし 2 つ目が {@code PASS} の
 * ときだけは、新しいものは消え、もとからあるものは残る。
 *
 * @param status   ステップが始まるときの状態
 * @param normal   正常に終わったときの処置
 * @param abnormal 異常終了したときの処置
 */
public record Disposition(Status status, Action normal, Action abnormal) {

    /** ステップが始まるときに何を期待するか。 */
    public enum Status {

        /** 新しく作る。もとからあれば誤りである。 */
        NEW,

        /** あるものを占有して使う。 */
        OLD,

        /** あるものを共有して使う。 */
        SHR,

        /** あるものの末尾へ足す。なければ作る。 */
        MOD,

        /**
         * ジョブが状態を言っていない。
         *
         * <p>JCL からは<b>決して生まれない</b>。{@code DISP} を書かなければ {@code NEW} だと
         * 決まっているからである。これがあるのは宣言的形式のためで、そちらは名前を書くだけ
         * であり、読むつもりか書くつもりかを言わない。何も確かめず、何も作らない。
         */
        ANY;

        /** JCL の綴りから読む。知らない綴りは {@code null} を返す。 */
        public static Status of(String text) {
            return switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "NEW" -> NEW;
                case "OLD" -> OLD;
                case "SHR" -> SHR;
                case "MOD" -> MOD;
                default -> null;
            };
        }
    }

    /** ステップが終わったときに何を残すか。 */
    public enum Action {

        /** 消す。 */
        DELETE,

        /** 残す。 */
        KEEP,

        /** 残して目録へ載せる。 */
        CATLG,

        /** 残して目録から外す。 */
        UNCATLG,

        /** 後続のステップへ渡す。 */
        PASS;

        /** JCL の綴りから読む。知らない綴りは {@code null} を返す。 */
        public static Action of(String text) {
            return switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "DELETE" -> DELETE;
                case "KEEP" -> KEEP;
                case "CATLG" -> CATLG;
                case "UNCATLG" -> UNCATLG;
                case "PASS" -> PASS;
                default -> null;
            };
        }
    }

    /** 状態を言わず、そのまま残す。記述形式に処置の書き分けがないときの既定である。 */
    public static final Disposition UNSPECIFIED = of(Status.ANY);

    /** 状態だけを言った処置。あとの 2 つはホストの既定に従う。 */
    public static Disposition of(Status status) {
        return of(status, null, null);
    }

    /**
     * 書かれたものから作る。書かれていないところはホストの既定で埋める。
     *
     * @param normal   書かれていなければ {@code null}
     * @param abnormal 書かれていなければ {@code null}
     */
    public static Disposition of(Status status, Action normal, Action abnormal) {
        Action kept = status == Status.NEW ? Action.DELETE : Action.KEEP;
        Action first = normal == null ? kept : normal;
        // 3 つ目は 2 つ目と同じになる。渡すだけの PASS は、残す理由にならない
        Action second = abnormal == null ? (first == Action.PASS ? kept : first) : abnormal;
        return new Disposition(status, first, second);
    }

    /** ステップが終わったとき、このデータセットを消すか。 */
    public boolean deletes(boolean abended) {
        return (abended ? abnormal : normal) == Action.DELETE;
    }
}
