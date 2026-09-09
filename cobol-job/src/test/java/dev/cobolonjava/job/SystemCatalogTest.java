package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 目録そのもの (要件 FR-131、暫定判断 P-045 の解消)。
 *
 * <p>目録は<b>ジョブをまたいで残る</b>ものである。ジョブの中の話であれば覚えておくだけで
 * 済むが、{@code CATLG} が意味を持つのは次のジョブから見えることなので、置き場の上に
 * 書き留めねばならない。
 */
@Tag("V1")
class SystemCatalogTest {

    @TempDir
    Path volume;

    @Test
    @DisplayName("覚えのない名前は目録に載っているものとして扱う (FR-131)")
    void anUnknownNameIsTreatedAsCataloged() {
        // 置き場は人がファイルを置くディレクトリでもある。そこへ置いたものを目録に
        // 書かせるのでは道具として使えない
        assertTrue(new SystemCatalog(volume).isCataloged("HAND.DAT"));
    }

    @Test
    @DisplayName("外したものは載っていない (FR-133)")
    void whatWasUncatalogedIsNotFound() {
        SystemCatalog catalog = new SystemCatalog(volume);
        catalog.uncatalog("PAY.WORK");

        assertFalse(catalog.isCataloged("PAY.WORK"));
        // 置き場での場所は変わらない。外したのは目録の項目だけである
        assertEquals(volume.resolve("PAY.WORK"), catalog.onVolume("PAY.WORK"));
    }

    @Test
    @DisplayName("載せ直せばまた見える (FR-133)")
    void catalogingPutsItBack() {
        SystemCatalog catalog = new SystemCatalog(volume);
        catalog.uncatalog("PAY.WORK");
        catalog.catalog("PAY.WORK");

        assertTrue(catalog.isCataloged("PAY.WORK"));
    }

    @Test
    @DisplayName("目録はジョブをまたいで残る (FR-131)")
    void theCatalogSurvivesTheJob() {
        new SystemCatalog(volume).uncatalog("PAY.WORK");

        // 別のジョブが同じ置き場を開いたときに同じことが言える
        assertFalse(new SystemCatalog(volume).isCataloged("PAY.WORK"));
        assertTrue(Files.isReadable(volume.resolve(SystemCatalog.INDEX)));
    }

    @Test
    @DisplayName("項目を消せば、また覚えのない名前になる (FR-133)")
    void forgettingRestoresTheDefault() {
        SystemCatalog catalog = new SystemCatalog(volume);
        catalog.uncatalog("PAY.WORK");
        catalog.forget("PAY.WORK");

        assertTrue(catalog.isCataloged("PAY.WORK"));
        // 書き留めたものも消える。次に開いた目録にも覚えがない
        assertTrue(new SystemCatalog(volume).isCataloged("PAY.WORK"));
    }

    @Test
    @DisplayName("名前の大小は区別しない (FR-131)")
    void namesAreNotCaseSensitive() {
        SystemCatalog catalog = new SystemCatalog(volume);
        catalog.uncatalog("pay.work");

        assertFalse(catalog.isCataloged("PAY.WORK"));
    }
}
