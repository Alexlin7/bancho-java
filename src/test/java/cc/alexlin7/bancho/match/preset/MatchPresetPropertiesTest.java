package cc.alexlin7.bancho.match.preset;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import cc.alexlin7.bancho.irc.OsuIrcConfiguration;
import cc.alexlin7.bancho.match.MatchConfiguration;
import cc.alexlin7.bancho.match.MatchProperties;

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
                    assertThat(context.getBean(MatchPresetProperties.class).presets()).containsEntry("practice",
                            new MatchPreset(0, 3, 2, null, List.of("HD"), null, List.of(), List.of("Alexlin86"), null));
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

    @Test
    void modeWithoutBeatmapFailsAtStartup() {
        runner.withPropertyValues("bancho.match.presets.bad.mode=mania")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidModeFailsAtStartup() {
        runner.withPropertyValues("bancho.match.presets.bad.beatmap=1502772", "bancho.match.presets.bad.mode=9")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("範本 bad 的 mode 無效：9");
                });
    }

    @Test
    void validModeStarts() {
        runner.withPropertyValues("bancho.match.presets.ok.beatmap=1502772", "bancho.match.presets.ok.mode=ctb")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MatchPresetProperties.class).presets().get("ok").mode()).isEqualTo("ctb");
                });
    }

    @Test
    void concatenatedModsFailAtStartup() {
        runner.withPropertyValues("bancho.match.presets.bad.mods=NFHD")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("範本 bad 的 mods 無效：mod 要用空白分開");
                });
    }

    @Test
    void validModsStart() {
        runner.withPropertyValues("bancho.match.presets.ok.mods=HD,HR,Freemod")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
