package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
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
        return lines(history);
    }

    /** 經過實際的 JLine 輸入流程（比照 shell：只有雙引號、行尾反斜線續行），回傳寫進歷史的內容。 */
    private List<String> recordedThroughReader(String typed) throws IOException {
        terminal = new DumbTerminal("dumb", Terminal.TYPE_DUMB,
                new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)), OutputStream.nullOutputStream(),
                StandardCharsets.UTF_8);
        PasswordSafeHistory history = new PasswordSafeHistory();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).history(history)
                .parser(new DefaultParser().quoteChars(new char[] {'"'})
                        .eofOnUnclosedQuote(true).eofOnEscapedNewLine(true))
                .build();
        reader.readLine("shell:>");
        return lines(history);
    }

    private static List<String> lines(History history) {
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
    void skipsQuotedOrEscapedFormsThatParseToAPasswordCommand() throws IOException {
        // parser 會移除雙引號與反斜線跳脫，送出的仍是 !mp password hunter2
        assertThat(recorded(
                "say \"!mp\" \"password\" hunter2",
                "say !mp pass\\word hunter2",
                "mp \"password\" hunter2",
                "\"password\" hunter2")).isEmpty();
    }

    @Test
    void skipsPasswordSplitByLineContinuation() throws IOException {
        // 行尾反斜線續行會被合併成 say !mp password hunter2
        assertThat(recordedThroughReader("say !mp pass\\\rword hunter2\r")).isEmpty();
    }

    @Test
    void skipsPasswordInsideMultilineQuote() throws IOException {
        // 引號內的換行送出時會變成空白：!mp password hunter2
        assertThat(recordedThroughReader("say \"!mp\rpassword hunter2\"\r")).isEmpty();
    }

    @Test
    void keepsOrdinaryCommandsTypedThroughReader() throws IOException {
        assertThat(recordedThroughReader("say gl hf\r")).containsExactly("say gl hf");
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
