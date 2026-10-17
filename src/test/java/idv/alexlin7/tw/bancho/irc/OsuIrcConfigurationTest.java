package idv.alexlin7.tw.bancho.irc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import idv.alexlin7.tw.bancho.match.MatchConfiguration;

class OsuIrcConfigurationTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OsuIrcConfiguration.class)
            // 預設不自動連線，避免測試連到真的 cho.ppy.sh；需要時各測試再覆寫成 true
            .withPropertyValues("osu.irc.auto-start=false");

    @Test
    void failsFastWhenCredentialsAreMissing() {
        // ApplicationContextRunner 會讀系統環境變數；開發者終端機常設著 OSU_IRC_USERNAME，明確設成空白才測得到缺少憑證
        runner.withPropertyValues("osu.irc.username=", "osu.irc.password=").run(context -> assertThat(context).hasFailed()
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
        runner.withPropertyValues(credentials).withPropertyValues("osu.irc.auto-start=true", "osu.irc.host=127.0.0.1",
                        "osu.irc.port=1")
                .run(context -> assertThat(context).hasBean("osuIrcLifecycle"));
        runner.withPropertyValues(credentials).withPropertyValues("osu.irc.auto-start=false")
                .run(context -> assertThat(context).doesNotHaveBean("osuIrcLifecycle"));
    }

    @Test
    void lifecycleConnectsOnContextStartAndQuitsOnClose() throws Exception {
        try (FakeIrcServer server = new FakeIrcServer()) {
            runner.withPropertyValues("osu.irc.auto-start=true", "osu.irc.host=127.0.0.1", "osu.irc.port=" + server.port(),
                    "osu.irc.username=someone", "osu.irc.password=secret", "osu.irc.timing.send-interval=0ms")
                    .run(context -> {
                        assertThat(server.awaitConnection(WAIT)).isTrue();
                        assertThat(server.awaitLine("NICK someone", WAIT)).isNotNull();
                        server.welcome();
                        assertThat(server.awaitLine(line -> line.startsWith("PING"), Duration.ofMillis(300))).isNull();
                    });
            assertThat(server.awaitLine("QUIT :Shutdown", WAIT)).isNotNull();
        }
    }

    @Test
    void lifecycleNotCreatedWhenAutoStartDisabled() throws Exception {
        try (FakeIrcServer server = new FakeIrcServer()) {
            runner.withPropertyValues("osu.irc.host=127.0.0.1", "osu.irc.port=" + server.port(),
                    "osu.irc.username=someone", "osu.irc.password=secret", "osu.irc.auto-start=false")
                    .run(context -> assertThat(server.awaitConnection(Duration.ofSeconds(1))).isFalse());
        }
    }

    @Test
    void createsClientTogetherWithMatchFeatureWithoutCircularDependency() {
        runner.withUserConfiguration(MatchConfiguration.class)
                .withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(OsuIrcClient.class));
    }
}
