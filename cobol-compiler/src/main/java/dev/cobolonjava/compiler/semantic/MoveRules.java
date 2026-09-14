package dev.cobolonjava.compiler.semantic;

/**
 * {@code MOVE} の分類の組み合わせ (要件 FR-060)。
 *
 * <p>どの組み合わせが書けるか、そして書けるとき<b>ランタイムのどの転記を呼ぶか</b>を決める。
 * 呼ぶ先が決まっていなければコードは生成できないので、この判断は翻訳時に済ませる。
 *
 * <h2>受取側の分類が転記の種類を決める</h2>
 * <table>
 *   <caption>受取側と転記の種類</caption>
 *   <tr><th>受取側</th><th>転記</th></tr>
 *   <tr><td>数値</td><td>{@link Kind#NUMERIC} — 小数点で位置を合わせる</td></tr>
 *   <tr><td>数字編集</td><td>{@link Kind#NUMERIC_EDITED} — 編集して書き込む</td></tr>
 *   <tr><td>それ以外</td><td>{@link Kind#ALPHANUMERIC} — バイト列として左右に詰める</td></tr>
 * </table>
 *
 * <p>ただし<b>どちらかが集団項目なら、必ずバイト列の転記になる</b>。集団項目は
 * 配下の基本項目が占めるバイト範囲そのものであり、転記は無変換で行われる (要件 FR-020)。
 * 受取側が数字編集項目でも、送出側が集団項目なら編集は起きない。
 *
 * <h2>書けない組み合わせは黙って通さない</h2>
 * <p>英字項目を数値へ、数字編集項目を数値へ移すような指定は、意味を決めようがない。
 * 通してしまうと<b>実行時に見当違いの値になる</b>ため、翻訳時に誤りとする。
 */
public final class MoveRules {

    private MoveRules() {
    }

    /** 転記の種類。ランタイムのどの関数を呼ぶかに対応する。 */
    public enum Kind {
        /** {@code Move.alphanumeric}。バイト列として詰める。 */
        ALPHANUMERIC,
        /** {@code Move.numeric}。小数点で位置を合わせる。 */
        NUMERIC,
        /** {@code Move.toNumericEdited}。編集結果を書き込む。 */
        NUMERIC_EDITED,
        /** {@code Move.toAlphanumericEdited}。挿入文字を置きながら詰める。 */
        ALPHANUMERIC_EDITED
    }

    /** 分類の組み合わせから転記の種類を決める。 */
    public static Kind kindOf(DataCategory sender, DataCategory receiver) {
        if (sender == DataCategory.GROUP || receiver == DataCategory.GROUP) {
            return Kind.ALPHANUMERIC;
        }
        if (receiver.isNumeric()) {
            return Kind.NUMERIC;
        }
        if (receiver == DataCategory.NUMERIC_EDITED) {
            return Kind.NUMERIC_EDITED;
        }
        // 英数字編集は挿入文字を置く。ただのバイト詰めでは B と 0 と / が消える
        return receiver == DataCategory.ALPHANUMERIC_EDITED
                ? Kind.ALPHANUMERIC_EDITED
                : Kind.ALPHANUMERIC;
    }

    /**
     * 送出側から受取側への転記が書けるかどうか。
     *
     * <p>集団項目はどちら側でも英数字として扱う。ただし変換を行わない点が違うため、
     * 受取側が集団項目なら送出側の分類を問わず書ける。
     */
    public static boolean isAllowed(DataCategory sender, DataCategory receiver) {
        if (receiver == DataCategory.GROUP || sender == DataCategory.GROUP) {
            // 集団項目はバイト範囲そのものであり、転記は無変換で行われる (要件 FR-020)
            return true;
        }
        return switch (receiver) {
            case ALPHABETIC -> sender == DataCategory.ALPHABETIC
                    || sender == DataCategory.ALPHANUMERIC
                    || sender == DataCategory.ALPHANUMERIC_EDITED;
            case ALPHANUMERIC, ALPHANUMERIC_EDITED -> sender != DataCategory.NUMERIC_NONINTEGER;
            // 数字編集項目から数値項目への転記は<b>編集を解く</b> (de-editing)。
            // 規格が認めている道であり、書いた文字の並びから値を取り出す
            case NUMERIC_INTEGER, NUMERIC_NONINTEGER -> sender.isNumeric()
                    || sender == DataCategory.ALPHANUMERIC
                    || sender == DataCategory.NUMERIC_EDITED;
            // 数字編集項目どうしは、送り側の編集を解いた値を受取側で編集し直す
            case NUMERIC_EDITED -> sender.isNumeric()
                    || sender == DataCategory.ALPHANUMERIC
                    || sender == DataCategory.NUMERIC_EDITED;
            case GROUP -> true;
        };
    }

    /** 書けない理由の説明。診断に添える。 */
    public static String reason(DataCategory sender, DataCategory receiver) {
        if (receiver.isNumeric() || receiver == DataCategory.NUMERIC_EDITED) {
            return sender == DataCategory.ALPHABETIC
                    ? "an alphabetic item has no numeric value"
                    : "a " + describe(sender) + " item cannot be moved to a numeric item";
        }
        if (receiver == DataCategory.ALPHABETIC) {
            return "only alphabetic and alphanumeric items can be moved to an alphabetic item";
        }
        // 小数を持つ数値を英数字へ移すと、小数点の位置がバイト列から失われる
        return "a non-integer numeric item cannot be moved to an alphanumeric item";
    }

    private static String describe(DataCategory category) {
        return switch (category) {
            case ALPHABETIC -> "alphabetic";
            case ALPHANUMERIC -> "alphanumeric";
            case ALPHANUMERIC_EDITED -> "alphanumeric-edited";
            case NUMERIC_INTEGER -> "integer";
            case NUMERIC_NONINTEGER -> "non-integer numeric";
            case NUMERIC_EDITED -> "numeric-edited";
            case GROUP -> "group";
        };
    }
}
