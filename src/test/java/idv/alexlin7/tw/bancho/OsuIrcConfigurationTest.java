package idv.alexlin7.tw.bancho;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import idv.alexlin7.tw.bancho.console.ConsoleConfiguration;

class OsuIrcConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OsuIrcConfiguration.class, OsuChatHandlerService.class);

    @Test
    void failsFastWhenCredentialsAreMissing() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("username"));
    }

    @Test
    void appliesDefaults() {
        runner.withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret").run(context -> {
            OsuIrcProperties properties = context.getBean(OsuIrcProperties.class);
            assertThat(properties.host()).isEqualTo("cho.ppy.sh");
            assertThat(properties.port()).isEqualTo(6667);
            assertThat(properties.timing().sendInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.timing().readTimeout()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void bindsTimingOverrides() {
        runner.withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret",
                "osu.irc.timing.send-interval=200ms", "osu.irc.timing.reconnect-max-delay=1m").run(context -> {
            OsuIrcProperties.Timing timing = context.getBean(OsuIrcProperties.class).timing();
            assertThat(timing.sendInterval()).isEqualTo(Duration.ofMillis(200));
            assertThat(timing.reconnectMaxDelay()).isEqualTo(Duration.ofMinutes(1));
            assertThat(timing.reconnectMinDelay()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void autoStarterCanBeDisabled() {
        String[] credentials = {"osu.irc.username=someone", "osu.irc.password=secret"};
        runner.withPropertyValues(credentials)
                .run(context -> assertThat(context).hasBean("osuIrcAutoStarter"));
        runner.withPropertyValues(credentials).withPropertyValues("osu.irc.auto-start=false")
                .run(context -> assertThat(context).doesNotHaveBean("osuIrcAutoStarter"));
    }

    @Test
    void registersEveryListenerBeanWithoutCircularDependency() {
        runner.withUserConfiguration(ConsoleConfiguration.class)
                .withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(IrcMessageListener.class))
                            .containsOnlyKeys("osuChatHandlerService", "consoleMessageListener");
                });
    }

    @Test
    void consoleCanBeDisabled() {
        runner.withUserConfiguration(ConsoleConfiguration.class)
                .withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret", "bancho.console" +
                        ".enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("consoleMessageListener"));
    }
}
