package idv.alexlin7.tw.bancho.irc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IrcMessageTest {

    static Stream<Arguments> validLines() {
        return Stream.of(
                Arguments.of(":BanchoBot!cho@ppy.sh PRIVMSG #mp_123 :All players are ready",
                        "BanchoBot!cho@ppy.sh", "PRIVMSG", List.of("#mp_123", "All players are ready")),
                Arguments.of("PING :cho.ppy.sh", null, "PING", List.of("cho.ppy.sh")),
                Arguments.of("PING cho.ppy.sh", null, "PING", List.of("cho.ppy.sh")),
                Arguments.of(":cho.ppy.sh 001 test_user :Welcome to the osu!Bancho.",
                        "cho.ppy.sh", "001", List.of("test_user", "Welcome to the osu!Bancho.")),
                // trailing 內的 " :" 與冒號都要原樣保留
                Arguments.of(":a!a@x PRIVMSG #osu :hi :) see: this", "a!a@x", "PRIVMSG", List.of("#osu", "hi :) see: " +
                        "this")),
                Arguments.of(":a!a@x PRIVMSG #osu ::)", "a!a@x", "PRIVMSG", List.of("#osu", ":)")),
                Arguments.of(":a!a@x PRIVMSG #osu :", "a!a@x", "PRIVMSG", List.of("#osu", "")),
                // 訊息內容剛好包含數字代碼或其他指令名稱，不能影響 command
                Arguments.of(":a!a@x PRIVMSG #mp_1 :team 001 ready", "a!a@x", "PRIVMSG", List.of("#mp_1", "team 001 " +
                        "ready")),
                Arguments.of(":cho.ppy.sh 332 me #mp_1 :topic PRIVMSG", "cho.ppy.sh", "332", List.of("me", "#mp_1",
                        "topic PRIVMSG")),
                Arguments.of("QUIT", null, "QUIT", List.of()),
                Arguments.of("privmsg #a :lower", null, "PRIVMSG", List.of("#a", "lower")),
                Arguments.of(":srv  MODE   #a  +o  nick", "srv", "MODE", List.of("#a", "+o", "nick")),
                Arguments.of("@time=2026-01-01T00:00:00Z :a!a@x PRIVMSG #a :tagged", "a!a@x", "PRIVMSG", List.of("#a"
                        , "tagged")),
                Arguments.of(":a!a@x PRIVMSG #a :中文 與 emoji 🎵", "a!a@x", "PRIVMSG", List.of("#a", "中文 與 emoji 🎵"))
        );
    }

    @ParameterizedTest
    @MethodSource("validLines")
    void parsesValidLines(String line, String prefix, String command, List<String> params) {
        assertThat(IrcMessage.parse(line)).hasValueSatisfying(message -> {
            assertThat(message.prefix()).isEqualTo(prefix);
            assertThat(message.command()).isEqualTo(command);
            assertThat(message.params()).containsExactlyElementsOf(params);
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", ":prefix-only", "@tags-only", ":srv :no-command", ":srv 12 short-numeric", ":srv " +
            "P1NG bad"})
    void rejectsInvalidLines(String line) {
        assertThat(IrcMessage.parse(line)).isEmpty();
    }

    @Test
    void exposesNickParamAndTrailing() {
        IrcMessage message = IrcMessage.parse(":Some_Player!cho@ppy.sh PRIVMSG #mp_1 :!mp ready").orElseThrow();

        assertThat(message.nick()).isEqualTo("Some_Player");
        assertThat(message.param(0)).isEqualTo("#mp_1");
        assertThat(message.param(5)).isEmpty();
        assertThat(message.trailing()).isEqualTo("!mp ready");
    }

    @Test
    void nickFallsBackWhenPrefixHasNoUserPart() {
        assertThat(IrcMessage.parse(":cho.ppy.sh 001 me :hi").orElseThrow().nick()).isEqualTo("cho.ppy.sh");
        assertThat(IrcMessage.parse("PING :x").orElseThrow().nick()).isEmpty();
        assertThat(IrcMessage.parse("QUIT").orElseThrow().trailing()).isEmpty();
    }
}
