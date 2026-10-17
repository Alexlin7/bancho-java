package cc.alexlin7.bancho.match.preset;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 與 {@code MatchProperties} 共用 {@code bancho.match} 前綴，各自只綁定自己的欄位。
 */
@ConfigurationProperties("bancho.match")
public record MatchPresetProperties(Map<String, MatchPreset> presets) {

    public MatchPresetProperties {
        presets = presets == null ? Map.of() : Map.copyOf(presets);
    }
}
