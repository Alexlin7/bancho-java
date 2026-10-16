package idv.alexlin7.tw.bancho.match.preset;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import idv.alexlin7.tw.bancho.irc.OsuIrcConfiguration;
import idv.alexlin7.tw.bancho.match.MatchConfiguration;
import idv.alexlin7.tw.bancho.match.MatchProperties;

class MatchPresetPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OsuIrcConfiguration.class, MatchConfiguration.class)
            .withPropertyValues("osu.irc.auto-start=false", "osu.irc.username=someone", "osu.irc.password=secret");

    @Test
    void bindsPresetsAndKeepsMatchProperties() {
        runner.withPropertyValues(
                "bancho.match.create-timeout=5s",
                "bancho.match.presets.practice.team-mode=0",
                "bancho.match.presets.practice.score-mode=3",
                "bancho.match.presets.practice.size=2",
                "bancho.match.presets.practice.mods[0]=HD",
                "bancho.match.presets.practice.invite[0]=Alexlin86").run(context -> {
                    assertThat(context.getBean(MatchPresetProperties.class).presets().get("practice"))
                            .isEqualTo(new MatchPreset(0, 3, 2, null, List.of("HD"), null, List.of(), List.of("Alexlin86")));
                    assertThat(context.getBean(MatchProperties.class).createTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void noPresetsByDefault() {
        runner.run(context -> assertThat(context.getBean(MatchPresetProperties.class).presets()).isEmpty());
    }

    @Test
    void partialSetFailsAtStartup() {
        runner.withPropertyValues("bancho.match.presets.bad.size=2")
                .run(context -> assertThat(context).hasFailed());
    }
}
