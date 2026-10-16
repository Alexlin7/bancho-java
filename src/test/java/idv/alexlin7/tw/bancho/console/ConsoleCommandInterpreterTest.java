package idv.alexlin7.tw.bancho.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import idv.alexlin7.tw.bancho.irc.RecordingIrcSender;
import idv.alexlin7.tw.bancho.match.MatchService;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.BeatmapChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchCreated;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.ModsChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerJoined;

class ConsoleCommandInterpreterTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private RecordingIrcSender sender;
    private MatchService matchService;
    private ConsoleCommandInterpreter interpreter;

    @BeforeEach
    void setUp() {
        sender = new RecordingIrcSender();
        matchService = new MatchService(sender, Duration.ofMillis(200));
        interpreter = new ConsoleCommandInterpreter(sender, matchService,
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void awaitLastMake() throws Exception {
        interpreter.lastMake().handle((room, error) -> null).get(2, TimeUnit.SECONDS);
    }

    @Test
    void makeCreatesRoomAndSwitchesTarget() throws Exception {
        interpreter.execute("/make 測試房");
        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make 測試房");

        matchService.onMatchEvent(new MatchCreated(122021273, "測試房"));
        awaitLastMake();

        assertThat(output()).contains("房間已建立：#mp_122021273 測試房");
        assertThat(interpreter.currentTarget()).isEqualTo("#mp_122021273");
    }

    @Test
    void makeReportsTimeout() throws Exception {
        interpreter.execute("/make r");
        awaitLastMake();

        assertThat(output()).contains("開房逾時：r");
    }

    @Test
    void makeWithoutNamePrintsUsage() {
        interpreter.execute("/make");

        assertThat(output()).contains("用法：/make <房名>");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void duplicateMakeIsReportedNotThrown() {
        interpreter.execute("/make r");
        interpreter.execute("/make r");

        assertThat(output()).contains("錯誤：已有同名的開房請求在等待中：r");
    }

    @Test
    void roomsListsOpenRooms() {
        matchService.onMatchEvent(new MatchCreated(1, "a"));
        matchService.onMatchEvent(new PlayerJoined(1, "Alexlin86", 1));
        matchService.onMatchEvent(new BeatmapChanged(1, 1502772, "Asriel - Kegare Naki Yume"));
        matchService.onMatchEvent(new ModsChanged(1, List.of("Hidden"), false));

        interpreter.execute("/rooms");

        assertThat(output()).contains("#mp_1 a", "map 1502772 Asriel - Kegare Naki Yume", "mods Hidden", "[1] Alexlin86");
    }

    @Test
    void roomsWhenNoneOpen() {
        interpreter.execute("/rooms");

        assertThat(output()).contains("目前沒有開著的房間");
    }

    @Test
    void helpListsMatchCommands() {
        interpreter.execute("/help");

        assertThat(output()).contains("/make <房名>", "/rooms");
    }

    @Test
    void plainTextGoesToCurrentTarget() {
        interpreter.execute("/target BanchoBot");
        interpreter.execute("!mp make 測試房");

        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make 測試房");
        assertThat(output()).contains("目前對象：BanchoBot");
    }

    @Test
    void plainTextWithoutTargetIsNotSent() {
        interpreter.execute("hello");

        assertThat(sender.calls).isEmpty();
        assertThat(output()).contains("尚未設定對象");
    }

    @Test
    void joinSwitchesTargetAndPartClearsIt() {
        interpreter.execute("/join #mp_123");
        assertThat(interpreter.currentTarget()).isEqualTo("#mp_123");

        interpreter.execute("/part #mp_123");

        assertThat(sender.calls).containsExactly("join #mp_123", "part #mp_123");
        assertThat(interpreter.currentTarget()).isNull();
    }

    @Test
    void msgSendsWithoutChangingTarget() {
        interpreter.execute("/target #mp_1");
        interpreter.execute("/msg Some_Player  hi there ");

        assertThat(sender.calls).containsExactly("msg Some_Player hi there");
        assertThat(interpreter.currentTarget()).isEqualTo("#mp_1");
    }

    @Test
    void commandsAreCaseInsensitive() {
        interpreter.execute("/TARGET BanchoBot");

        assertThat(interpreter.currentTarget()).isEqualTo("BanchoBot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/target", "/join", "/part", "/msg", "/msg onlyTarget"})
    void missingArgumentsPrintUsage(String line) {
        interpreter.execute(line);

        assertThat(sender.calls).isEmpty();
        assertThat(output()).contains("用法：");
    }

    @Test
    void senderValidationErrorsArePrintedNotThrown() {
        interpreter.execute("/join mp_1");

        assertThat(output()).contains("錯誤：頻道名稱必須以 # 開頭");
        assertThat(interpreter.currentTarget()).isNull();
    }

    @Test
    void unknownCommandAndHelp() {
        interpreter.execute("/nope");
        interpreter.execute("/help");

        assertThat(output()).contains("未知指令：/nope", "/target <對象>");
    }

    @Test
    void blankLinesAreIgnored() {
        interpreter.execute("   ");

        assertThat(sender.calls).isEmpty();
        assertThat(output()).isEmpty();
    }

}
