package dev.cobolonjava.verify.ccvs85;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.verify.VerifySupport;
import dev.cobolonjava.verify.corpus.CorpusReport;
import dev.cobolonjava.verify.corpus.CorpusRunner;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 本物の CCVS85 を流す (要件 NFR-040)。
 *
 * <p>配布物は同梱しないので、{@code CCVS85} が指していなければスキップする。
 *
 * <h2>合格率は試験しない</h2>
 * <p>ここで確かめるのは<b>道具が正しく起こせているか</b>だけである。合格率に下限を
 * 課すと、処理系が育つまでビルドが赤くなり、<b>数を取り続けられなくなる</b>。数は
 * 記録して並べるものであって、通す・落とすの門にするものではない。
 */
@Tag("V1")
class Ccvs85SuiteTest {

    /** 7 桁目に残っていてよい文字。ここに無いものが残っていれば起こし損ねている。 */
    private static final String SOUND_INDICATORS = " */-D";

    /**
     * 埋まっていない差し込み札の行か。
     *
     * <p>{@code CALL "XXXXXXXX"} のような<b>ただの文字定数</b>と取り違えないよう、
     * 12 桁目から 19 桁目までの形をそのまま見る。番号でないものは札ではない。
     */
    private static boolean card(String line) {
        if (line.length() < 19) {
            return false;
        }
        return line.startsWith("XXXX", 11) && line.charAt(15) == 'X'
                && line.substring(16, 19).chars().allMatch(Character::isDigit);
    }

    @Test
    @DisplayName("配布物は 400 本を超える検査プログラムを持つ (NFR-040)")
    void theArchiveHoldsTheWholeSuite() {
        Path archive = VerifySupport.requireCcvs85();

        Ccvs85Archive read = Ccvs85Archive.read(archive);

        assertTrue(read.programs().size() > 400, "programs: " + read.programs().size());
        assertTrue(read.members().stream()
                .anyMatch(m -> m.kind() == Ccvs85Archive.Kind.COPYBOOK), "no copybooks");
    }

    @Test
    @DisplayName("起こしたあとの 7 桁目に英字は残らない (NFR-040)")
    void populationLeavesNoOptionalIndicator() {
        // 残っていれば「7 桁目が読めない」と処理系が言う。それは処理系のせいではない
        Path archive = VerifySupport.requireCcvs85();

        Ccvs85Suite.Prepared prepared = Ccvs85Suite.prepare(archive,
                Population.plain(XCards.defaults()));

        for (CorpusRunner.Source source : prepared.sources()) {
            for (String line : source.text().split("\n")) {
                if (line.length() < 7) {
                    continue;
                }
                char indicator = line.charAt(6);
                assertTrue(SOUND_INDICATORS.indexOf(indicator) >= 0,
                        source.name() + ": indicator '" + indicator + "' in " + line);
            }
        }
    }

    @Test
    @DisplayName("流すものに埋まっていない差し込み札は無い (NFR-040)")
    void nothingWithAMissingCardIsRun() {
        Path archive = VerifySupport.requireCcvs85();

        Ccvs85Suite.Prepared prepared = Ccvs85Suite.prepare(archive,
                Population.plain(XCards.defaults()));

        for (CorpusRunner.Source source : prepared.sources()) {
            for (String line : source.text().split("\n")) {
                assertFalse(card(line), source.name() + ": " + line);
            }
        }
        // 入出力のモジュールは札を用意していないので、流さないほうへ回る
        assertFalse(prepared.skipped().isEmpty(), "nothing skipped");
    }

    @Test
    @DisplayName("同じ原本名を 2 つの置き場から引き分けられる (NFR-040)")
    void theSameTextNameIsResolvedFromTwoLibraries() {
        // SM207A は COPY ALTLB OF <X-47> と COPY ALTLB IN <X-48> を書き、
        // <b>違う中身が来ること</b>を確かめる。置き場の名前を決めるのは差し込み札
        // なので、原本と置き場の結び付けは道具の側が決めるほかない。
        // 決めておかないと 2 つめが 1 つめと同じ原本を引き、道具が処理系の失敗を作る
        Path archive = VerifySupport.requireCcvs85();
        XCards cards = XCards.defaults();

        Ccvs85Suite.Prepared prepared = Ccvs85Suite.prepare(archive, Population.plain(cards));
        CopyBookResolver resolver = prepared.resolver();

        String first = resolver.resolve("ALTLB", cards.text(47)).orElseThrow().text();
        String second = resolver.resolve("ALTLB", cards.text(48)).orElseThrow().text();

        assertTrue(first.contains("PERFORM PASS"), first);
        assertTrue(second.contains("WRONG LIBRARY"), second);
    }

    @Test
    @DisplayName("入出力以外のモジュールだけを数え直せる (NFR-040)")
    void theAcceptanceCriterionCountsNonIoModulesOnly() {
        Path archive = VerifySupport.requireCcvs85();
        Ccvs85Suite.Prepared prepared = Ccvs85Suite.prepare(archive,
                Population.plain(XCards.defaults()));

        CorpusReport all = CorpusRunner.with(prepared.resolver()).run(prepared.sources());
        CorpusReport nonIo = all.only(Ccvs85Suite.NON_IO);

        assertEquals(prepared.sources().size(), all.outcomes().size());
        assertTrue(nonIo.outcomes().size() <= all.outcomes().size());
        assertTrue(nonIo.byGroup().keySet().stream().allMatch(Ccvs85Suite.NON_IO::contains),
                nonIo.byGroup().keySet().toString());
        // 数そのものは試験しない。記録して並べるものである
        assertTrue(nonIo.rate() >= 0);
    }
}
