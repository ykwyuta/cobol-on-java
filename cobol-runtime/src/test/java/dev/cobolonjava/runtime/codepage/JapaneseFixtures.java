package dev.cobolonjava.runtime.codepage;

/**
 * 日本語の検体。
 *
 * <p><b>置いてある理由</b>: 日本語を試すたびに各テストがバイト列を書き下すと、どれが
 * 処理系の振る舞いでどれが検体の写し間違いか分からなくなる。検体は 1 か所に置き、
 * <b>出どころを書く</b>。
 *
 * <p><b>この数字の出どころ</b>: JDK の {@code x-IBM930} / {@code x-IBM939} の変換表である。
 * IBM CDRA の表と一致するかは<b>確かめていない</b> (暫定判断 P-012)。したがって
 * 検査で当てにしてよいのは次の 2 つだけである。
 * <ul>
 *   <li><b>往復すること</b> — 符号化して復号すると元の文字に戻る</li>
 *   <li><b>構造</b> — 2 バイト文字はシフトアウト {@code X'0E'} とシフトイン {@code X'0F'}
 *       の間に置かれ、1 文字あたり 2 バイトを占める</li>
 * </ul>
 * バイトの値そのものを期待値に書いた検査は、CDRA と突き合わせるまで
 * <b>「JDK の表がこうである」以上のことを言っていない</b>。そう読めるように印を付けてある。
 *
 * <p>3 つのコードページで<b>同じ</b>バイトになるもの (空白 {@code X'40'}、数字
 * {@code X'F0'}〜{@code X'F9'}、英大文字) は、日本語を混ぜた検査でも期待値に書いてよい。
 * これは EBCDIC の不変部分であり、CDRA との差は出ない。
 */
public final class JapaneseFixtures {

    private JapaneseFixtures() {
    }

    /** 氏名。全角 4 文字。IBM-930 で 10 バイト (SO + 8 + SI)。 */
    public static final String NAME = "山田太郎";

    /** {@link #NAME} を IBM-930 で符号化したバイト。<b>JDK の表に基づく。CDRA 未照合。</b> */
    public static final String NAME_930_HEX = "0E4565456345AB456E0F";

    /** 地名。全角 2 文字。IBM-939 で 6 バイト (SO + 4 + SI)。 */
    public static final String CITY = "東京";

    /** {@link #CITY} を IBM-939 で符号化したバイト。<b>JDK の表に基づく。CDRA 未照合。</b> */
    public static final String CITY_939_HEX = "0E455745750F";

    /** 品名。全角 3 文字。IBM-930 で 8 バイト (SO + 6 + SI)。 */
    public static final String ITEM = "テスト";

    /** {@link #ITEM} を IBM-930 で符号化したバイト。<b>JDK の表に基づく。CDRA 未照合。</b> */
    public static final String ITEM_930_HEX = "0E4394438E43950F";

    /**
     * 半角カタカナ 3 文字。混在コードページでも<b>1 バイト</b>で表され、シフトコードを伴わない。
     * 全角と半角で扱いが変わることを検査に出すために要る。
     */
    public static final String HALFWIDTH = "ｱｲｳ";
}
