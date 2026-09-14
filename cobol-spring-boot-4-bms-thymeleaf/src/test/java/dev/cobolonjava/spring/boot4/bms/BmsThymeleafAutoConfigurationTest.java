package dev.cobolonjava.spring.boot4.bms;

import static org.assertj.core.api.Assertions.assertThat;

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
}
