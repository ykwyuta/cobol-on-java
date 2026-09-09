package dev.cobolonjava.verify.ccvs85;

import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.MapCopyBookResolver;
import dev.cobolonjava.verify.corpus.CorpusRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CCVS85 を処理系へ流す (要件 NFR-040)。
 *
 * <p>配布物を部品へ切り、起こして、翻訳にかけるところまでをまとめる。
 *
 * <h2>入出力以外のモジュールが基準である</h2>
 * <p>要件 13 章が P0-b の受け入れ基準に置いているのは<b>入出力以外のモジュールの合格率
 * 95% 以上</b>である。入出力のモジュール ({@code SQ} / {@code RL} / {@code IX} /
 * {@code ST}) は、翻訳できても<b>動かしてみないと</b>合っているか分からないうえ、
 * 装置の結び付けを差し込み札で決めなければならない。段が違う。
 *
 * <p>だから合格率は全体と、入出力以外の分と、両方を数える。片方だけを見せると、
 * 都合のよいほうを選んだように見える。
 */
public final class Ccvs85Suite {

    private Ccvs85Suite() {
    }

    /**
     * 入出力以外の検査モジュール。
     *
     * <ul>
     *   <li>{@code NC} — 基本機能 (核)
     *   <li>{@code IC} — プログラム間の呼び出し
     *   <li>{@code IF} — 組み込み関数
     *   <li>{@code SM} — 原文の操作 ({@code COPY} / {@code REPLACE})
     *   <li>{@code OB} — 廃要素
     *   <li>{@code DB} — デバッグ
     *   <li>{@code SG} — 段量化 ({@code SEGMENT-LIMIT})
     * </ul>
     */
    public static final List<String> NON_IO = List.of("NC", "IC", "IF", "SM", "OB", "DB", "SG");

    /** 配布物そのものを起こすためのプログラム。検査ではないので流さない。 */
    private static final String POPULATION = "EXEC85";

    /**
     * 起こした結果。
     *
     * @param skipped 差し込み札が足りず、流さなかったもの。名前 → 足りない番号
     */
    public record Prepared(List<CorpusRunner.Source> sources, Map<String, List<Integer>> skipped,
                           Map<String, String> copybooks, XCards cards) {

        /** 写し句を引ける形にする。CCVS85 の {@code SM} モジュールが使う。 */
        public CopyBookResolver resolver() {
            MapCopyBookResolver resolver = new MapCopyBookResolver();
            copybooks.forEach(resolver::put);
            for (Placement placement : PLACEMENTS) {
                String library = cards.text(placement.card());
                String text = copybooks.get(placement.member());
                if (library != null && text != null) {
                    resolver.put(library, placement.textName(), text);
                }
            }
            return resolver;
        }
    }

    /**
     * 原本を置き場へ置く指示。
     *
     * @param card     置き場の名前を決める差し込み札の番号
     * @param textName 置き場の中での原本の名前
     * @param member   配布物の中の原本の名前
     */
    private record Placement(int card, String textName, String member) {
    }

    /**
     * 原本と置き場の結び付け (要件 NFR-040)。
     *
     * <p>配布物の原本はふつう 1 つの置き場に入っていればよい。ただし SM207A だけは
     * <b>同じ原本名を 2 つの置き場から引き分ける</b>ことを試す。指示は SM207A の冒頭に
     * 文章で書いてある。
     *
     * <pre>
     * X-47 の置き場 ← 原本 ALTLB
     * X-48 の置き場 ← 原本 ALTL1 を、ALTLB という名前で
     * </pre>
     *
     * <p>置き場の名前を決めるのは差し込み札 047 / 048 であり、そこに何と書くかは
     * こちらの自由である。だから結び付けも道具の側で決めるほかない。決めておかないと
     * 2 つめの {@code COPY ALTLB IN <X-48>} が 1 つめと同じ原本を引き、
     * <b>道具が処理系の失敗を作る</b> (SM207A QUAL-TEST-02)。
     */
    private static final List<Placement> PLACEMENTS = List.of(
            new Placement(47, "ALTLB", "ALTLB"),
            new Placement(48, "ALTLB", "ALTL1"));

    /**
     * 配布物を読み、翻訳にかけられる形へ起こす。
     *
     * <p>写し句も<b>同じように起こす</b>。7 桁目の印も差し込み札も、写し句の中に同じよう
     * に入っているからである。起こさずに渡すと、{@code COPY} した先で「7 桁目が読めない」
     * と言われる。処理系ではなく道具の落ち度である。
     */
    public static Prepared prepare(Path archive, Population population) {
        Ccvs85Archive read = Ccvs85Archive.read(archive);
        List<CorpusRunner.Source> sources = new ArrayList<>();
        Map<String, List<Integer>> skipped = new LinkedHashMap<>();
        Map<String, String> copybooks = new LinkedHashMap<>();
        for (Ccvs85Archive.Member member : read.members()) {
            if (member.kind() == Ccvs85Archive.Kind.COPYBOOK) {
                copybooks.put(member.name(), population.apply(member).text());
            }
        }
        for (Population.Program program : population.populate(read)) {
            if (program.name().equals(POPULATION)) {
                continue;
            }
            if (!program.missing().isEmpty()) {
                // 札が足りないまま流すと、処理系の失敗を道具が作ることになる
                skipped.put(program.name(), List.copyOf(program.missing()));
                continue;
            }
            sources.add(new CorpusRunner.Source(program.name() + ".cbl", program.module(),
                    program.source()));
        }
        return new Prepared(List.copyOf(sources), Map.copyOf(skipped), Map.copyOf(copybooks),
                population.cards());
    }
}
