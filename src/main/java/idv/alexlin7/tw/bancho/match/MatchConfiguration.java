package idv.alexlin7.tw.bancho.match;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import idv.alexlin7.tw.bancho.irc.IrcSender;
import idv.alexlin7.tw.bancho.match.preset.MatchPresetProperties;

@Configuration
@EnableConfigurationProperties({MatchProperties.class, MatchPresetProperties.class})
public class MatchConfiguration {

    /** 在 shell 能開房前檢查全部模式，避免開房後才發現範本錯誤。 */
    @Bean
    public InitializingBean presetModeValidation(MatchPresetProperties presets) {
        return () -> presets.presets().forEach((name, preset) -> {
            if (preset.mode() != null && GameMode.parse(preset.mode()).isEmpty()) {
                throw new IllegalStateException("範本 " + name + " 的 mode 無效：" + preset.mode()
                        + "（可用 osu、taiko、catch、mania 或 0–3）");
            }
        });
    }

    /** MatchService 不是 IrcMessageListener，直接注入 IrcSender 不會形成循環依賴。 */
    @Bean
    public MatchService matchService(IrcSender sender, MatchProperties properties) {
        return new MatchService(sender, properties.createTimeout());
    }
}
