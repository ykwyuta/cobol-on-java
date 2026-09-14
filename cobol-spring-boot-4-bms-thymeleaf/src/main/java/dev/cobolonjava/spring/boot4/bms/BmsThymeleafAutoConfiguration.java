package dev.cobolonjava.spring.boot4.bms;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * BMS 画面の Thymeleaf 描画に使う部品を構成する (設計 77 §3.1)。
 *
 * <p>HTTP 入口 ({@code POST /cics/{transid}}) は {@link CicsBrowserAutoConfiguration} が、Spring Security が
 * あるときだけ構成する (設計 81 §5)。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.thymeleaf.TemplateEngine")
public class BmsThymeleafAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    BmsScreenViewFactory cobolBmsScreenViewFactory() {
        return new BmsScreenViewFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    BmsTerminalInputBinder cobolBmsTerminalInputBinder() {
        return new BmsTerminalInputBinder();
    }
}
