package idv.alexlin7.tw.bancho.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.irc.IrcMessage;

class ConsoleMessageListenerTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final ConsoleMessageListener listener =
            new ConsoleMessageListener(new PrintStream(buffer, true, StandardCharsets.UTF_8));

    private String print(String rawLine) {
        listener.onMessage(IrcMessage.parse(rawLine).orElseThrow());
        return buffer.toString(StandardCharsets.UTF_8).strip();
    }

    @Test
    void printsChannelMessageWithChannelName() {
        assertThat(print(":BanchoBot!cho@ppy.sh PRIVMSG #mp_123 :All players are ready"))
                .isEqualTo("[#mp_123] BanchoBot: All players are ready");
    }

    @Test
    void printsPrivateMessageAsPm() {
        assertThat(print(":BanchoBot!cho@ppy.sh PRIVMSG test_user :你好"))
                .isEqualTo("[PM] BanchoBot: 你好");
    }

    @Test
    void printsCtcpActionLikeMe() {
        assertThat(print(":Someone!cho@ppy.sh PRIVMSG test_user :\u0001ACTION is listening to a song\u0001"))
                .isEqualTo("[PM] * Someone is listening to a song");
    }
}
