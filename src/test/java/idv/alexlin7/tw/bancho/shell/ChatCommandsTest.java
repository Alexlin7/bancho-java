package idv.alexlin7.tw.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.irc.RecordingIrcSender;
import idv.alexlin7.tw.bancho.match.MatchService;

class ChatCommandsTest {

    private final RecordingIrcSender sender = new RecordingIrcSender();
    private final MatchService matchService = new MatchService(sender, Duration.ofSeconds(1));
    private final ShellSession session = new ShellSession();
    private final ChatCommands commands = new ChatCommands(sender, session, matchService);

    @Test
    void sayJoinsWordsAndSendsToRoom() {
        matchService.attach(42);

        assertThat(commands.say(List.of("gl", "hf"), 42L)).isEqualTo("已送出：gl hf");
        assertThat(sender.calls).contains("msg #mp_42 gl hf");
    }

    @Test
    void sayUsesCurrentRoom() {
        matchService.attach(42);
        session.use(42);

        commands.say(List.of("hi"), null);

        assertThat(sender.calls).contains("msg #mp_42 hi");
    }

    @Test
    void msgSendsPrivateMessage() {
        assertThat(commands.msg("BanchoBot", List.of("!mp", "make", "x"))).isEqualTo("已送出：BanchoBot !mp make x");
        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make x");
    }

    @Test
    void msgWithoutTextExplains() {
        assertThat(commands.msg("BanchoBot", List.of())).startsWith("錯誤：");
        assertThat(sender.calls).isEmpty();
    }
}
