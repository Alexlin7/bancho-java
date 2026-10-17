package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.shell.jline.JLineInputProvider;

class InterruptAwareInputProviderTest {

    private static final String HINT = "（再按一次 Ctrl+C 離開，或輸入 quit）";

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CtrlCGuardTest.MutableClock clock = new CtrlCGuardTest.MutableClock();
    private Terminal terminal;
    private LineReader reader;

    @BeforeEach
    void setUp() throws IOException {
        // 明確指定 UTF-8：不指定時 writer 用 JVM 的 stdout.encoding，CI 的 Windows runner 不是 UTF-8，中文會變成 ?
        terminal = TerminalBuilder.builder().system(false).dumb(true)
                .encoding(StandardCharsets.UTF_8).stdoutEncoding(StandardCharsets.UTF_8)
                .streams(InputStream.nullInputStream(), output).build();
        reader = LineReaderBuilder.builder().terminal(terminal).build();
    }

    @AfterEach
    void closeTerminal() throws IOException {
        terminal.close();
    }

    /** 依序回傳字串，或在元素為 UserInterruptException 時丟出。 */
    private InterruptAwareInputProvider providerReading(Object... inputs) {
        Deque<Object> queue = new ArrayDeque<>(List.of(inputs));
        JLineInputProvider delegate = new JLineInputProvider(reader) {
            @Override
            public String readInput() {
                Object next = queue.removeFirst();
                if (next instanceof UserInterruptException e) {
                    throw e;
                }
                return (String) next;
            }
        };
        return new InterruptAwareInputProvider(delegate, new CtrlCGuard(clock, Duration.ofSeconds(2)));
    }

    private String printed() {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void returnsTypedLine() {
        assertThat(providerReading("rooms").readInput()).isEqualTo("rooms");
    }

    @Test
    void firstInterruptOnEmptyLineWarnsAndContinues() {
        assertThat(providerReading(new UserInterruptException("")).readInput()).isEmpty();
        assertThat(printed()).contains(HINT);
    }

    @Test
    void secondInterruptWithinWindowExits() {
        InterruptAwareInputProvider provider = providerReading(
                new UserInterruptException(""), new UserInterruptException(""));
        provider.readInput();
        clock.advance(Duration.ofSeconds(1));
        assertThat(provider.readInput()).isNull();
    }

    @Test
    void interruptWithPartialLineOnlyClearsIt() {
        assertThat(providerReading(new UserInterruptException("passw")).readInput()).isEmpty();
        assertThat(printed()).doesNotContain(HINT);
    }

    @Test
    void typedLineDisarms() {
        InterruptAwareInputProvider provider = providerReading(
                new UserInterruptException(""), "rooms", new UserInterruptException(""));
        provider.readInput();
        provider.readInput();
        assertThat(provider.readInput()).isEmpty();
    }

    @Test
    void keepsTheSameLineReader() {
        assertThat(providerReading().getLineReader()).isSameAs(reader);
    }
}
