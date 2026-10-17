package idv.alexlin7.tw.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.irc.RecordingIrcSender;
import idv.alexlin7.tw.bancho.match.MatchService;
import idv.alexlin7.tw.bancho.match.preset.MatchPreset;
import idv.alexlin7.tw.bancho.match.preset.MatchPresetProperties;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchClosed;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchCreated;

class MatchCommandsTest {

    private static final String NO_ROOM = "請先用 make / use / attach 選擇房間，或加上 --room <房號>";

    private final RecordingIrcSender sender = new RecordingIrcSender();
    private final MatchService matchService = new MatchService(sender, Duration.ofMillis(200));
    private final ShellSession session = new ShellSession();
    private final List<String> printed = Collections.synchronizedList(new ArrayList<>());
    private final MatchCommands commands = new MatchCommands(matchService, session, printed::add,
            new MatchPresetProperties(Map.of("practice",
                    new MatchPreset(0, 3, 2, null, List.of("HD"), null, null, null, null))));

    private void awaitLastMake() throws Exception {
        commands.lastMake().handle((room, error) -> null).get(2, TimeUnit.SECONDS);
    }

    @Test
    void makeSendsRequestAndUsesRoomWhenCreated() throws Exception {
        assertThat(commands.make(List.of("測試房"), null, null)).contains("已送出開房請求：測試房");
        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make 測試房");

        matchService.onMatchEvent(new MatchCreated(122021273, "測試房"));
        awaitLastMake();

        assertThat(printed).contains("房間已建立：#mp_122021273 測試房");
        assertThat(session.currentRoom()).contains(122021273L);
    }

    @Test
    void makeWithPresetAppliesItAfterCreation() throws Exception {
        commands.make(List.of("r"), "practice", null);

        matchService.onMatchEvent(new MatchCreated(5, "r"));
        awaitLastMake();

        assertThat(sender.calls).containsSubsequence("join #mp_5", "msg #mp_5 !mp set 0 3 2", "msg #mp_5 !mp mods HD");
        assertThat(printed).contains("已套用範本：practice");
    }

