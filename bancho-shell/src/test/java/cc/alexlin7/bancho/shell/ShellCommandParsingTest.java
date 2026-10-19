package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.test.ShellTestClient;

import cc.alexlin7.bancho.irc.RecordingIrcSender;
import cc.alexlin7.bancho.match.MatchService;

/**
 * 經由 Spring Shell 真正的 parser 執行指令，驗證「使用者打的字」與「送出的 !mp 指令」一致：
 * 不可悄悄丟掉參數、截斷名稱，或忽略打錯的選項。
 */
@SpringBootTest
class ShellCommandParsingTest {

    @TestConfiguration
    static class RecordingSenderConfiguration {

        @Bean
        @Primary
        RecordingIrcSender recordingIrcSender() {
            return new RecordingIrcSender();
        }

        /** 測試沒有終端機，提示一律回答固定的訊息。 */
        @Bean
        @Primary
        TextPrompt scriptedTextPrompt() {
            return prompt -> Optional.of("hello");
        }
    }

    @Autowired
    private CommandParser parser;

    @Autowired
    private CommandRegistry registry;

    @Autowired
    private RecordingIrcSender sender;

    @Autowired
    private MatchService matchService;

    @Autowired
    private ShellSession session;

    private ShellTestClient client;

    @BeforeEach
    void setUp() {
        client = new ShellTestClient(parser, registry);
        matchService.attach(42);
        sender.calls.clear();
    }

    private String run(String command) throws Exception {
        return String.join("\n", client.sendCommand(command).lines());
    }

    @Test
    void startAndTimerTakePositionalValues() throws Exception {
        run("start 10 --room 42");
        run("timer 60 --room 42");

        assertThat(sender.calls).containsExactly("msg #mp_42 !mp start 10", "msg #mp_42 !mp timer 60");
    }

    @Test
    void withoutValueStartAndTimerUseDefaults() throws Exception {
        run("start --room 42");
        run("timer --room 42");

        assertThat(sender.calls).containsExactly("msg #mp_42 !mp start", "msg #mp_42 !mp timer");
    }

    @Test
    void passwordInNonInteractiveModeIsAnError() throws Exception {
        assertThat(run("password --room 42")).contains("錯誤：非互動模式無法輸入密碼");
        run("password --clear --room 42");
        assertThat(run("password hunter2 --room 42")).contains("不要寫在指令裡");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp password");
    }

