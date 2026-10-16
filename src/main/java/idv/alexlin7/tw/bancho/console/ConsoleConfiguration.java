package idv.alexlin7.tw.bancho.console;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import idv.alexlin7.tw.bancho.irc.IrcSender;
import idv.alexlin7.tw.bancho.match.MatchService;

/**
 * 終端機前端：印出收到的訊息、讀取鍵盤輸入送出。設 {@code bancho.console.enabled=false} 可關閉。
 */
@Configuration
@ConditionalOnProperty(prefix = "bancho.console", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConsoleConfiguration {

    /**
     * 終端機前端的輸出本來就是 stdout，不是 log。
     */
    @Bean
    @SuppressWarnings("java:S106")
    public PrintStream consoleOut() {
        return System.out;
    }

    @Bean
    public ConsoleMessageListener consoleMessageListener(PrintStream consoleOut) {
        return new ConsoleMessageListener(consoleOut);
    }

    @Bean(destroyMethod = "close")
    public ConsoleInputLoop consoleInputLoop(IrcSender sender, MatchService matchService, PrintStream consoleOut) {
        // JDK 25 起 stdin.encoding 反映終端機實際編碼（Windows 主控台可能不是 UTF-8）
        Charset charset = Charset.forName(System.getProperty("stdin.encoding", Charset.defaultCharset().name()));
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, charset));
        return new ConsoleInputLoop(input, new ConsoleCommandInterpreter(sender, matchService, consoleOut));
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> consoleInputStarter(ConsoleInputLoop loop,
                                                                          PrintStream consoleOut) {
        return event -> {
            consoleOut.println(ConsoleCommandInterpreter.HELP);
            loop.start();
        };
    }
}
