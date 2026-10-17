package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import cc.alexlin7.bancho.irc.OsuIrcClient;
import cc.alexlin7.bancho.irc.spring.OsuIrcAutoConfiguration;

class MatchConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OsuIrcAutoConfiguration.class)
            // 不自動連線，避免測試連到真的 cho.ppy.sh
            .withPropertyValues("osu.irc.auto-start=false");

    @Test
    void createsClientTogetherWithMatchFeatureWithoutCircularDependency() {
        runner.withUserConfiguration(MatchConfiguration.class)
                .withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(OsuIrcClient.class));
    }
}
