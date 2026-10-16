package idv.alexlin7.tw.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.irc.IrcMessage;

class ShellMessagePrinterTest {

    private final List<String> printed = new ArrayList<>();
    private final ShellMessagePrinter printer = new ShellMessagePrinter(printed::add);

    private List<String> print(String rawLine) {
        printer.onMessage(IrcMessage.parse(rawLine).orElseThrow());
        return printed;
    }

    @Test
    void printsChannelMessageWithChannelName() {
        assertThat(print(":BanchoBot!cho@ppy.sh PRIVMSG #mp_123 :All players are ready"))
                .containsExactly("[#mp_123] BanchoBot: All players are ready");
    }

    @Test
    void printsPrivateMessageAsPm() {
        assertThat(print(":BanchoBot!cho@ppy.sh PRIVMSG test_user :你好"))
                .containsExactly("[PM] BanchoBot: 你好");
    }

    @Test
    void printsCtcpActionLikeMe() {
        assertThat(print(":Someone!cho@ppy.sh PRIVMSG test_user :\u0001ACTION is listening to a song\u0001"))
                .containsExactly("[PM] * Someone is listening to a song");
    }
}
