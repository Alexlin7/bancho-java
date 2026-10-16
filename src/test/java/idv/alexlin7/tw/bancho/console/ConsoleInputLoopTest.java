package idv.alexlin7.tw.bancho.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.IrcSender;

class ConsoleInputLoopTest {

    @Test
    void feedsEveryInputLineToInterpreterUntilEof() throws Exception {
        CountDownLatch sent = new CountDownLatch(2);
        IrcSender sender = new ConsoleCommandInterpreterTest.RecordingSender() {
            @Override
            public void sendMessage(String target, String message) {
                super.sendMessage(target, message);
                sent.countDown();
            }
        };
        var interpreter = new ConsoleCommandInterpreter(sender, new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
        var input = new BufferedReader(new StringReader("/target BanchoBot\n!mp make room\n!mp close\n"));

        try (var loop = new ConsoleInputLoop(input, interpreter)) {
            loop.start();
            assertThat(sent.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(((ConsoleCommandInterpreterTest.RecordingSender) sender).calls)
                .containsExactly("msg BanchoBot !mp make room", "msg BanchoBot !mp close");
    }
}
