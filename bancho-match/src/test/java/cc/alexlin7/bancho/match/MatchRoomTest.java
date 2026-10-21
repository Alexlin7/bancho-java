package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import cc.alexlin7.bancho.irc.RecordingIrcSender;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchClosed;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;

class MatchRoomTest {

    private final RecordingIrcSender sender = new RecordingIrcSender();
    private final MatchRoom room = new MatchRoom(123, "r", sender);

    static Stream<Arguments> commands() {
        return Stream.of(
                Arguments.of((Consumer<MatchRoom>) r -> r.invite("Alexlin86"), "!mp invite Alexlin86"),
                Arguments.of((Consumer<MatchRoom>) r -> r.host("https://osu.ppy.sh/users/2548278"), "!mp host #2548278"),
                Arguments.of((Consumer<MatchRoom>) r -> r.host("- Ｓ Ｈ -"), "!mp host - Ｓ Ｈ -"),
                Arguments.of((Consumer<MatchRoom>) r -> r.set(0, 3, 2), "!mp set 0 3 2"),
                Arguments.of((Consumer<MatchRoom>) r -> r.map(1502772), "!mp map 1502772"),
                Arguments.of((Consumer<MatchRoom>) r -> r.map(2453358, GameMode.MANIA), "!mp map 2453358 3"),
                Arguments.of((Consumer<MatchRoom>) r -> r.map(2453358, null), "!mp map 2453358"),
                Arguments.of((Consumer<MatchRoom>) r -> r.raw(" host Alexlin86 "), "!mp host Alexlin86"),
                Arguments.of((Consumer<MatchRoom>) r -> r.mods("HD", "HR"), "!mp mods HD HR"),
                Arguments.of((Consumer<MatchRoom>) MatchRoom::start, "!mp start"),
                Arguments.of((Consumer<MatchRoom>) r -> r.start(Duration.ofSeconds(10)), "!mp start 10"),
                Arguments.of((Consumer<MatchRoom>) r -> r.timer(Duration.ofSeconds(30)), "!mp timer 30"),
                Arguments.of((Consumer<MatchRoom>) MatchRoom::timer, "!mp timer"),
                Arguments.of((Consumer<MatchRoom>) MatchRoom::abort, "!mp abort"),
                Arguments.of((Consumer<MatchRoom>) MatchRoom::close, "!mp close"),
                Arguments.of((Consumer<MatchRoom>) MatchRoom::settings, "!mp settings"),
                Arguments.of((Consumer<MatchRoom>) r -> r.password("abc"), "!mp password abc"),
                Arguments.of((Consumer<MatchRoom>) r -> r.password(null), "!mp password"),
                Arguments.of((Consumer<MatchRoom>) r -> r.password(" "), "!mp password"),
                Arguments.of((Consumer<MatchRoom>) r -> r.addRef("A"), "!mp addref A"),
                Arguments.of((Consumer<MatchRoom>) r -> r.say("gl hf"), "gl hf"));
    }

    @ParameterizedTest
    @MethodSource("commands")
    void sendsCommandToRoomChannel(Consumer<MatchRoom> action, String expected) {
        action.accept(room);

        assertThat(sender.calls).containsExactly("msg #mp_123 " + expected);
    }

    @Test
    void exposesIdentity() {
        assertThat(room.matchId()).isEqualTo(123);
        assertThat(room.channel()).isEqualTo("#mp_123");
        assertThat(room.state().name()).isEqualTo("r");
    }

    @Test
    void inviteConvertsProfileUrlsWithoutChangingUnicodeNames() {
        room.invite("https://osu.ppy.sh/users/2548278");
        room.invite("https://osu.ppy.sh/u/2548278/");
        room.invite("#2548278");
        room.invite("- Ｓ Ｈ -");
        assertThat(sender.calls).containsExactly("msg #mp_123 !mp invite #2548278",
                "msg #mp_123 !mp invite #2548278", "msg #mp_123 !mp invite #2548278",
                "msg #mp_123 !mp invite - Ｓ Ｈ -");
    }

