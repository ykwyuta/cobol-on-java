package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsSecurityPort;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code cobol.cics.security.mode=demo} のとき、デモ環境の簡易認証を構成する (設計 84、暫定判断 P-145)。
 *
 * <ul>
 *   <li>{@link DemoCicsSecurity}: 利用者の一覧で principal を CICS の user ID にし、transaction の attach と START USERID の
 *       代理を確かめる。coordinator が使う</li>
 *   <li>Spring Security が classpath にあれば、一覧のログイン名とパスワードの {@link UserDetailsService} と、すべての要求に
 *       認証を求める form login の {@link SecurityFilterChain}。利用者が bean を置けばそれを使う</li>
 * </ul>
 *
 * <p>本番の利用者管理の代わりにはならないので、起動の記録に警告を出す。
 */
@AutoConfiguration(before = CicsTaskAutoConfiguration.class, beforeName = {
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"})
@ConditionalOnProperty(prefix = "cobol.cics.security", name = "mode", havingValue = "demo")
@EnableConfigurationProperties(CicsSecurityProperties.class)
// 入れ子を @Configuration にすると利用者の component scan に拾われるので、@Import で読む
@Import(CicsDemoSecurityAutoConfiguration.SpringSecurity.class)
public class CicsDemoSecurityAutoConfiguration {

    private static final Log LOG = LogFactory.getLog(CicsDemoSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CicsSecurityPort.class)
    DemoCicsSecurity cobolDemoCicsSecurity(CicsSecurityProperties properties) {
        DemoCicsSecurity security = new DemoCicsSecurity(properties.users());
        LOG.warn("CICS demo security is enabled with " + properties.users().size()
                + " configured users; it is intended for demo environments only");
        return security;
    }

    @ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
    static class SpringSecurity {

        /** 一覧のログイン名とパスワード。パスワードの照合は Spring Security の既定の DelegatingPasswordEncoder が行う。 */
        @Bean
        @ConditionalOnMissingBean(UserDetailsService.class)
        InMemoryUserDetailsManager cobolDemoUsers(CicsSecurityProperties properties) {
            return new InMemoryUserDetailsManager(new DemoCicsSecurity(properties.users()).loginUsers().stream()
                    .map(user -> User.withUsername(user.username()).password(user.password())
                            .authorities("ROLE_CICS_USER").build())
                    .toList());
        }

        /** すべての要求に認証を求め、form login と logout を使う。CSRF は Spring Security の既定のまま有効。 */
        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        SecurityFilterChain cobolDemoSecurityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults())
                    .logout(Customizer.withDefaults())
                    .build();
        }
    }
}
