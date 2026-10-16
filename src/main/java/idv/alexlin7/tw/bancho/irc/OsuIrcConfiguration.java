package idv.alexlin7.tw.bancho.irc;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OsuIrcProperties.class)
public class OsuIrcConfiguration {

    /**
     * 所有 {@link IrcMessageListener} bean 都會註冊進 client，順序依 {@code @Order}。
     */
    @Bean(destroyMethod = "close")
    public OsuIrcClient osuIrcClient(OsuIrcProperties properties, ObjectProvider<IrcMessageListener> listeners) {
        return new OsuIrcClient(properties, listeners.orderedStream().toList());
    }

    /**
     * context refresh 完成時連線（在 ApplicationRunner 之前），關閉時送出 QUIT。
     * 設 {@code osu.irc.auto-start=false} 可關閉（例如測試）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "osu.irc", name = "auto-start", havingValue = "true", matchIfMissing = true)
    OsuIrcLifecycle osuIrcLifecycle(OsuIrcClient client) {
        return new OsuIrcLifecycle(client);
    }
}
