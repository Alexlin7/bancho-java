package idv.alexlin7.tw.bancho.match;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import idv.alexlin7.tw.bancho.irc.IrcSender;

@Configuration
@EnableConfigurationProperties(MatchProperties.class)
public class MatchConfiguration {

    /** MatchService 不是 IrcMessageListener，直接注入 IrcSender 不會形成循環依賴。 */
    @Bean
    public MatchService matchService(IrcSender sender, MatchProperties properties) {
        return new MatchService(sender, properties.createTimeout());
    }
}
