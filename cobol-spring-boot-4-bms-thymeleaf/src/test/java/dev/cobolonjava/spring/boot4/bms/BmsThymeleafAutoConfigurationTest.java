package dev.cobolonjava.spring.boot4.bms;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BmsThymeleafAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BmsThymeleafAutoConfiguration.class));

    @Test
    @DisplayName("Thymeleafがあれば表示モデルと入力の部品を構成し、HTTP入口は構成しない")
    void configuresRenderingParts() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BmsScreenViewFactory.class);
            assertThat(context).hasSingleBean(BmsTerminalInputBinder.class);
        });
    }

    @Test
    @DisplayName("端末のコードページは既定でIBM-1047、propertyで替えられ、知らない名前は断る")
    void resolvesTheTerminalCodePage() {
        runner.run(context -> assertThat(context.getBean(BmsTerminalRepertoire.class).codePage())
                .isEqualTo(CodePages.IBM_1047));

        runner.withPropertyValues("cobol.cics.bms.code-page=930")
                .run(context -> assertThat(context.getBean(BmsTerminalRepertoire.class).codePage())
                        .isEqualTo(CodePages.IBM_930));

        // 綴り違いが黙って既定へ倒れると、client が実際とは違う文字を弾く
        runner.withPropertyValues("cobol.cics.bms.code-page=IBM-9999")
                .run(context -> assertThat(context).hasFailed());

        // 利用者が CodePage の bean を置いていればそれを使う
        runner.withBean(CodePage.class, () -> CodePages.IBM_939)
                .run(context -> assertThat(context.getBean(BmsTerminalRepertoire.class).codePage())
                        .isEqualTo(CodePages.IBM_939));
    }
}