    @Test
    void unknownPresetRejectedBeforeMake() {
        assertThat(commands.make(List.of("r"), "nope", null)).isEqualTo("找不到範本：nope");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void makeTimeoutIsPrinted() throws Exception {
        commands.make(List.of("r"), null, null);
        awaitLastMake();

        assertThat(printed).contains("開房逾時：r");
    }

    @Test
    void roomCommandUsesCurrentRoom() {
        commands.attach("42", null);
        commands.use("42", null);

        assertThat(commands.invite(List.of("Alexlin86"), null, null)).isEqualTo("已送出：!mp invite Alexlin86");
        assertThat(sender.calls).contains("msg #mp_42 !mp invite Alexlin86");
    }

    @Test
    void roomOptionOverridesCurrentRoom() {
        commands.attach("42", null);
        commands.attach("7", null);
        commands.use("42", null);

        commands.abort(7L, null);

        assertThat(sender.calls).contains("msg #mp_7 !mp abort").doesNotContain("msg #mp_42 !mp abort");
    }

    @Test
    void roomCommandWithoutCurrentRoomExplains() {
        assertThat(commands.invite(List.of("A"), null, null)).isEqualTo(NO_ROOM);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void unknownOrClosedRoomIsReported() {
        assertThat(commands.invite(List.of("A"), 999L, null)).isEqualTo("找不到房間：999");

        matchService.onMatchEvent(new MatchCreated(1, "a"));
        matchService.onMatchEvent(new MatchClosed(1));
        sender.calls.clear();

        assertThat(commands.abort(1L, null)).isEqualTo("房間已關閉：#mp_1");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void useRejectsUnknownRoom() {
        assertThat(commands.use("5", null)).isEqualTo("找不到房間：5");
        assertThat(session.currentRoom()).isEmpty();
    }

    @Test
    void invalidArgumentsBecomeErrorText() {
        commands.attach("42", null);

        assertThat(commands.set("9", "0", "2", 42L, null)).startsWith("錯誤：");
    }

    @Test
    void startAndTimer() {
        commands.attach("42", null);

        commands.start(null, 42L, null);
        commands.start(10, 42L, null);
        commands.timer(null, 42L, null);
        commands.timer(30, 42L, null);

        assertThat(sender.calls).containsSubsequence(
                "msg #mp_42 !mp start", "msg #mp_42 !mp start 10", "msg #mp_42 !mp timer", "msg #mp_42 !mp timer 30");
    }

    @Test
    void otherRoomCommands() {
        commands.attach("42", null);
        sender.calls.clear();

        commands.map("1502772", null, 42L, null);
        commands.mods(List.of("HD", "HR"), 42L, null);
        commands.set("0", "3", "2", 42L, null);
        commands.settings(42L, null);
        commands.password(null, 42L, null);
        commands.addref(List.of("Ref1"), 42L, null);
        commands.close(42L, null);

        assertThat(sender.calls).containsExactly(
                "msg #mp_42 !mp map 1502772", "msg #mp_42 !mp mods HD HR", "msg #mp_42 !mp set 0 3 2",
                "msg #mp_42 !mp settings", "msg #mp_42 !mp password", "msg #mp_42 !mp addref Ref1",
                "msg #mp_42 !mp close");
    }

    @Test
    void missingRequiredArgumentsShowUsage() {
        matchService.attach(42);
        sender.calls.clear();
        String setUsage = "錯誤：用法：set <team mode> <score mode> <人數> [--room <房號>]";
        assertThat(commands.set(null, null, null, 42L, null)).isEqualTo(setUsage);
        assertThat(commands.set("2", null, null, 42L, null)).isEqualTo(setUsage);
        assertThat(commands.set("0", "3", null, 42L, null)).isEqualTo(setUsage);
        assertThat(commands.map(null, null, 42L, null)).isEqualTo("錯誤：用法：map <beatmap id> [模式] [--room <房號>]");
        assertThat(commands.attach(null, null)).isEqualTo("錯誤：用法：attach <房號>");
        assertThat(commands.use(null, null)).isEqualTo("錯誤：用法：use <房號>");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void mapAcceptsOptionalMode() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.map("2453358", "mania", 42L, null)).isEqualTo("已送出：!mp map 2453358 3");
        assertThat(commands.map("2453358", null, 42L, null)).isEqualTo("已送出：!mp map 2453358");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp map 2453358 3", "msg #mp_42 !mp map 2453358");
    }

    @Test
    void mapRejectsUnknownMode() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.map("2453358", "4", 42L, null))
                .isEqualTo("錯誤：不認得的遊玩模式：4（可用 osu、taiko、catch、mania 或 0–3）");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void mpForwardsArguments() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.mp(List.of("host", "Alexlin86"), 42L, null)).isEqualTo("已送出：!mp host Alexlin86");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp host Alexlin86");
    }

    @Test
    void mpRejectsMissingArgumentsAndRoomCreation() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.mp(List.of(), 42L, null)).isEqualTo("錯誤：請輸入 !mp 之後的指令，例如 mp host Alexlin86");
        for (String subcommand : List.of("make", "MAKE", "MakePrivate")) {
            assertThat(commands.mp(List.of(subcommand, "x"), 42L, null)).isEqualTo("錯誤：開房請用 make 指令");
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void mpMasksPasswordReply() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.mp(List.of("password", "secret"), 42L, null))
                .isEqualTo("已送出：!mp password ***").doesNotContain("secret");
        assertThat(commands.mp(List.of("PaSsWoRd", "secret"), 42L, null)).doesNotContain("secret");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp password secret", "msg #mp_42 !mp PaSsWoRd secret");
    }

    @Test
    void nameCommandsRejectRoomWordsBeforeSending() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.addref(List.of("-SH-", "--room", "7"), 42L, null)).startsWith("錯誤：");
        assertThat(commands.invite(List.of("-SH-", "--room=7"), 42L, null)).startsWith("錯誤：");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void roomsShowsGameMode() {
        matchService.attach(42);
        assertThat(commands.rooms(null)).contains("mode ?");
    }

    @Test
    void roomsListsOpenRooms() {
        assertThat(commands.rooms(null)).isEqualTo("目前沒有開著的房間");

        matchService.onMatchEvent(new MatchCreated(1, "a"));

        assertThat(commands.rooms(null)).contains("#mp_1 a");
    }
}
