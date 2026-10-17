package cc.alexlin7.bancho.irc.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * @param username osu! 使用者名稱（空白會自動轉成底線），必填
 * @param password IRC 密碼（https://osu.ppy.sh/home/account/edit#legacy-api），不是帳號密碼，必填
 * @param timing   連線、送訊與重連的時間設定
 */
@ConfigurationProperties(prefix = "osu.irc")
public record OsuIrcProperties(
        String host,
        int port,
        String username,
        String password,
        @NestedConfigurationProperty OsuIrcSettings.Timing timing
) {

    /**
     * @throws IllegalStateException {@code osu.irc.username} 或 {@code osu.irc.password} 為空白
     */
    public OsuIrcSettings toSettings() {
        try {
            return new OsuIrcSettings(host, port, username, password, timing);
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
                + timing + "]";
    }
}
