package idv.alexlin7.tw.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import idv.alexlin7.tw.bancho.irc.FakeIrcServer;
import idv.alexlin7.tw.bancho.irc.OsuIrcClient;
import idv.alexlin7.tw.bancho.irc.OsuIrcConfiguration;
import idv.alexlin7.tw.bancho.match.MatchState.Beatmap;
import idv.alexlin7.tw.bancho.match.MatchState.ModSelection;
import idv.alexlin7.tw.bancho.match.MatchState.Settings;
import idv.alexlin7.tw.bancho.match.MatchState.Slot;

/**
 * 把真實的 BanchoBot 訊息經由假 IRC server 依序送入整個系統，驗證最後的房間狀態。
 */
@ExtendWith(OutputCaptureExtension.class)
class MatchReplayIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private static List<String> sampleLines() throws Exception {
        Path path = Path.of(Objects.requireNonNull(
                MatchReplayIntegrationTest.class.getResource("/banchobot/session-2026-10-16.irc")).toURI());
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
    }

    @Test
    void replaysRealSessionIntoRoomState(CapturedOutput output) throws Exception {
        List<String> lines = sampleLines();
        try (FakeIrcServer server = new FakeIrcServer()) {
            new ApplicationContextRunner()
                    .withUserConfiguration(OsuIrcConfiguration.class, MatchConfiguration.class, BanchoBotListener.class)
                    .withPropertyValues("osu.irc.host=127.0.0.1", "osu.irc.port=" + server.port(),
                            "osu.irc.username=test_user", "osu.irc.password=pw",
                            "osu.irc.timing.send-interval=0ms")
                    .run(context -> {
                        context.getBean(OsuIrcClient.class).start();
                        assertThat(server.awaitConnection(WAIT)).isTrue();
                        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
                        server.welcome();

                        lines.forEach(server::send);

                        assertThat(server.awaitLine("JOIN #mp_122021237", WAIT)).isNotNull();
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
        }
        assertThat(output).doesNotContain("未辨識的 BanchoBot 訊息");
    }

    @Test
    void sampleFileKeepsTrailingWhitespaceOfSlotLine() throws Exception {
        assertThat(sampleLines()).anyMatch(line -> line.endsWith("Alexlin86       "));
    }
}
