package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleRecorderTest {

    private static final ZoneId ZONE = ZoneOffset.ofHours(8);
    private static final Instant NOON = Instant.parse("2026-10-17T04:00:00Z");
    private static final String HEADER = "# bancho-shell 樣本檔；匯入測試資源前把自己的 nick 改成 test_user";
    private static final String SLOT = ":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Slot 1  Not Ready https://osu.ppy.sh/u/1 A   ";

    @TempDir
    Path dir;

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZONE);
    }

    private List<String> lines(String date) throws Exception {
        return Files.readAllLines(dir.resolve("samples-" + date + ".irc"), StandardCharsets.UTF_8);
    }

    @Test
    void writesReceivedLinesVerbatimWithTimestampComments() throws Exception {
        SampleRecorder recorder = new SampleRecorder(dir, at(NOON));

        recorder.received(SLOT);
        recorder.received(":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Closed the match");
        recorder.received(":Alexlin86!cho@ppy.sh PRIVMSG #mp_1 :gl");

        assertThat(lines("2026-10-17")).containsExactly(HEADER, "# 12:00:00", SLOT,
                ":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Closed the match");
    }

    @Test
    void writesSentLinesAsComments() throws Exception {
        SampleRecorder recorder = new SampleRecorder(dir, at(NOON));

        recorder.sent("PRIVMSG #mp_1 :!mp start 10");
        recorder.sent("PRIVMSG Alexlin86 :hi");

        assertThat(lines("2026-10-17")).containsExactly(HEADER, "# 12:00:00 -> PRIVMSG #mp_1 :!mp start 10");
    }

    @Test
    void createsMissingDirectory() throws Exception {
        Path nested = dir.resolve("logs/nested");
        SampleRecorder recorder = new SampleRecorder(nested, at(NOON));

        recorder.received(":cho.ppy.sh 001 u :Welcome");

        assertThat(nested.resolve("samples-2026-10-17.irc")).exists();
    }

    @Test
    void rollsOverByDate() throws Exception {
        new SampleRecorder(dir, at(NOON)).received(":cho.ppy.sh 001 u :Welcome");
        SampleRecorder nextDay = new SampleRecorder(dir, at(NOON.plusSeconds(86_400)));

        nextDay.received(":cho.ppy.sh 001 u :Welcome");

        assertThat(lines("2026-10-18")).containsExactly(HEADER, "# 12:00:00", ":cho.ppy.sh 001 u :Welcome");
    }

    @Test
    void replayFilterReadsOnlyReceivedLines() throws Exception {
        SampleRecorder recorder = new SampleRecorder(dir, at(NOON));
        recorder.sent("PRIVMSG BanchoBot :!mp make r");
        recorder.received(":BanchoBot!cho@ppy.sh PRIVMSG me :Created the tournament match https://osu.ppy.sh/mp/1 r");

        // 與 MatchReplayIntegrationTest 相同的讀法：略過空行與 # 開頭的註解
        List<String> replayed = lines("2026-10-17").stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();

        assertThat(replayed).containsExactly(
                ":BanchoBot!cho@ppy.sh PRIVMSG me :Created the tournament match https://osu.ppy.sh/mp/1 r");
    }
}
