package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import cc.alexlin7.bancho.irc.IrcMessage;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;

@ExtendWith(OutputCaptureExtension.class)
class BanchoBotListenerTest {

    private static final String UNRECOGNIZED = "未辨識的 BanchoBot 訊息";

    private final List<Object> published = new ArrayList<>();
    private final BanchoBotListener listener = new BanchoBotListener(published::add);

    private void receive(String rawLine) {
        listener.onMessage(IrcMessage.parse(rawLine).orElseThrow());
    }

    @Test
    void publishesParsedEvents(CapturedOutput output) {
        receive(":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Alexlin86 joined in slot 1.");

        assertThat(published).containsExactly(new PlayerJoined(1, "Alexlin86", 1));
        assertThat(output).contains("[match]", "PlayerJoined");
    }

    @Test
    void logsUnrecognizedBanchoBotText(CapturedOutput output) {
        receive(":BanchoBot!cho@ppy.sh PRIVMSG #mp_7 :Something new happened");

        assertThat(published).isEmpty();
        assertThat(output).contains(UNRECOGNIZED, "Something new happened");
    }

    @Test
    void staysSilentForIgnoredLines(CapturedOutput output) {
        receive(":BanchoBot!cho@ppy.sh PRIVMSG #mp_7 :Players: 1");

        assertThat(published).isEmpty();
        assertThat(output).doesNotContain(UNRECOGNIZED);
    }

    @Test
    void ignoresOtherUsers(CapturedOutput output) {
        receive(":Alexlin86!cho@ppy.sh PRIVMSG #mp_1 :Closed the match");

        assertThat(published).isEmpty();
        assertThat(output).doesNotContain(UNRECOGNIZED);
    }
}
