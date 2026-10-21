package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import cc.alexlin7.bancho.irc.IrcConnectionState;
import cc.alexlin7.bancho.irc.RecordingIrcSender;
import cc.alexlin7.bancho.match.MatchService;
import cc.alexlin7.bancho.match.preset.MatchPreset;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchClosed;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchCreated;
import cc.alexlin7.bancho.match.spring.MatchPresetProperties;

class MatchCommandsTest {

    private static final String NO_ROOM = "請先用 make / use / attach 選擇房間，或加上 --room <房號>";

    private final RecordingIrcSender sender = new RecordingIrcSender();
    private final MatchService matchService = new MatchService(sender, Duration.ofMillis(200));
    private final ShellSession session = new ShellSession();
    private final List<String> printed = Collections.synchronizedList(new ArrayList<>());
    private final List<String> prompts = new ArrayList<>();
    private Optional<String> typed = Optional.empty();
    private IrcConnectionState connection = IrcConnectionState.REGISTERED;
    private final MatchCommands commands = new MatchCommands(matchService, session, printed::add,
            new MatchPresetProperties(Map.of("practice",
                    new MatchPreset("0", "3", 2, null, List.of("HD"), null, null, null, null))),
            prompt -> {
                prompts.add(prompt);
                return typed;
            },
            new LoginGuard(() -> connection));

