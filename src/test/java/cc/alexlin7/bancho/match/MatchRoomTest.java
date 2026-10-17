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
                Arguments.of((Consumer<MatchRoom>) r -> r.addRef("A", "B"), "!mp addref A B"),
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

        assertThat(sender.calls).containsExactly("msg #mp_123 !mp addref Alice Bob");
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
        assertThatThrownBy(() -> room.raw("host x")).isInstanceOf(IllegalStateException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void appliesEventsToState() {
        room.apply(new PlayerJoined(123, "A", 1));

        assertThat(room.state().slots()).containsKey(1);
    }
}