    @Test
    void inviteRejectsInvalidReferencesAndControlCharactersBeforeSending() {
        for (String target : java.util.List.of("#0", "#-1", "#abc", "#99999999999999999999999999",
                "https://example.com/users/2548278", "https://osu.ppy.sh.evil/users/2548278",
                "https://osu.ppy.sh/users/not-an-id", "https://osu.ppy.sh/users/2548278/extra",
                "https://user@osu.ppy.sh/users/2548278", "A\r\nPRIVMSG BanchoBot :!mp make wrong", "A\u0000B")) {
            assertThatThrownBy(() -> room.invite(target)).as(target).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void playerCommandsAcceptSchemeLessProfilesAndRulesetSuffixes() {
        room.invite("osu.ppy.sh/users/2548278");
        for (String mode : java.util.List.of("osu", "taiko", "fruits", "mania")) {
            room.host("https://osu.ppy.sh/users/2548278/" + mode);
        }
        room.addRef("osu.ppy.sh/users/2548278/fruits", "#42");
        assertThat(sender.calls).containsExactly("msg #mp_123 !mp invite #2548278",
                "msg #mp_123 !mp host #2548278", "msg #mp_123 !mp host #2548278",
                "msg #mp_123 !mp host #2548278", "msg #mp_123 !mp host #2548278",
                "msg #mp_123 !mp addref #2548278", "msg #mp_123 !mp addref #42");
    }

    @Test
    void namedProfileExplainsHowToUseANameWithoutSending() {
        assertThatThrownBy(() -> room.invite("https://osu.ppy.sh/users/Alexlin86"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("數字 ID", "直接輸入玩家名稱");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void slashTargetsNeverFallBackToPlayerNames() {
        for (String target : java.util.List.of("www.osu.ppy.sh/users/2548278", "osu.ppy.shh/users/2548278",
                "example.com/users/2548278", "osu.ppy.sh.evil/users/2548278", "/users/2548278", "Some/Player")) {
            assertThatThrownBy(() -> room.invite(target)).as("invite %s", target)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("玩家網址");
            assertThatThrownBy(() -> room.host(target)).as("host %s", target)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("玩家網址");
            assertThatThrownBy(() -> room.addRef("#42", target)).as("addref %s", target)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("玩家網址");
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void addRefRejectsInvalidReferencesAtomically() {
        assertThatThrownBy(() -> room.addRef("#42", "https://example.com/users/1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> room.addRef("#0")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void playerTargetsExplainMultipleExplicitReferencesConsistently() {
        for (String target : java.util.List.of("#1 #2", "#1 Alexlin86", "osu.ppy.sh/users/1 #2")) {
            assertThatThrownBy(() -> room.host(target)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("一次只能指定一位玩家");
            assertThatThrownBy(() -> room.invite(target)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("一次只能指定一位玩家");
            assertThatThrownBy(() -> room.addRef(target)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("一次只能指定一位玩家");
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void playerNamesCannotContainHashes() {
        for (String target : java.util.List.of("Alexlin86 #2", "Alexlin#86", "#1#2")) {
            assertThatThrownBy(() -> room.invite(target)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("玩家名稱不可含 #");
            assertThatThrownBy(() -> room.host(target)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("玩家名稱不可含 #");
            assertThatThrownBy(() -> room.addRef(target)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("玩家名稱不可含 #");
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void profileFragmentsAreAllowedWhileNamesRejectHashes() {
        room.invite("osu.ppy.sh/users/2548278/fruits#medals");
        room.host("https://osu.ppy.sh/users/2548278#top");
        assertThat(sender.calls).containsExactly("msg #mp_123 !mp invite #2548278", "msg #mp_123 !mp host #2548278");
    }

    static Stream<ThrowingCallable> invalidArguments() {
        MatchRoom target = new MatchRoom(1, "r", new RecordingIrcSender());
        return Stream.of(
                () -> target.set(4, 0, 2),
                () -> target.set(0, -1, 2),
                () -> target.set(0, 0, 0),
                () -> target.set(0, 0, 17),
                () -> target.mods("HD HR"),
                target::mods,
                () -> target.raw(" "),
                () -> target.raw(null),
                () -> target.invite(" "),
                () -> target.host(" "),
                () -> target.host("#0"),
                target::addRef,
                () -> target.start(Duration.ofSeconds(-1)));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidArguments(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concatenatedModsAreRejected() {
        assertThatThrownBy(() -> room.mods("NFHD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mod 要用空白分開，例如 mods NF HD");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void everyKnownModIsAccepted() {
        for (String mod : ModNames.KNOWN) {
            room.mods(mod);
        }
        room.mods("sv2");
        assertThat(sender.calls).hasSize(ModNames.KNOWN.size() + 1).contains("msg #mp_123 !mp mods SV2");
    }

    @Test
    void freemodIsAccepted() {
        room.mods("Freemod");
        room.mods("freemod");
        room.mods("HD", "HR");
        assertThat(sender.calls).containsExactly(
                "msg #mp_123 !mp mods Freemod", "msg #mp_123 !mp mods freemod", "msg #mp_123 !mp mods HD HR");
    }

    @Test
    void addRefSendsStrippedNames() {
        room.addRef(" Alice ", "Bob");

        assertThat(sender.calls).containsExactly("msg #mp_123 !mp addref Alice", "msg #mp_123 !mp addref Bob");
    }

    @Test
    void addRefSendsOneCommandPerTarget() {
        assertThat(room.addRef("Saru86", "#3538446", "https://osu.ppy.sh/users/2548278"))
                .containsExactly("!mp addref Saru86", "!mp addref #3538446", "!mp addref #2548278");
        assertThat(sender.calls).containsExactly("msg #mp_123 !mp addref Saru86",
                "msg #mp_123 !mp addref #3538446", "msg #mp_123 !mp addref #2548278");
    }

    @Test
    void addRefRejectsAnyInvalidTargetBeforeSending() {
        assertThatThrownBy(() -> room.addRef("Saru86", "Tomari Mari"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("#ID");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void addRefRejectsNamesWithSpaces() {
        assertThatThrownBy(() -> room.addRef("Some Player")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void invalidArgumentsSendNothing() {
        assertThatThrownBy(() -> room.mods("HD HR")).isInstanceOf(IllegalArgumentException.class);

        assertThat(sender.calls).isEmpty();
    }

    @Test
    void rejectsCommandsAfterClosed() {
        room.apply(new MatchClosed(123));

        assertThatThrownBy(() -> room.invite("A")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> room.host("A")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> room.raw("host x")).isInstanceOf(IllegalStateException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void appliesEventsToState() {
        room.apply(new PlayerJoined(123, "A", 1));

        assertThat(room.state().slots()).containsKey(1);
    }
}
