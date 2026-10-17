package idv.alexlin7.tw.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.event.EventListener;

import idv.alexlin7.tw.bancho.irc.FakeIrcServer;
import idv.alexlin7.tw.bancho.irc.OsuIrcClient;
import idv.alexlin7.tw.bancho.irc.OsuIrcConfiguration;
import idv.alexlin7.tw.bancho.match.MatchState.Beatmap;
import idv.alexlin7.tw.bancho.match.MatchState.ModSelection;
import idv.alexlin7.tw.bancho.match.MatchState.Settings;
import idv.alexlin7.tw.bancho.match.MatchState.Slot;
import idv.alexlin7.tw.bancho.match.event.MatchEvent;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.BeatmapChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchStarted;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchAborted;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.AllPlayersReady;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.GameModeChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchFinished;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerFinished;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.SettingsChanged;

/**
 * 把真實的 BanchoBot 訊息經由假 IRC server 依序送入整個系統，驗證最後的房間狀態。
 */
@ExtendWith(OutputCaptureExtension.class)
class MatchReplayIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private static List<String> sampleLines(String resource) throws Exception {
        Path path = Path.of(Objects.requireNonNull(
                MatchReplayIntegrationTest.class.getResource(resource)).toURI());
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
    }

    @Test
    void replaysRealSessionIntoRoomState(CapturedOutput output) throws Exception {
        replay("/banchobot/session-2026-10-16.irc", "JOIN #mp_122021237", (server, context) -> {
            assertThat(server.awaitLine("PART #mp_122021237", WAIT)).isNotNull();
            assertThat(server.awaitLine("JOIN #mp_122021273", WAIT)).isNotNull();
            assertThat(server.awaitLine("PART #mp_122021273", WAIT)).isNotNull();

            MatchService service = context.getBean(MatchService.class);
            assertThat(service.openRooms()).isEmpty();
            assertThat(service.room(122021237).orElseThrow().state().closed()).isTrue();

            MatchState state = service.room(122021273).orElseThrow().state();
            assertThat(state.name()).isEqualTo("測試房");
            assertThat(state.closed()).isTrue();
            assertThat(state.settings()).isEqualTo(new Settings(2, "HeadToHead", "ScoreV2"));
            assertThat(state.beatmap()).isEqualTo(new Beatmap(1502772, "Asriel - Kegare Naki Yume"));
            assertThat(state.mods()).isEqualTo(new ModSelection(List.of("Hidden", "HardRock"), false));
            assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("Alexlin86", 4841753L, "Not Ready")));
        });
        assertThat(output).doesNotContain("未辨識的 BanchoBot 訊息");
    }

    @Test
    void replaysSecondSessionIntoRoomState(CapturedOutput output) throws Exception {
        replay("/banchobot/session-2026-10-17.irc", "JOIN #mp_122022152", (server, context) -> {
            EventCollector collector = context.getBean(EventCollector.class);
            await().atMost(WAIT).untilAsserted(() -> {
                assertThat(collector.events).contains(new BeatmapChanged(122022152, 2453358,
                        "Minato Aqua - Shiroi Yuki no Princess wa"));
                assertThat(output).contains("未辨識的 BanchoBot 訊息：User not found: _AppleCat_");
            });
            MatchState state = context.getBean(MatchService.class).room(122022152).orElseThrow().state();
            assertThat(state.name()).isEqualTo("mp測試です");
            assertThat(state.closed()).isFalse();
            assertThat(state.beatmap()).isEqualTo(new Beatmap(2453358, "Minato Aqua - Shiroi Yuki no Princess wa"));
            assertThat(state.mods()).isEqualTo(new ModSelection(List.of("NoFail", "Hidden"), false));
            assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("Alexlin86", 4841753L, "Not Ready")));
            assertThat(collector.events.stream().filter(MatchStarted.class::isInstance)).hasSize(1);
            assertThat(collector.events.stream().filter(MatchAborted.class::isInstance)).hasSize(1);
        });
        assertThat(output.getAll().lines().filter(line -> line.contains("未辨識的 BanchoBot 訊息"))).hasSize(4);
    }

    @Test
    void replaysThirdSessionIntoRoomState(CapturedOutput output) throws Exception {
        replay("/banchobot/session-2026-10-17b.irc", "JOIN #mp_122022753", (server, context) -> {
            EventCollector collector = context.getBean(EventCollector.class);
            await().atMost(WAIT).untilAsserted(() -> assertThat(collector.events)
                    .contains(new SettingsChanged(122022753, 6, "TagTeamVs", "ScoreV2")));
            MatchState state = context.getBean(MatchService.class).room(122022753).orElseThrow().state();
            assertThat(state.name()).isEqualTo("test123房間");
            assertThat(state.closed()).isFalse();
            assertThat(state.beatmap()).isEqualTo(
                    new Beatmap(4199355, "Sena Airi (CV: Gogyou Nazuna) - Symphonic Love ReArrange"));
            assertThat(state.mods()).isEqualTo(new ModSelection(List.of("NoFail", "Hidden"), false));
            assertThat(state.gameMode()).isEqualTo("CatchTheBeat");
            assertThat(state.settings()).isEqualTo(new Settings(6, "TagTeamVs", "ScoreV2"));
            assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("Alexlin86", null, null)));
            assertThat(collector.events.stream().filter(PlayerFinished.class::isInstance)).hasSize(3);
            assertThat(collector.events.stream().filter(MatchFinished.class::isInstance)).hasSize(2);
            assertThat(collector.events.stream().filter(AllPlayersReady.class::isInstance)).hasSize(2);
            assertThat(collector.events.stream().filter(MatchStarted.class::isInstance)).hasSize(3);
            assertThat(collector.events.stream().filter(MatchAborted.class::isInstance)).hasSize(1);
        });
        assertThat(output).doesNotContain("未辨識的 BanchoBot 訊息");
    }

    private static void replay(String resource, String join, ReplayAssertions assertions) throws Exception {
        List<String> lines = sampleLines(resource);
        try (FakeIrcServer server = new FakeIrcServer()) {
            new ApplicationContextRunner()
                    .withUserConfiguration(OsuIrcConfiguration.class, MatchConfiguration.class, BanchoBotListener.class,
                            EventCollector.class)
                    .withPropertyValues("osu.irc.host=127.0.0.1", "osu.irc.port=" + server.port(),
                            "osu.irc.username=test_user", "osu.irc.password=pw",
                            "osu.irc.timing.send-interval=0ms")
                    .run(context -> {
                        context.getBean(OsuIrcClient.class).start();
                        assertThat(server.awaitConnection(WAIT)).isTrue();
                        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
                        server.welcome();

                        lines.forEach(server::send);
                        assertThat(server.awaitLine(join, WAIT)).isNotNull();
                        assertions.verify(server, context);
                    });
        }
    }

    @FunctionalInterface
    private interface ReplayAssertions {
        void verify(FakeIrcServer server, AssertableApplicationContext context) throws Exception;
    }

    static class EventCollector {
        private final List<MatchEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void collect(MatchEvent event) {
            events.add(event);
        }
    }

    @Test
    void sampleFileKeepsTrailingWhitespaceOfSlotLine() throws Exception {
        assertThat(sampleLines("/banchobot/session-2026-10-16.irc"))
                .anyMatch(line -> line.endsWith("Alexlin86       "));
        assertThat(sampleLines("/banchobot/session-2026-10-17.irc"))
                .anyMatch(line -> line.endsWith("Alexlin86       "));
    }
}
