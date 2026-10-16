package idv.alexlin7.tw.bancho.match;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param createTimeout 等待 BanchoBot 開房回覆的時間，預設 15 秒
 */
@ConfigurationProperties("bancho.match")
public record MatchProperties(Duration createTimeout) {

    public MatchProperties {
        if (createTimeout == null) {
            createTimeout = Duration.ofSeconds(15);
        }
    }
}
