package cc.alexlin7.bancho.irc;

import java.time.Duration;

/**
 * {@link OsuIrcClient} 的連線設定，不依賴任何框架。{@code host}、{@code port}、{@code timing} 省略時使用預設值。
 * {@code username} 與 {@code password} 同時為 null 代表延後登入：建立 client 後以 {@link OsuIrcClient#login} 提供。
 *
 * @param host     預設 {@code cho.ppy.sh}
 * @param port     預設 6667
 * @param username osu! 使用者名稱（空白會自動轉成底線）
 * @param password IRC 密碼（https://osu.ppy.sh/home/account/edit#legacy-api），不是帳號密碼
 * @param timing   預設值見 {@link Timing}
 */
public record OsuIrcSettings(
        String host,
        int port,
        String username,
        String password,
        Timing timing
) {

    public static final String DEFAULT_HOST = "cho.ppy.sh";
    public static final int DEFAULT_PORT = 6667;

    /**
     * @throws IllegalArgumentException {@code username} 或 {@code password} 為空白（兩者同時為 null 除外）
     */
    public OsuIrcSettings {
        if (host == null || host.isBlank()) {
            host = DEFAULT_HOST;
        }
        if (port <= 0) {
            port = DEFAULT_PORT;
        }
        if (timing == null) {
            timing = Timing.defaults();
        }
        if (username != null || password != null) {
            requireNotBlank(username, "username");
            requireNotBlank(password, "password");
        }
    }

    /** 帳密兩者同時為 null 時為 false（延後登入）。 */
    public boolean hasCredentials() {
        return username != null;
    }

    static String requireNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不得空白");
        }
        return value;
    }

    /**
     * 不輸出密碼，避免寫進 log。
     */
    @Override
    public String toString() {
        return "OsuIrcSettings[host=" + host + ", port=" + port + ", username=" + username + ", password=***, timing="
                + timing + "]";
    }

    /**
     * @param connectTimeout    TCP 連線逾時
     * @param readTimeout       多久沒收到任何資料就主動送 PING；再一次逾時仍無回應即判定斷線
     * @param sendInterval      出站訊息間隔。一般帳號上限 10 則 / 5 秒，保留 20% 餘裕為 625ms（預設）；
     *                          bot 帳號上限 300 則 / 60 秒，建議 240ms
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
            sendInterval = orDefault(sendInterval, Duration.ofMillis(625));
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
