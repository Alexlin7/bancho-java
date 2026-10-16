package idv.alexlin7.tw.bancho;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param username osu! 使用者名稱（空白會自動轉成底線）
 * @param password IRC 密碼（https://osu.ppy.sh/home/account/edit#legacy-api），不是帳號密碼
 */
@Validated
@ConfigurationProperties(prefix = "osu.irc")
public record OsuIrcProperties(
        String host,
        int port,
        @NotBlank String username,
        @NotBlank String password,
        Timing timing
) {

    public OsuIrcProperties {
        if (host == null || host.isBlank()) {
            host = "cho.ppy.sh";
        }
        if (port <= 0) {
            port = 6667;
        }
        if (timing == null) {
            timing = Timing.defaults();
        }
    }

    /**
     * @param connectTimeout    TCP 連線逾時
     * @param readTimeout       多久沒收到任何資料就主動送 PING；再一次逾時仍無回應即判定斷線
     * @param sendInterval      出站訊息間隔。一般帳號上限 10 則 / 5 秒（500ms），bot 帳號 300 則 / 60 秒（200ms）
     * @param reconnectMinDelay 重連等待的起始值，每次失敗加倍
     * @param reconnectMaxDelay 重連等待的上限
     */
    public record Timing(
            Duration connectTimeout,
            Duration readTimeout,
            Duration sendInterval,
            Duration reconnectMinDelay,
            Duration reconnectMaxDelay
    ) {

        public Timing {
            connectTimeout = orDefault(connectTimeout, Duration.ofSeconds(10));
            readTimeout = orDefault(readTimeout, Duration.ofSeconds(60));
            sendInterval = orDefault(sendInterval, Duration.ofMillis(500));
            reconnectMinDelay = orDefault(reconnectMinDelay, Duration.ofSeconds(5));
            reconnectMaxDelay = orDefault(reconnectMaxDelay, Duration.ofMinutes(5));
        }

        public static Timing defaults() {
            return new Timing(null, null, null, null, null);
        }

        private static Duration orDefault(Duration value, Duration fallback) {
            return value == null ? fallback : value;
        }
    }
}