    @Test
    void dropJoinsAllWordsIntoRoomName() throws Exception {
        assertThat(run("drop 第一場 練習")).contains("找不到結果未知的開房請求：第一場 練習");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void misspelledOptionIsRejectedInsteadOfIgnored() throws Exception {
        assertThat(run("start --dealy 10 --room 42")).contains("不認得的選項：--dealy");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void extraArgumentsAreRejectedInsteadOfIgnored() throws Exception {
        assertThat(run("abort now --room 42")).contains("多餘的參數：now");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void makeKeepsWholeRoomName() throws Exception {
        run("make OWC A vs B");

        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make OWC A vs B");
    }

    @Test
    void inviteKeepsWholeUsername() throws Exception {
        run("invite Mr Tester --room 42");

        assertThat(sender.calls).containsExactly("msg #mp_42 !mp invite Mr Tester");
    }

    @Test
    void inviteExplainsThatExplicitTargetsMustBeInvitedOneAtATime() throws Exception {
        for (String command : List.of("invite", "host")) {
            for (String targets : List.of("#1 #2", "#1 Alexlin86", "osu.ppy.sh/users/1 #2", "Alexlin86 #2")) {
                assertThat(run(command + " " + targets + " --room 42"))
                        .contains("一次只能指定一位玩家").doesNotContain("ID 必須是正整數");
            }
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void inviteAcceptsProfileLinksAndExplicitIds() throws Exception {
        assertThat(run("invite https://osu.ppy.sh/users/2548278 --room 42"))
                .isEqualTo("已送出：!mp invite #2548278");
        run("invite #2548278 --room 42");
        run("invite 2548278 --room 42");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp invite #2548278",
                "msg #mp_42 !mp invite #2548278", "msg #mp_42 !mp invite 2548278");
    }

    @Test
    void invitePreservesFullWidthCharacters() throws Exception {
        run("invite \"- Ｓ Ｈ -\" --room 42");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp invite - Ｓ Ｈ -");
    }

    @Test
    void hostAcceptsNamesIdsAndProfiles() throws Exception {
        assertThat(run("host \"- Ｓ Ｈ -\" --room 42")).isEqualTo("已送出：!mp host - Ｓ Ｈ -");
        run("host #2548278 --room 42");
        run("host https://osu.ppy.sh/users/2548278 --room 42");
        session.use(42);
        run("host -- -SH-");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp host - Ｓ Ｈ -",
                "msg #mp_42 !mp host #2548278", "msg #mp_42 !mp host #2548278", "msg #mp_42 !mp host -SH-");
    }

    @Test
    void invalidPlayerTargetsSendNothing() throws Exception {
        for (String command : List.of("invite", "host")) {
            for (String target : List.of("#0", "#abc", "https://example.com/users/2548278",
                    "https://osu.ppy.sh/users/nope")) {
                assertThat(run(command + " " + target + " --room 42")).startsWith("錯誤：");
            }
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void setAcceptsModeNamesAndAliasesIgnoringCase() throws Exception {
        for (String team : List.of("h2h", "headtohead", "0")) {
            run("set " + team + " accuracy 2 --room 42");
        }
        run("set TAG v1 2 --room 42");
        run("set tagteam scorev1 2 --room 42");
        run("set tagcoop score 2 --room 42");
        run("set teamvs V2 4 --room 42");
        run("set --room 42 tagvs combo 4");
        run("set tagteamvs scorev2 4 --room 42");
        run("set 2 acc 4 --room 42");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp set 0 1 2", "msg #mp_42 !mp set 0 1 2",
                "msg #mp_42 !mp set 0 1 2", "msg #mp_42 !mp set 1 0 2", "msg #mp_42 !mp set 1 0 2",
                "msg #mp_42 !mp set 1 0 2", "msg #mp_42 !mp set 2 3 4", "msg #mp_42 !mp set 3 2 4",
                "msg #mp_42 !mp set 3 3 4", "msg #mp_42 !mp set 2 1 4");
    }

    @Test
    void setRejectsUnknownModesWithoutSending() throws Exception {
        assertThat(run("set catch v2 4 --room 42")).contains("不認得的隊伍模式", "h2h", "teamvs");
        assertThat(run("set teamvs nope 4 --room 42")).contains("不認得的計分模式", "accuracy", "scorev2");
        assertThat(run("set teamvs v2 4 extra --room 42")).contains("多餘的參數：extra");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void hostRejectsMissingTargetUnknownOptionsAndRoomAfterDoubleDash() throws Exception {
        session.use(42);
        assertThat(run("host --room 42")).startsWith("錯誤：");
        assertThat(run("host A --rom 42")).contains("不認得的選項");
        assertThat(run("host -- -SH- --room 42")).contains("host \"-SH-\" --room 42");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void helpExplainsPlayerIdsAndSetModeAliases() throws Exception {
        assertThat(run("host --help")).contains("#2548278", "host -- -SH-", "--room");
        assertThat(run("invite --help")).contains("#2548278", "https://osu.ppy.sh/users/2548278");
        assertThat(run("set --help")).contains("headtohead", "tagcoop", "accuracy", "scorev2", "map");
    }

    @Test
    void playerCommandRepliesMatchResolvedIrcTargets() throws Exception {
        assertThat(run("invite osu.ppy.sh/users/2548278 --room 42"))
                .isEqualTo("已送出：!mp invite #2548278");
        assertThat(run("host https://osu.ppy.sh/users/2548278/fruits --room 42"))
                .isEqualTo("已送出：!mp host #2548278");
        assertThat(run("addref osu.ppy.sh/users/2548278/fruits #42 --room 42"))
                .isEqualTo("已送出：!mp addref #2548278 #42");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp invite #2548278",
                "msg #mp_42 !mp host #2548278", "msg #mp_42 !mp addref #2548278 #42");
    }

    @Test
    void namedProfilesAndInvalidRefereesSendNothing() throws Exception {
        assertThat(run("invite osu.ppy.sh/users/Alexlin86 --room 42"))
                .contains("數字 ID", "直接輸入玩家名稱");
        assertThat(run("addref #42 #0 --room 42")).startsWith("錯誤：");
        assertThat(run("addref \"Minato Yukina\" --room 42")).contains("#ID");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void malformedSchemeLessUrlsAreErrorsInsteadOfQueuedNames() throws Exception {
        for (String command : List.of("invite", "host", "addref")) {
            assertThat(run(command + " www.osu.ppy.sh/users/2548278 --room 42"))
                    .startsWith("錯誤：").contains("玩家網址").doesNotContain("已送出");
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void chatWordStartingWithDashIsReportedNotDropped() throws Exception {
        assertThat(run("say score was -5 lol --room 42")).contains("不認得的選項：-5");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void missingSetArgumentsShowUsageInsteadOfPrimitiveError() throws Exception {
        for (String command : List.of("set", "set 2", "set --room 42 2", "set 0 3 --room 42")) {
            assertThat(run(command)).contains("錯誤：用法：set").doesNotContain("primitive");
        }
        assertThat(run("map --room 42")).contains("錯誤：用法：map");
        assertThat(run("set a b c --room 42")).contains("錯誤：用法：set");
        assertThat(run("map abc --room 42")).contains("錯誤：用法：map");
        assertThat(run("attach abc")).contains("錯誤：用法：attach");
        assertThat(run("use abc")).contains("錯誤：用法：use");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void mapTakesOptionalMode() throws Exception {
        run("map 2453358 2 --room 42");
        run("map 2453358 MANIA --room 42");
        run("map --room 42 2453358 Ctb");
        assertThat(run("map 2453358 --room 42")).isEqualTo("已送出：!mp map 2453358");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp map 2453358 2",
                "msg #mp_42 !mp map 2453358 3", "msg #mp_42 !mp map 2453358 2", "msg #mp_42 !mp map 2453358");
    }

    @Test
    void mapRejectsUnknownModeAndExtraArguments() throws Exception {
        assertThat(run("map 2453358 abc --room 42")).contains("不認得的遊玩模式：abc");
        assertThat(run("map 2453358 4 --room 42")).contains("不認得的遊玩模式：4");
        assertThat(run("map 2453358 3 x --room 42")).contains("多餘的參數：x");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void mpForwardsAllArguments() throws Exception {
        run("mp host \"-SH-\" --room 42");
        run("mp kick Mr Tester --room 42");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp host -SH-", "msg #mp_42 !mp kick Mr Tester");
    }

    @Test
    void mpRejectsCreationAndPassword() throws Exception {
        assertThat(run("mp --room 42")).startsWith("錯誤：");
        assertThat(run("mp make x --room 42")).contains("開房請用 make");
        assertThat(run("mp MakePrivate x --room 42")).contains("開房請用 make");
        assertThat(run("mp password secret --room 42")).contains("設定密碼請用 password 指令");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void quotedDashNameIsSent() throws Exception {
        run("addref \"-SH-\" --room 42");
        run("invite \"-SH-\" --room 42");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp addref -SH-", "msg #mp_42 !mp invite -SH-");
    }

    @Test
    void doubleDashEscapesNameInCurrentRoom() throws Exception {
        session.use(42);
        run("addref -- -SH-");
        assertThat(sender.calls).containsExactly("msg #mp_42 !mp addref -SH-");
    }

    @Test
    void roomAfterDoubleDashIsRejected() throws Exception {
        matchService.attach(7);
        session.use(7);
        sender.calls.clear();
        assertThat(run("addref -- -SH- --room 42")).contains("addref \"-SH-\" --room 42");
        assertThat(run("invite -- -SH- --room 42")).contains("invite \"-SH-\" --room 42")
                .doesNotContain("addref");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void roomEqualsAfterDoubleDashIsRejected() throws Exception {
        matchService.attach(7);
        session.use(7);
        sender.calls.clear();
        for (String command : java.util.List.of("addref", "invite", "host")) {
            for (String option : java.util.List.of("--room=42", "--room=")) {
                assertThat(run(command + " -- -SH- " + option)).startsWith("錯誤：")
                        .contains(command + " \"-SH-\" --room 42");
            }
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void unknownOptionMentionsBothEscapes() throws Exception {
        assertThat(run("say score was -5 lol --room 42")).contains("\"-SH-\"", "-- -SH-");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void helpExplainsEscaping() throws Exception {
        assertThat(run("help addref")).contains("addref --help");
        assertThat(run("help invite")).contains("invite --help");
        assertThat(run("addref --help")).contains("addref \"-SH-\"", "addref -- -SH-");
        assertThat(run("invite --help")).contains("invite \"-SH-\"", "invite -- -SH-")
                .doesNotContain("addref");
    }

    @Test
    void quotedChatTextIsSentAsIs() throws Exception {
        run("say \"score was -5 lol\" --room 42");

        assertThat(sender.calls).containsExactly("msg #mp_42 score was -5 lol");
    }

    @Test
    void msgKeepsWholeTargetNameWithSpaces() throws Exception {
        assertThat(run("msg Minato Yukina")).contains("已送出給 Minato Yukina：hello");
        assertThat(run("msg \"Minato Yukina\"")).contains("已送出給 Minato Yukina：hello");

        assertThat(sender.calls).containsExactly("msg Minato Yukina hello", "msg Minato Yukina hello");
    }

    @Test
    void msgAcceptsDashNamesAndRejectsUnknownOptions() throws Exception {
        run("msg \"-SH-\"");
        run("msg -- -SH-");
        assertThat(run("msg BanchoBot --rom 42")).startsWith("錯誤：");

        assertThat(sender.calls).containsExactly("msg -SH- hello", "msg -SH- hello");
    }
}
