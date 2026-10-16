package idv.alexlin7.tw.bancho;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
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
     * 等 ApplicationReadyEvent 才連線：避免在 context refresh 途中做網路 I/O，
     * refresh 失敗時也不會留下已建立的連線。設 {@code osu.irc.auto-start=false} 可關閉（例如測試）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "osu.irc", name = "auto-start", havingValue = "true", matchIfMissing = true)
    public ApplicationListener<ApplicationReadyEvent> osuIrcAutoStarter(OsuIrcClient client) {
        return event -> client.start();
    }
}
