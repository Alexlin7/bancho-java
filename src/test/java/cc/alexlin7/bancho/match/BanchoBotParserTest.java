package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import cc.alexlin7.bancho.irc.IrcMessage;
import cc.alexlin7.bancho.match.BanchoBotParser.Result;
import cc.alexlin7.bancho.match.event.MatchEvent;
import cc.alexlin7.bancho.match.event.MatchEvent.BeatmapChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchClosed;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchCreated;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchStarted;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchAborted;
import cc.alexlin7.bancho.match.event.MatchEvent.ModsChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerInvited;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerLeft;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerMoved;
import cc.alexlin7.bancho.match.event.MatchEvent.SettingsChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.SlotStatus;
import cc.alexlin7.bancho.match.event.MatchEvent.AllPlayersReady;
import cc.alexlin7.bancho.match.event.MatchEvent.GameModeChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchFinished;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerFinished;

class BanchoBotParserTest {

    private static final String ROOM = "#mp_122021273";
    private static final long ROOM_ID = 122021273L;

    private static Result parse(String nick, String target, String text) {
        return BanchoBotParser.parse(IrcMessage.parse(":" + nick + "!cho@ppy.sh PRIVMSG " + target + " :" + text).orElseThrow());
    }

    private static Result parse(String target, String text) {
        return parse("BanchoBot", target, text);
    }

    /** 取自實測原文；FreeMod 的 enabled 分支沿用既有格式。 */
    static Stream<Arguments> realSamples() {
        return Stream.of(
                Arguments.of("test_user", "Created the tournament match https://osu.ppy.sh/mp/122021273 測試房",
                        new MatchCreated(ROOM_ID, "測試房")),
                Arguments.of("test_user", "Created the tournament match https://osu.ppy.sh/mp/5 第一場 練習",
                        new MatchCreated(5, "第一場 練習")),
                Arguments.of(ROOM, "Closed the match", new MatchClosed(ROOM_ID)),
                Arguments.of("#mp_42", "The match has started!", new MatchStarted(42)),
                Arguments.of("#mp_42", "Aborted the match", new MatchAborted(42)),
                Arguments.of("#mp_42", "Disabled all mods, disabled FreeMod", new ModsChanged(42, List.of(), false)),
                Arguments.of("#mp_42", "Disabled all mods, enabled FreeMod", new ModsChanged(42, List.of(), true)),
                Arguments.of(ROOM, "Invited Alexlin86 to the room", new PlayerInvited(ROOM_ID, "Alexlin86")),
                Arguments.of(ROOM, "Alexlin86 joined in slot 1.", new PlayerJoined(ROOM_ID, "Alexlin86", 1)),
                Arguments.of(ROOM, "Alexlin86 moved to slot 2", new PlayerMoved(ROOM_ID, "Alexlin86", 2)),
                Arguments.of(ROOM, "Alexlin86 left the game.", new PlayerLeft(ROOM_ID, "Alexlin86")),
                Arguments.of(ROOM, "Changed match settings to 2 slots, HeadToHead, ScoreV2",
                        new SettingsChanged(ROOM_ID, 2, "HeadToHead", "ScoreV2")),
                Arguments.of(ROOM, "Changed beatmap to https://osu.ppy.sh/b/1502772 Asriel - Kegare Naki Yume",
                        new BeatmapChanged(ROOM_ID, 1502772, "Asriel - Kegare Naki Yume")),
                Arguments.of(ROOM, "Beatmap: https://osu.ppy.sh/b/1502772 Asriel - Kegare Naki Yume",
                        new BeatmapChanged(ROOM_ID, 1502772, "Asriel - Kegare Naki Yume")),
                Arguments.of(ROOM, "Enabled Hidden, HardRock, disabled FreeMod",
                        new ModsChanged(ROOM_ID, List.of("Hidden", "HardRock"), false)),
                Arguments.of(ROOM, "Changed match mode to CatchTheBeat", new GameModeChanged(ROOM_ID, "CatchTheBeat")),
                Arguments.of(ROOM, "Alexlin86 finished playing (Score: 9245186, PASSED).",
                        new PlayerFinished(ROOM_ID, "Alexlin86", 9245186, "PASSED")),
                Arguments.of(ROOM, "The match has finished!", new MatchFinished(ROOM_ID)),
                Arguments.of(ROOM, "All players are ready", new AllPlayersReady(ROOM_ID)),
                Arguments.of(ROOM, "Slot 1  Not Ready https://osu.ppy.sh/u/4841753 Alexlin86       ",
                        new SlotStatus(ROOM_ID, 1, "Not Ready", 4841753, "Alexlin86"))
        );
    }

