package dev.cobolonjava.verify.ccvs85;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code ACCEPT} の検査へ流し込む入力の札 (要件 NFR-040)。
 *
 * <p>CCVS85 の {@code ACCEPT} の検査は、<b>卓の人が決まった値を打ち込む</b>ことを
 * 前提にしている。ホストではその値を {@code SYSIN} の札束として渡す。道具に人はいない
 * ので、同じ札束をここに置く。差し込み札 (X-card) と同じく<b>道具の側の道具立て</b>で
 * ある。
 *
 * <h2>値はプログラムが決めている</h2>
 * <p>札の中身をこちらで考えたわけではない。検査は {@code ACCEPT} のすぐあとで
 * <b>対になる項目と比べている</b>ので、打ち込むべき値は原文に書かれている。
 *
 * <pre>
 * 02 ACCEPT-D3 PICTURE 9(10) USAGE DISPLAY.
 * 02 ACCEPT-D4 PICTURE 9(10) USAGE DISPLAY VALUE 0123456789.
 *     ...
 *     ACCEPT ACCEPT-D3.
 *     IF ACCEPT-D3 EQUAL TO ACCEPT-D4 PERFORM PASS.
 * </pre>
 *
 * <p>札に書くのは {@code 0123456789} である。<b>合否を決めているのはプログラムの
 * ほうであって、札ではない</b>。転記の規則を間違えていれば、正しい値を流し込んでも
 * 落ちる。
 *
 * <h2>1 枚は 80 桁である</h2>
 * <p>受取項目が 80 桁に収まらなければ、{@code ACCEPT} は<b>収まるまで札を読む</b>。
 * NC204M の ACC-TEST-F1-13 は 200 桁の項目を 1 回で埋め、期待する中身の 0 桁目・
 * 80 桁目・160 桁目に {@code D} を置いている。3 枚の札の先頭である。
 */
public final class OperatorInput {

    private OperatorInput() {
    }

    /** 27 桁の英数字。NC109M と NC204M が同じものを使う。 */
    private static final String ALPHABET_27 = "ABCDEFGHIJKLMNOPQRSTUVWXY Z";
    /** 20 桁の英字。前後と途中に空白が入る。 */
    private static final String ALPHABETIC_20 = " ABC            XYZ ";
    /** 特殊文字を並べた 11 桁。 */
    private static final String SPECIALS_11 = "().+-*/$, =";
    /** 80 桁の項目へ入れる 1 枚。残りは転記の規則で空白になる。 */
    private static final String SPACED_ALPHABET =
            "A B C D E F G H I J K L M N O P Q R S T U V W X Y Z  0123456789";

    /**
     * 200 桁の項目を埋める 3 枚 (NC204M の ACC-TEST-F1-13)。
     *
     * <p>期待する中身は {@code D001*002...*050} という 4 桁ごとの並びで、80 桁ごとに
     * {@code D} が来る。札の先頭だけが {@code D} である。
     */
    private static final List<String> TWO_HUNDRED = List.of(
            "D001*002*003*004*005*006*007*008*009*010*011*012*013*014*015*016*017*018*019*020",
            "D021*022*023*024*025*026*027*028*029*030*031*032*033*034*035*036*037*038*039*040",
            "D041*042*043*044*045*046*047*048*049*050");

    /**
     * プログラムごとの札束。打ち込む順に並ぶ。
     *
     * <p>並びは原文の {@code ACCEPT} が現れる順である。1 つでもずれると<b>そのあとが
     * 全部ずれる</b>ので、対になる項目の名前を注釈に残す。
     */
    private static final Map<String, List<String>> DECKS = Map.of(
            // NC109M: 形式 1 の ACCEPT を 11 件
            "NC109M", List.of(
                    ALPHABET_27,        // ACCEPT-D1  X(27)  = ACCEPT-D2
                    "0123456789",       // ACCEPT-D3  9(10)  = ACCEPT-D4
                    SPECIALS_11,        // ACCEPT-D5  X(11)  = ACCEPT-D6
                    "9",                // ACCEPT-D7  X      = ACCEPT-D8
                    "0",                // ACCEPT-D9  X      = ACCEPT-D10
                    ALPHABETIC_20,      // ACCEPT-D11 A(20)  = ACCEPT-D12
                    "012345678",        // ACCEPT-D13 9(9)   = ACCEPT-D14
                    " ",                // ACCEPT-D15 X      = ACCEPT-D16 (SPACE)
                    "\"",               // ACCEPT-D17 X      = ACCEPT-D18 (QUOTE)
                    "ABCD",             // TAB-ACCEPT (2)    → "....ABCD...."
                    SPACED_ALPHABET),   // X80-CHARACTER-FIELD = ACCEPT-RESULTS
            // NC204M: 同じ形式を SYSIN の呼び名ごしに 15 件
            "NC204M", concat(List.of(
                    ALPHABET_27,        // ACCEPT-D1  X(27)  = ACCEPT-D2
                    "0123456789",       // ACCEPT-D3  9(10)  = ACCEPT-D4
                    SPECIALS_11,        // ACCEPT-D5  X(11)  = ACCEPT-D6
                    "9",                // ACCEPT-D7  X      = ACCEPT-D8
                    "0",                // ACCEPT-D9  X      = ACCEPT-D10
                    ALPHABETIC_20,      // ACCEPT-D11 A(20)  = ACCEPT-D12
                    " 9",               // ACCEPT-D15 XX     = ACCEPT-D16
                    "\"",               // ACCEPT-D17 X      = ACCEPT-D18 (QUOTE)
                    "Q",                // ACCEPT-D19 X      = ACCEPT-D20
                    "ABCD",             // TAB-ACCEPT (2)    → "....ABCD...."
                    "ABCD",             // TAB-A (5)         → "----------------ABCD"
                    SPACED_ALPHABET),   // 80X-CHARACTER-FIELD = ACCEPT-RESULTS
                    TWO_HUNDRED,        // ACCEPT-D13 X(200) = DISPLAY-F
                    List.of(
                    // ACCEPT-TEST-14-DATA X(15) を 2 度読む。名前は 11〜15 桁と
                    // 言っているが、REDEFINES は<b>どちらも先頭から</b>重ねてあるので、
                    // 2 度目に見るのも先頭の 5 桁である。札は 2 枚に分かれる
                    "ABCDEFGHIJ",       // 1 度目: ACC-14-CHARS-1-10  = "ABCDEFGHIJ"
                    "KLMNO")));         // 2 度目: ACC-14-CHARS-11-15 = "KLMNO"

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> out = new java.util.ArrayList<>();
        for (List<String> part : parts) {
            out.addAll(part);
        }
        return List.copyOf(out);
    }

    /**
     * そのプログラムへ流し込む札束を、1 枚ずつ返すものにする。
     *
     * <p>札束を持っていないプログラムでは<b>最初から尽きている</b>ものを返す。
     * 卓に人はいないので、標準入力を読ませてはならない。読ませると、道具をどう
     * 起動したかで止まるか止まらないかが変わってしまう。
     *
     * @param name 原文の名前 ({@code NC109M.cbl} のような形でよい)
     */
    public static Supplier<String> forProgram(String name) {
        String key = name.toUpperCase(Locale.ROOT).replace(".CBL", "");
        int comma = key.indexOf(',');
        if (comma >= 0) {
            key = key.substring(0, comma);
        }
        Deque<String> deck = new ArrayDeque<>(DECKS.getOrDefault(key, List.of()));
        return deck::poll;
    }
}
