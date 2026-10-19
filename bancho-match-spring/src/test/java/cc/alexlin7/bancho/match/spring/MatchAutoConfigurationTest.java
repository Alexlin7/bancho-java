package cc.alexlin7.bancho.match.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.event.EventListener;

import cc.alexlin7.bancho.irc.FakeIrcServer;
import cc.alexlin7.bancho.irc.IrcMessage;
import cc.alexlin7.bancho.irc.IrcTrafficListener;
import cc.alexlin7.bancho.irc.OsuIrcClient;
import cc.alexlin7.bancho.irc.RecordingIrcSender;
import cc.alexlin7.bancho.irc.spring.OsuIrcAutoConfiguration;
import cc.alexlin7.bancho.match.BanchoBotListener;
import cc.alexlin7.bancho.match.MatchService;
import cc.alexlin7.bancho.match.event.MatchEvent;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchCreated;

class MatchAutoConfigurationTest {

    private static final String CREATED = ":BanchoBot!cho@ppy.sh PRIVMSG someone :Created the tournament match "
            + "https://osu.ppy.sh/mp/42 測試房";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OsuIrcAutoConfiguration.class, MatchAutoConfiguration.class))
            // 不自動連線，避免測試連到真的 cho.ppy.sh
            .withPropertyValues("osu.irc.auto-start=false", "osu.irc.username=someone", "osu.irc.password=secret");

    @Test
    void isRegisteredAsAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(MatchAutoConfiguration.class.getName());
    }

    @Test
    void createsMatchBeansTogetherWithClientWithoutCircularDependency() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(OsuIrcClient.class)
                .hasSingleBean(MatchService.class)
                .hasSingleBean(BanchoBotListener.class));
    }

    @Test
    void springListenersReceiveEventsAfterMatchServiceUpdatedTheRoom() {
        runner.withUserConfiguration(RoomStateRecorder.class).run(context -> {
            context.getBean(BanchoBotListener.class).onMessage(IrcMessage.parse(CREATED).orElseThrow());

            assertThat(context.getBean(RoomStateRecorder.class).roomNames).containsExactly(Map.entry(42L, "測試房"));
        });
    }

    @Test
    void backsOffWithoutIrcSender() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MatchAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(MatchService.class)
                        .doesNotHaveBean(BanchoBotListener.class)
                        .doesNotHaveBean(MatchProperties.class));
    }

    @Test
    void customMatchServiceReceivesParsedEvents() {
        runner.withUserConfiguration(CustomMatchServiceConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(MatchService.class);
            context.getBean(BanchoBotListener.class).onMessage(IrcMessage.parse(CREATED).orElseThrow());

            assertThat(context.getBean("customMatchService", MatchService.class).room(42)).isPresent();
        });
    }

    @Test
    void createTimeoutDefaultsToFifteenSeconds() {
        runner.run(context -> assertThat(context.getBean(MatchProperties.class).createTimeout())
                .isEqualTo(Duration.ofSeconds(15)));
    }

    @Test
    void worksWhenPackageIsComponentScanned() {
        // 使用端的根套件涵蓋本套件時（例如 bancho-shell 的 BanchoShellApplication），不能重複建立或提早判斷條件
        runner.withUserConfiguration(ScanningApplication.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(MatchService.class)
                        .hasSingleBean(BanchoBotListener.class));
    }

    /** 收到 MatchCreated 時記下 MatchService 中的房名，用來確認 Spring 事件在房間更新之後才發布。 */
    @Configuration(proxyBeanMethods = false)
    static class RoomStateRecorder {

        private final Map<Long, String> roomNames = new ConcurrentHashMap<>();
        private final MatchService matchService;

        RoomStateRecorder(MatchService matchService) {
            this.matchService = matchService;
        }

        @EventListener
        public void onMatchEvent(MatchEvent event) {
            if (event instanceof MatchCreated created) {
                matchService.room(created.matchId())
                        .ifPresent(room -> roomNames.put(room.matchId(), room.state().name()));
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomMatchServiceConfiguration {

        @Bean
        MatchService customMatchService() {
            return new MatchService(new RecordingIrcSender(), Duration.ofSeconds(1));
        }
    }

    /** 與 {@code @SpringBootApplication} 相同地排除 auto-configuration 後掃描本套件。 */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = MatchAutoConfiguration.class, excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    RoomStateRecorder.class, CustomMatchServiceConfiguration.class})})
    static class ScanningApplication {
    }

    @Test
    void autoConfigurationInstallsPasswordRedactor() throws Exception {
        try (FakeIrcServer server = new FakeIrcServer()) {
            runner.withPropertyValues("osu.irc.auto-start=true", "osu.irc.host=127.0.0.1",
                    "osu.irc.port=" + server.port(), "osu.irc.timing.send-interval=0ms").run(context -> {
                List<String> sent = new CopyOnWriteArrayList<>();
                OsuIrcClient client = context.getBean(OsuIrcClient.class);
                client.addTrafficListener(new IrcTrafficListener() {
                    @Override
                    public void sent(String line) {
                        sent.add(line);
                    }
                });
                assertThat(server.awaitLine(line -> line.startsWith("USER "), Duration.ofSeconds(3))).isNotNull();
                server.welcome();

                client.sendMessage("#mp_1", "!mp password hunter2");

                assertThat(server.awaitLine("PRIVMSG #mp_1 :!mp password hunter2", Duration.ofSeconds(3))).isNotNull();
                await().atMost(Duration.ofSeconds(3)).until(() -> sent.contains("PRIVMSG #mp_1 :!mp password ***"));
            });
        }
    }
}