    @ParameterizedTest
    @MethodSource("realSamples")
    void parsesRealSamples(String target, String text, MatchEvent expected) {
        assertThat(parse(target, text)).isEqualTo(new Result.Parsed(expected));
    }

    @Test
    void parsesUsernameWithSpaces() {
        assertThat(parse("#mp_1", "Some Player joined in slot 3."))
                .isEqualTo(new Result.Parsed(new PlayerJoined(1, "Some Player", 3)));
        assertThat(parse("#mp_1", "Some Player left the game."))
                .isEqualTo(new Result.Parsed(new PlayerLeft(1, "Some Player")));
        assertThat(parse("#mp_1", "Some Player finished playing (Score: 0, FAILED)."))
                .isEqualTo(new Result.Parsed(new PlayerFinished(1, "Some Player", 0, "FAILED")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Room name: 測試房, History: https://osu.ppy.sh/mp/122021273",
            "Team mode: HeadToHead, Win condition: ScoreV2",
            "Players: 1", "Started the match", "Countdown ends in 10 seconds", "Countdown ends in 1 second",
            "Countdown finished", "Changed the match password", "Removed the match password",
            "Active mods: NoFail, Hidden"})
    void ignoresKnownSettingsLines(String text) {
        assertThat(parse(ROOM, text)).isEqualTo(new Result.Ignored());
    }

    @Test
    void stillReportsErrorRepliesAsUnrecognized() {
        for (String text : List.of("User not found: alexlin7", "You have too many referees! Remove some to add new ones.")) {
            assertThat(parse(ROOM, text)).isEqualTo(new Result.Unrecognized(ROOM_ID, text));
        }
    }

    @Test
    void channelOnlyRulesDoNotApplyToPrivateMessages() {
        assertThat(parse("test_user", "The match has started!"))
                .isEqualTo(new Result.Unrecognized(null, "The match has started!"));
        assertThat(parse("test_user", "Countdown finished"))
                .isEqualTo(new Result.Unrecognized(null, "Countdown finished"));
    }

    @Test
    void reportsUnrecognizedText() {
        assertThat(parse("#mp_7", "Something new happened"))
                .isEqualTo(new Result.Unrecognized(7L, "Something new happened"));
        assertThat(parse("test_user", "You cannot create more than 4 rooms"))
                .isEqualTo(new Result.Unrecognized(null, "You cannot create more than 4 rooms"));
    }

    @Test
    void slotLineWithUnknownSuffixIsUnrecognizedInsteadOfCorruptingUsername() {
        String text = "Slot 1  Not Ready https://osu.ppy.sh/u/4841753 Alexlin86       [Host / Team Blue]";

        assertThat(parse(ROOM, text)).isEqualTo(new Result.Unrecognized(ROOM_ID, text));
    }

    @Test
    void ignoresMessagesNotFromBanchoBot() {
        assertThat(parse("Alexlin86", "#mp_1", "Closed the match")).isEqualTo(new Result.NotFromBanchoBot());
    }

    @Test
    void channelMessagesOutsideMpChannelsAreUnrecognized() {
        assertThat(parse("#osu", "Closed the match")).isEqualTo(new Result.Unrecognized(null, "Closed the match"));
    }
}
