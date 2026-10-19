package cc.alexlin7.bancho.irc.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.StandardEnvironment;

import cc.alexlin7.bancho.irc.FakeIrcServer;
import cc.alexlin7.bancho.irc.IrcConnectionState;
import cc.alexlin7.bancho.irc.OsuIrcClient;
import cc.alexlin7.bancho.irc.OsuIrcSettings;

class OsuIrcAutoConfigurationTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 以 auto-configuration 載入，@ConditionalOnMissingBean 才會在使用者設定之後判斷
            .withConfiguration(AutoConfigurations.of(OsuIrcAutoConfiguration.class))
            // 預設不自動連線，避免測試連到真的 cho.ppy.sh；需要時各測試再覆寫成 true
            .withPropertyValues("osu.irc.auto-start=false");

    @Test
    void isRegisteredAsAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(OsuIrcAutoConfiguration.class.getName());
    }

    @Test
    void publishesMetadataForAutoStart() throws IOException {
        // osu.irc.auto-start 只出現在 @ConditionalOnProperty，要靠 additional-spring-configuration-metadata.json 補上
        try (InputStream metadata = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(metadata).isNotNull();
            assertThat(new String(metadata.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"osu.irc.auto-start\"", "\"osu.irc.username\"");
        }
    }

    @Test
    void failsFastWhenCredentialsAreMissing() {
        // ApplicationContextRunner 會讀系統環境變數；開發者終端機常設著 OSU_IRC_USERNAME，明確設成空白才測得到缺少憑證
        runner.withPropertyValues("osu.irc.username=", "osu.irc.password=").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("username"));
    }

    @Test
    void startsWithoutBeanValidation() {
        // 使用端沒有加入 spring-boot-starter-validation 時，classpath 上不會有 jakarta.validation
        runner.withClassLoader(new FilteredClassLoader("jakarta.validation"))
                .withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(OsuIrcClient.class));
    }

    @Test
    void rejectsBlankCredentialsWithoutBeanValidation() {
        runner.withClassLoader(new FilteredClassLoader("jakarta.validation"))
                .withPropertyValues("osu.irc.username=", "osu.irc.password=")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("username"));
    }

    @Test
    void customClientReplacesDefaultsWithoutRequiringCredentials() {
        runner.withUserConfiguration(CustomClientConfiguration.class)
                .withPropertyValues("osu.irc.username=", "osu.irc.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(OsuIrcProperties.class);
                    assertThat(context.getBean(OsuIrcClient.class)).isSameAs(context.getBean("customClient"));
                });
    }

    @Test
    void customClientReplacesDefaultsWhenPackageIsComponentScanned() {
        // 使用端的根套件涵蓋本套件時（例如 bancho-shell 的 BanchoShellApplication），掃描也不能提早觸發預設 client 的條件判斷
        runner.withUserConfiguration(ScanningApplication.class)
                .withPropertyValues("osu.irc.username=", "osu.irc.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(OsuIrcProperties.class);
                    assertThat(context.getBeansOfType(OsuIrcClient.class)).containsOnlyKeys("appClient");
                });
    }

    /**
     * 模擬根套件涵蓋本套件的應用程式：與 {@code @SpringBootApplication} 相同地排除 auto-configuration 後掃描，
     * 自訂 client 寫在應用程式本身，要等掃描到的設定類別處理完才會註冊。
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = OsuIrcAutoConfiguration.class, excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CustomClientConfiguration.class)})
    static class ScanningApplication {

        @Bean(destroyMethod = "close")
        OsuIrcClient appClient() {
            return new OsuIrcClient(new OsuIrcSettings(null, 0, "app", "secret", null), List.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomClientConfiguration {

        @Bean(destroyMethod = "close")
        OsuIrcClient customClient() {
            return new OsuIrcClient(new OsuIrcSettings(null, 0, "custom", "secret", null), List.of());
        }
    }

    @Test
    void appliesDefaults() {
        runner.withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret").run(context -> {
            OsuIrcSettings settings = context.getBean(OsuIrcProperties.class).toSettings();
            assertThat(settings.host()).isEqualTo("cho.ppy.sh");
            assertThat(settings.port()).isEqualTo(6667);
            assertThat(settings.timing().sendInterval()).isEqualTo(Duration.ofMillis(625));
            assertThat(settings.timing().readTimeout()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void bindsTimingOverrides() {
        runner.withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret",
                "osu.irc.timing.send-interval=200ms", "osu.irc.timing.reconnect-max-delay=1m").run(context -> {
            OsuIrcSettings.Timing timing = context.getBean(OsuIrcProperties.class).toSettings().timing();
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

    // ---------------------------------------------------------------- 延後登入

    @Test
    void deferredLoginStartsWithoutCredentials() throws Exception {
        try (FakeIrcServer server = new FakeIrcServer()) {
            runner.withPropertyValues("osu.irc.username=", "osu.irc.password=", "osu.irc.deferred-login=true",
                    "osu.irc.auto-start=true", "osu.irc.host=127.0.0.1", "osu.irc.port=" + server.port())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(OsuIrcClient.class).state()).isEqualTo(IrcConnectionState.IDLE);
                        assertThat(server.awaitConnection(Duration.ofMillis(300))).isFalse();
                    });
        }
    }

    @Test
    void deferredLoginStillUsesCredentialsWhenGiven() throws Exception {
        try (FakeIrcServer server = new FakeIrcServer()) {
            runner.withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret",
                    "osu.irc.deferred-login=true", "osu.irc.auto-start=true", "osu.irc.host=127.0.0.1",
                    "osu.irc.port=" + server.port())
                    .run(context -> assertThat(server.awaitLine("PASS secret", WAIT)).isNotNull());
        }
    }

    @Test
    void failsWhenCredentialsAreOmittedEntirely() {
        // 移除系統環境變數，確保 osu.irc.username / password 完全沒有設定（不是空字串）
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessage("username 不得空白"));
    }

    @Test
    void omittedCredentialsWithoutDeferredLoginAreRejected() {
        assertThatIllegalStateException().isThrownBy(() -> new OsuIrcProperties(null, 0, null, null, null).toSettings())
                .withMessage("osu.irc.username 不得空白");
    }

    @Test
    void fiveArgumentConstructorStillWorks() {
        OsuIrcProperties properties = new OsuIrcProperties(null, 0, "u", "p", null);

        assertThat(properties.deferredLogin()).isFalse();
        assertThat(properties.toSettings().hasCredentials()).isTrue();
    }
}
