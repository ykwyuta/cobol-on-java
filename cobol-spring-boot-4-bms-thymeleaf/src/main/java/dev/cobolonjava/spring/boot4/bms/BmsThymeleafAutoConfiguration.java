package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

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

    /**
     * ブラウザへ渡す文字の一覧 (設計 81 §5.1)。
     *
     * <p>コードページは、利用者が {@link CodePage} の bean を置いていればそれを、置いていなければ
     * {@code cobol.cics.bms.code-page} の値を使う。どちらも無ければ実行時の既定 (IBM-1047) にする。
     * 知らない名前は {@code CodePages.forName} が断るので、綴り違いが黙って既定へ倒れることはない。
     *
     * <p><b>実行時のコードページと同じものでなければならない</b>。生成コードが使うコードページは
     * {@code CobolRuntime} が持っており、bean として見えるとは限らないので、ここでは突き合わせられない。
     * 食い違っても server の判定 (RECEIVE MAP) は正しいままだが、client の弾き方が実際と違うものになる
     * (暫定判断 P-180)。
     */
    @Bean
    @ConditionalOnMissingBean
    BmsTerminalRepertoire cobolBmsTerminalRepertoire(ObjectProvider<CodePage> codePage,
                                                     Environment environment) {
        return new BmsTerminalRepertoire(codePage.getIfAvailable(() -> {
            String name = environment.getProperty("cobol.cics.bms.code-page");
            return name == null || name.isBlank() ? CodePages.DEFAULT : CodePages.forName(name);
        }));
    }
}
