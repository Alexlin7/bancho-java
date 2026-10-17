package idv.alexlin7.tw.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JLineSecretPromptTest {

    private Terminal terminal;

    @AfterEach
    void closeTerminal() throws IOException {
        if (terminal != null) {
            terminal.close();
        }
    }

    private LineReader readerWithInput(String input) throws IOException {
        terminal = TerminalBuilder.builder().system(false).dumb(true)
                .streams(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), OutputStream.nullOutputStream())
                .build();
        return LineReaderBuilder.builder().terminal(terminal).build();
    }

    /** 比照 Spring Shell 的 reader：引號未閉合或行尾跳脫時繼續讀、以反斜線跳脫，並有上一則指令的歷史。 */
    private LineReader shellLikeReaderWithInput(String input) throws IOException {
        LineReader reader = readerWithInput(input);
        ((LineReaderImpl) reader).setParser(new DefaultParser().eofOnUnclosedQuote(true).eofOnEscapedNewLine(true));
        reader.getHistory().add("rooms");
        return reader;
    }

    @Test
    void readsTypedSecret() throws IOException {
        assertThat(new JLineSecretPrompt(readerWithInput("hunter2\r")).read("新密碼：")).contains("hunter2");
    }

    @Test
    void blankInputCancels() throws IOException {
        assertThat(new JLineSecretPrompt(readerWithInput("  \r")).read("新密碼：")).isEmpty();
    }

    @Test
    void endOfInputCancels() throws IOException {
        assertThat(new JLineSecretPrompt(readerWithInput("")).read("新密碼：")).isEqualTo(Optional.empty());
    }

    @Test
    void secretIsNotAddedToHistory() throws IOException {
        LineReader reader = readerWithInput("hunter2\r");
        new JLineSecretPrompt(reader).read("新密碼：");
        assertThat(reader.getHistory().size()).isZero();
    }

    @Test
    void historyExpansionIsNotApplied() throws IOException {
        assertThat(new JLineSecretPrompt(shellLikeReaderWithInput("Pa$$!!word\r")).read("新密碼：")).contains("Pa$$!!word");
    }

    @Test
    void backslashIsKept() throws IOException {
        assertThat(new JLineSecretPrompt(shellLikeReaderWithInput("x\\y\r")).read("新密碼：")).contains("x\\y");
    }

    @Test
    void unclosedQuoteDoesNotKeepReading() throws IOException {
        assertThat(new JLineSecretPrompt(shellLikeReaderWithInput("a\"b\r")).read("新密碼：")).contains("a\"b");
    }

    @Test
    void readerSettingsAreRestoredAfterReading() throws IOException {
        LineReader reader = shellLikeReaderWithInput("hunter2\r");
        Parser parser = reader.getParser();
        new JLineSecretPrompt(reader).read("新密碼：");
        assertThat(reader.getParser()).isSameAs(parser);
        assertThat(reader.isSet(LineReader.Option.DISABLE_EVENT_EXPANSION)).isFalse();
    }
}
