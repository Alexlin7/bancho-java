package cc.alexlin7.bancho.irc.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import cc.alexlin7.bancho.irc.OsuIrcSettings;

/**
 * {@code osu.irc.*} 設定，啟動時以 {@link #toSettings()} 轉成 {@link OsuIrcSettings}；預設值由 {@link OsuIrcSettings} 補上。
 * <p>
 * 刻意不用 {@code @Validated} / {@code @NotBlank}：classpath 上只要有 Validation API，Boot 就會建立 validator，
 * 使用端若只帶 API 不帶實作就會丟 {@code NoProviderFoundException}。帳密檢查只交給 {@link OsuIrcSettings}。
 *
 * @param host     預設 {@code cho.ppy.sh}
 * @param port     預設 6667
 * @param username      osu! 使用者名稱（空白會自動轉成底線）；未開啟 {@code deferred-login} 時必填
 * @param password      IRC 密碼（https://osu.ppy.sh/home/account/edit#legacy-api），不是帳號密碼；未開啟
 *                      {@code deferred-login} 時必填
 * @param timing        連線、送訊與重連的時間設定
 * @param deferredLogin 帳密都空白時不在啟動時失敗，改由應用程式稍後以 {@code OsuIrcClient.login} 提供（例如互動輸入）
 */
@ConfigurationProperties(prefix = "osu.irc")
public record OsuIrcProperties(
        String host,
        int port,
        String username,
        String password,
        @NestedConfigurationProperty OsuIrcSettings.Timing timing,
        boolean deferredLogin
) {

    @ConstructorBinding
    public OsuIrcProperties {
        // 由 Boot 綁定時使用；保留下面的五參數建構子給既有使用者
    }

    public OsuIrcProperties(String host, int port, String username, String password, OsuIrcSettings.Timing timing) {
        this(host, port, username, password, timing, false);
    }

    /**
     * 開啟 {@code deferred-login} 且帳密都空白時，建立延後登入的設定（帳密為 null）。
     *
     * @throws IllegalStateException {@code osu.irc.username} 或 {@code osu.irc.password} 為空白
     */
    public OsuIrcSettings toSettings() {
        if (deferredLogin && isBlank(username) && isBlank(password)) {
            return new OsuIrcSettings(host, port, null, null, timing);
        }
        try {
            // null 代表延後登入，未開啟 deferred-login 時要當成空白檢查
            return new OsuIrcSettings(host, port, nullToEmpty(username), nullToEmpty(password), timing);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("osu.irc." + e.getMessage(), e);
        }
    }

    /**
     * 不輸出密碼，避免寫進 log。
     */
    @Override
    public String toString() {
        return "OsuIrcProperties[host=" + host + ", port=" + port + ", username=" + username + ", password=***, timing="
                + timing + ", deferredLogin=" + deferredLogin + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
