package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

/**
 * 試験の COBOL ソースを固定形式で組み立てる。
 *
 * <p>置いてある理由は 1 つだけである。<b>固定形式は 72 桁で切れる。</b>試験のソースは
 * 7 桁目から書き出すので、65 文字を超えた行は黙って詰められる。詰められた行は
 * 構文誤りになり、<b>試験が処理系の失敗を作る</b>。
 *
 * <p>実際に 2 度やった。入れ子の組み込み関数を書いた試験と、{@code SEARCH ALL} を
 * 1 行に書いた試験である。どちらも文法のあいまいさを疑って掘ってから、行が長すぎた
 * だけだと分かった。<b>検査の道具が、処理系の失敗を作ってはならない。</b>
 */
final class FixedFormatSource {

    /** 7 桁目から書き出したときに 72 桁に収まる長さ。 */
    private static final int WIDTH = 72 - 7 + 1;

    private FixedFormatSource() {
    }

    /**
     * 見出し部・データ部・手続き部を組み立てる。
     *
     * @param storage   作業場所の記述項
     * @param procedure 手続き部の行
     */
    static String program(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            append(sb, line);
        }
        for (String line : storage) {
            append(sb, line);
        }
        append(sb, "PROCEDURE DIVISION.");
        for (String line : procedure) {
            append(sb, line);
        }
        return sb.toString();
    }

    /** 1 行を B 領域へ書き足す。長すぎれば試験のほうを落とす。 */
    static void append(StringBuilder sb, String line) {
        assertTrue(line.length() <= WIDTH,
                () -> "the test source runs past column 72: " + line);
        sb.append("       ").append(line).append('\n');
    }
}
