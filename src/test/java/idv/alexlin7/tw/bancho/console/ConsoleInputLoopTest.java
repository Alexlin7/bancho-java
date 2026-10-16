package idv.alexlin7.tw.bancho.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.irc.IrcSender;
import idv.alexlin7.tw.bancho.irc.RecordingIrcSender;
import idv.alexlin7.tw.bancho.match.MatchService;

class ConsoleInputLoopTest {

    @Test
    void feedsEveryInputLineToInterpreterUntilEof() throws Exception {
        CountDownLatch sent = new CountDownLatch(2);
        IrcSender sender = new RecordingIrcSender() {
            @Override
            public void sendMessage(String target, String message) {
                super.sendMessage(target, message);
                sent.countDown();
            }
        };
        var interpreter = new ConsoleCommandInterpreter(sender, new MatchService(sender, Duration.ofSeconds(1)), new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
        var input = new BufferedReader(new StringReader("/target BanchoBot\n!mp make room\n!mp close\n"));

        try (var loop = new ConsoleInputLoop(input, interpreter)) {
            loop.start();
            assertThat(sent.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(((RecordingIrcSender) sender).calls)
                .containsExactly("msg BanchoBot !mp make room", "msg BanchoBot !mp close");
    }
}
