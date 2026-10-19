package cc.alexlin7.bancho.match.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import cc.alexlin7.bancho.irc.spring.OsuIrcAutoConfiguration;
import cc.alexlin7.bancho.match.preset.MatchPreset;

class MatchPresetPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OsuIrcAutoConfiguration.class, MatchAutoConfiguration.class))
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
                            new MatchPreset("0", "3", 2, null, List.of("HD"), null, List.of(), List.of("Alexlin86"), null));
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

    @Test
    void invalidInviteReferencesFailAtStartup() {
        for (String target : List.of("#0", "https://osu.ppy.sh/users/nope")) {
            runner.withPropertyValues("bancho.match.presets.bad.invite[0]=" + target)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("範本 bad 的 invite 無效：");
                    });
        }
    }

    @Test
    void validInviteReferencesStart() {
        runner.withPropertyValues("bancho.match.presets.ok.invite[0]=https://osu.ppy.sh/users/2548278",
                        "bancho.match.presets.ok.invite[1]=- Ｓ Ｈ -")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void hashNamesAndMultipleTargetsFailAtStartupForBothPlayerLists() {
        for (String field : List.of("invite", "referees")) {
            for (String target : List.of("Alexlin86 #2", "#1 #2")) {
                runner.withPropertyValues("bancho.match.presets.bad." + field + "[0]=" + target)
                        .run(context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).hasStackTraceContaining("範本 bad 的 " + field + " 無效：");
                        });
            }
        }
    }

    @Test
    void invalidSettingsAndRefereesFailAtStartupWithPresetName() {
        for (String property : List.of("team-mode=5", "team-mode=catch", "score-mode=-1", "score-mode=nope",
                "size=0", "size=17", "referees[0]=Minato Yukina", "referees[0]=#0")) {
            runner.withPropertyValues("bancho.match.presets.bad.team-mode=0", "bancho.match.presets.bad.score-mode=3",
                            "bancho.match.presets.bad.size=2", "bancho.match.presets.bad." + property)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("範本 bad");
                    });
        }
    }

    @Test
    void aliasesBindAndApplyLikeNumericSettings() {
        runner.withPropertyValues("bancho.match.presets.practice.team-mode=TeamVs",
                        "bancho.match.presets.practice.score-mode=V2", "bancho.match.presets.practice.size=4")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var sender = new cc.alexlin7.bancho.irc.RecordingIrcSender();
                    var service = new cc.alexlin7.bancho.match.MatchService(sender, Duration.ofSeconds(1));
                    var room = service.attach(42);
                    sender.calls.clear();
                    room.apply(context.getBean(MatchPresetProperties.class).presets().get("practice"));
                    assertThat(sender.calls).containsExactly("msg #mp_42 !mp set 2 3 4");
                });
    }
}
