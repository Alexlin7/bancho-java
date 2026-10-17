package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jline.reader.History;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PasswordSafeHistoryTest {

    private Terminal terminal;

    @AfterEach
    void closeTerminal() throws IOException {
        if (terminal != null) {
            terminal.close();
        }
    }

    private List<String> recorded(String... lines) throws IOException {
        terminal = new DumbTerminal("dumb", Terminal.TYPE_DUMB, InputStream.nullInputStream(),
                OutputStream.nullOutputStream(), StandardCharsets.UTF_8);
        PasswordSafeHistory history = new PasswordSafeHistory();
        LineReaderBuilder.builder().terminal(terminal).history(history).build();
        for (String line : lines) {
            history.add(line);
        }
        List<String> kept = new ArrayList<>();
        history.forEach(entry -> kept.add(entry.line()));
        return kept;
    }

    @Test
    void skipsLinesThatCarryARoomPassword() throws IOException {
        assertThat(recorded(
                "say !mp password hunter2",
                "say \"!MP Password hunter2\"",
                "mp password hunter2 --room 42",
                "password hunter2",
                "password --room 42 hunter2")).isEmpty();
    }

    @Test
    void keepsOrdinaryCommands() throws IOException {
        assertThat(recorded("make 練習房", "say gl hf", "mp host Alexlin86", "addref Alexlin86"))
                .containsExactly("make 練習房", "say gl hf", "mp host Alexlin86", "addref Alexlin86");
    }

    @Test
    void isTheHistoryBeanSpringShellUses() {
        History history = new ShellConfiguration().history();

        assertThat(history).isInstanceOf(PasswordSafeHistory.class);
    }
}
