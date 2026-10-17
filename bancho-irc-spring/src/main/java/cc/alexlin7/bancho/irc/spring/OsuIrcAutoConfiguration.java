package cc.alexlin7.bancho.irc.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import cc.alexlin7.bancho.irc.IrcMessageListener;
import cc.alexlin7.bancho.irc.OsuIrcClient;

/**
 * 以 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 註冊，
 * 應用程式不必掃描 {@code cc.alexlin7.bancho} 也會載入。
 */
@AutoConfiguration
@Import(OsuIrcAutoConfiguration.DefaultClientConfiguration.class)
public class OsuIrcAutoConfiguration {

    /**
     * context refresh 完成時連線（在 ApplicationRunner 之前），關閉時送出 QUIT；自訂的 client 也適用。
     * 設 {@code osu.irc.auto-start=false} 可關閉（例如測試）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "osu.irc", name = "auto-start", havingValue = "true", matchIfMissing = true)
    OsuIrcLifecycle osuIrcLifecycle(OsuIrcClient client) {
        return new OsuIrcLifecycle(client);
    }

    /**
     * 使用者自己定義 {@link OsuIrcClient} 時，{@code osu.irc.*} 的綁定要一起退出，否則仍會要求填寫帳密。
     * <p>
     * 刻意不加 {@code @Configuration}：應用程式的元件掃描涵蓋本套件時（auto-configuration 本身會被排除），
     * 帶註解的內層類別會被當成一般設定提早處理，{@code @ConditionalOnMissingBean} 就看不到使用者的 bean。
     * 只經由上面的 {@code @Import} 載入，才能保證在使用者設定之後判斷。
     */
    @ConditionalOnMissingBean(OsuIrcClient.class)
    @EnableConfigurationProperties(OsuIrcProperties.class)
    static class DefaultClientConfiguration {

        /**
         * 所有 {@link IrcMessageListener} bean 都會註冊進 client，順序依 {@code @Order}。
         */
        @Bean(destroyMethod = "close")
        OsuIrcClient osuIrcClient(OsuIrcProperties properties, ObjectProvider<IrcMessageListener> listeners) {
            return new OsuIrcClient(properties.toSettings(), listeners.orderedStream().toList());
        }
    }
}
