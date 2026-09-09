package dev.cobolonjava.runtime.abend;

/**
 * 異常終了コード (要件 FR-141)。
 *
 * <p>ホストは実行を打ち切るとき、<b>何が起きたのかを 4 桁で言う</b>。{@code S} で始まる
 * ものはシステムが検出したもの、{@code U} で始まるものはプログラムや言語環境が自分で
 * 立てたものである。運用はこのコードを見て次にやることを決めるので、コードが合っていない
 * 実装は、動いても使えない。
 *
 * <h2>コードは条件そのものが持つ</h2>
 * <p>どの例外がどのコードになるかを 1 か所の対応表にすると、条件を足したときに<b>表を直し
 * 忘れる</b>。そうではなく、条件を表す例外自身が {@link AbendCause} としてコードを名乗る。
 */
public enum AbendCode {

    /** 10 進演算例外。数値項目に数値でないバイトが入っていた。 */
    S0C7("S0C7", "data exception"),

    /** 10 進除算例外。ゼロで割ったか、商が入りきらなかった。 */
    S0CB("S0CB", "decimal divide exception"),

    /** 保護例外。渡されていない連絡節の項目を参照した。 */
    S0C4("S0C4", "protection exception"),

    /** 実行不可能命令。解決できない呼び先へ飛んだ。 */
    S0C1("S0C1", "operation exception"),

    /** 呼び先のロードモジュールが見つからない。 */
    S806("S806", "module not found"),

    /**
     * データセットを開けなかった。
     *
     * <p>割当ては通ったのに開けない、というところに立つ (要件 FR-113)。区分データセットの
     * <b>メンバが無い</b>か、区分データセットをメンバを言わずに開こうとした。データセット
     * そのものはあるので割当ての段では分からず、開く段で初めて分かる。
     */
    S013("S013", "open failed"),

    /**
     * 入出力の誤り。
     *
     * <p>装置に届かなかったか、届いた中身が<b>データセットの記述と合っていなかった</b>。
     * 固定長なのに長さが割り切れない、可変長なのに {@code RDW} がつながらない、という
     * 読めない形がここへ来る。
     */
    S001("S001", "input/output error"),

    /**
     * 出力データセットを広げられなかった。
     *
     * <p>ジョブが割り当てた領域を使い切ったということである。
     */
    S037("S037", "output data set error"),

    /**
     * 言語環境が検出した条件。
     *
     * <p>{@code SSRANGE} の範囲外参照や、{@code FILE STATUS} を書いていないファイルの異常が
     * ここへ来る。ホストでもメッセージ ({@code IGZ0006S} など) を出してこのコードで終わる。
     */
    U4038("U4038", "condition detected by the language environment");

    private final String text;
    private final String reason;

    AbendCode(String text, String reason) {
        this.text = text;
        this.reason = reason;
    }

    /** {@code S0C7} のような 4 桁の綴り。 */
    public String text() {
        return text;
    }

    /** 何が起きたのか。覚え書きに書く。 */
    public String reason() {
        return reason;
    }

    /**
     * 綴りから読む。
     *
     * @return 知らない綴りは {@code null}
     */
    public static AbendCode of(String written) {
        if (written == null) {
            return null;
        }
        String upper = written.trim().toUpperCase(java.util.Locale.ROOT);
        for (AbendCode code : values()) {
            if (code.text.equals(upper)) {
                return code;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return text;
    }
}