    @Test
    void makeRejectedWhileConnecting() {
        connection = IrcConnectionState.CONNECTING;

        assertThat(commands.make(List.of("r"), null, null)).isEqualTo("錯誤：正在連線 Bancho，請等登入完成");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void teamJoinsPlayerWordsAfterColour() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.team(List.of("blue", "Tomari", "Mari"), 42L, null))
                .isEqualTo("已送出：!mp team Tomari Mari blue");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp team Tomari Mari blue");
    }

    @Test
    void teamRejectsInvalidColourAndMissingPlayerWithoutSending() {
        String usage = "錯誤：用法：team <blue|red> <玩家目標…> [--room <房號>]";
        assertThat(commands.team(List.of("green", "A"), 42L, null)).isEqualTo(usage);
        assertThat(commands.team(List.of("red"), 42L, null)).isEqualTo(usage);
        assertThat(commands.team(List.of(), 42L, null)).isEqualTo(usage);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void teamRequiresLogin() {
        connection = IrcConnectionState.IDLE;
        assertThat(commands.team(List.of("red", "A"), 42L, null)).isEqualTo("錯誤：請先 login");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void roomCommandsRejectedBeforeLogin() {
        matchService.attach(42);
        session.use(42);
        sender.calls.clear();
        connection = IrcConnectionState.IDLE;

        assertThat(commands.attach("43", null)).isEqualTo("錯誤：請先 login");
        assertThat(commands.invite(List.of("A"), null, null)).isEqualTo("錯誤：請先 login");
        assertThat(commands.host(List.of("#2548278"), null, null)).isEqualTo("錯誤：請先 login");
        assertThat(commands.close(null, null)).isEqualTo("錯誤：請先 login");
        assertThat(commands.mp(List.of("host", "A"), null, null)).isEqualTo("錯誤：請先 login");
        assertThat(commands.password(List.of(), false, null, null)).isEqualTo("錯誤：請先 login");
        assertThat(prompts).isEmpty();
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void roomsAndDropWorkBeforeLogin() {
        connection = IrcConnectionState.IDLE;

        assertThat(commands.rooms(null)).isEqualTo("目前沒有開著的房間（名額 0/4）");
        assertThat(commands.drop(List.of("r"), null)).isEqualTo("找不到結果未知的開房請求：r");
    }

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

    private static final String UNKNOWN_R = "開房結果未知：r；若遊戲內沒有這間房，用 drop r 釋放名額（之後不能再用這個房名）";

    @Test
    void addrefReportsEveryQueuedCommand() {
        matchService.attach(42);
        sender.calls.clear();

        assertThat(commands.addref(List.of("A", "#2"), 42L, null))
                .isEqualTo("已送出：!mp addref A" + System.lineSeparator() + "已送出：!mp addref #2");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp addref A", "msg #mp_42 !mp addref #2");
    }

    private void makeUntilUnknown(String name) {
        commands.make(List.of(name), null, null);
        await().atMost(Duration.ofSeconds(2)).until(() -> printed.stream().anyMatch(line -> line.startsWith("開房結果未知：" + name)));
    }

    @Test
    void makeTimeoutPrintsUnknownHint() {
        makeUntilUnknown("r");

        assertThat(printed).containsExactly(UNKNOWN_R);
    }

    @Test
    void lateReplyDoesNotSwitchCurrentRoom() throws Exception {
        makeUntilUnknown("r");
        matchService.attach(9);
        session.use(9);

        matchService.onMatchEvent(new MatchCreated(5, "r"));
        awaitLastMake();

        // 使用者可能已經在另一間房主持，晚到的房間不能悄悄變成目前房間
        assertThat(printed).contains("房間已建立（晚到的回覆）：#mp_5 r；用 use 5 切換過去");
        assertThat(session.currentRoom()).contains(9L);
    }

    @Test
    void droppedRequestDoesNotPrintFailure() throws Exception {
        makeUntilUnknown("r");

        commands.drop(List.of("r"), null);
        awaitLastMake();

        assertThat(printed).containsExactly(UNKNOWN_R);
    }

    @Test
    void dropReleasesSlot() {
        makeUntilUnknown("r");

        assertThat(commands.drop(List.of("r"), null)).isEqualTo("已釋放：r");
        assertThat(commands.drop(List.of("r"), null)).isEqualTo("找不到結果未知的開房請求：r");
        assertThat(matchService.usedSlots()).isZero();
        assertThat(commands.make(List.of("r"), null, null))
                .isEqualTo("錯誤：此房名已 drop，本次執行不能再用，請換一個房名：r");
    }

    @Test
    void makeRejectedWhenSlotsFull() {
        for (long id = 1; id <= 4; id++) {
            matchService.attach(id);
        }

        assertThat(commands.make(List.of("r"), null, null)).isEqualTo("錯誤：名額已滿（4/4）");
    }

    @Test
    void roomsListsPendingAndSlots() {
        matchService.attach(1);
        makeUntilUnknown("r");

        String rooms = commands.rooms(null);

        assertThat(rooms).contains("#mp_1", "結果未知：r");
        assertThat(rooms.lines().toList().getLast()).isEqualTo("名額 2/4");
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
    void hostRequiresAnOpenRoomAndValidTarget() {
        assertThat(commands.host(List.of("A"), null, null)).isEqualTo(NO_ROOM);
        assertThat(commands.host(List.of("A"), 999L, null)).isEqualTo("找不到房間：999");
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.host(List.of(), 42L, null)).startsWith("錯誤：");
        connection = IrcConnectionState.CONNECTING;
        assertThat(commands.host(List.of("A"), 42L, null)).contains("正在連線");
        assertThat(sender.calls).isEmpty();
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
        commands.password(List.of(), true, 42L, null);
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
        String setUsage = "錯誤：用法：set <隊伍模式：h2h/tag/teamvs/tagvs 或 0–3> "
                + "<計分模式：score/accuracy/combo/scorev2 或 0–3> <人數> [--room <房號>]；例如 set teamvs v2 4";
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
        assertThat(commands.mp(List.of(), 42L, null)).contains("例如 mp lock", "指定房主請用 host");
        for (String subcommand : List.of("make", "MAKE", "MakePrivate")) {
            assertThat(commands.mp(List.of(subcommand, "x"), 42L, null)).isEqualTo("錯誤：開房請用 make 指令");
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void mpPasswordIsRejected() {
        matchService.attach(42);
        sender.calls.clear();
        String expected = "錯誤：設定密碼請用 password 指令（移除請用 password --clear）";
        assertThat(commands.mp(List.of("password", "secret"), 42L, null)).isEqualTo(expected);
        assertThat(commands.mp(List.of("PaSsWoRd"), 42L, null)).isEqualTo(expected);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void passwordIsReadFromPrompt() {
        matchService.attach(42);
        sender.calls.clear();
        typed = Optional.of("abc");
        assertThat(commands.password(List.of(), false, 42L, null)).isEqualTo("已送出：!mp password ***");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp password abc");
        assertThat(prompts).containsExactly("新密碼：");
    }

    @Test
    void cancelledPromptSendsNothing() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.password(List.of(), false, 42L, null)).isEqualTo("已取消，密碼沒有變更");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void clearRemovesPassword() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.password(List.of(), true, 42L, null)).isEqualTo("已送出：!mp password（移除密碼）");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp password");
        assertThat(prompts).isEmpty();
    }

    @Test
    void passwordOnCommandLineIsRejected() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.password(List.of("abc"), false, 42L, null))
                .isEqualTo("錯誤：密碼請在提示中輸入，不要寫在指令裡（會留在指令歷史）");
        assertThat(sender.calls).isEmpty();
        assertThat(prompts).isEmpty();
    }

    @Test
    void passwordWithoutRoomDoesNotPrompt() {
        assertThat(commands.password(List.of(), false, null, null)).isEqualTo(NO_ROOM);
        assertThat(prompts).isEmpty();
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
    void modsRejectsConcatenatedNames() {
        matchService.attach(42);
        sender.calls.clear();
        assertThat(commands.mods(List.of("NFHD"), 42L, null)).isEqualTo("錯誤：mod 要用空白分開，例如 mods NF HD");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void roomsShowsGameMode() {
        matchService.attach(42);
        assertThat(commands.rooms(null)).contains("mode ?");
    }

    @Test
    void roomsListsOpenRooms() {
        assertThat(commands.rooms(null)).isEqualTo("目前沒有開著的房間（名額 0/4）");

        matchService.onMatchEvent(new MatchCreated(1, "a"));

        assertThat(commands.rooms(null)).contains("#mp_1 a");
    }
}
