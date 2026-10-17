package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JLinePromptTest {

    private Terminal terminal;

    @AfterEach
    void closeTerminal() throws IOException {
        if (terminal != null) {
            terminal.close();
        }
    }

    private LineReader readerWithInput(String input) throws IOException {
        // 不用 TerminalBuilder.streams()：它建出的 ExternalTerminal 由 pump 執行緒讀完輸入就關閉串流，讀取時會印出 closed stream 警告
        terminal = new DumbTerminal("dumb", Terminal.TYPE_DUMB,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), OutputStream.nullOutputStream(),
                StandardCharsets.UTF_8);
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
        assertThat(JLinePrompt.secret(readerWithInput("hunter2\r")).read("新密碼：")).contains("hunter2");
    }

    @Test
    void blankInputCancels() throws IOException {
        assertThat(JLinePrompt.secret(readerWithInput("  \r")).read("新密碼：")).isEmpty();
    }

    @Test
    void endOfInputCancels() throws IOException {
        assertThat(JLinePrompt.secret(readerWithInput("")).read("新密碼：")).isEmpty();
    }

    @Test
    void secretIsNotAddedToHistory() throws IOException {
        LineReader reader = readerWithInput("hunter2\r");
        JLinePrompt.secret(reader).read("新密碼：");
        assertThat(reader.getHistory().size()).isZero();
    }

    @Test
    void historyExpansionIsNotApplied() throws IOException {
        assertThat(JLinePrompt.secret(shellLikeReaderWithInput("Pa$$!!word\r")).read("新密碼：")).contains("Pa$$!!word");
    }

    @Test
    void backslashIsKept() throws IOException {
        assertThat(JLinePrompt.secret(shellLikeReaderWithInput("x\\y\r")).read("新密碼：")).contains("x\\y");
    }

    @Test
    void unclosedQuoteDoesNotKeepReading() throws IOException {
        assertThat(JLinePrompt.secret(shellLikeReaderWithInput("a\"b\r")).read("新密碼：")).contains("a\"b");
    }

    @Test
    void readerSettingsAreRestoredAfterReading() throws IOException {
        LineReader reader = shellLikeReaderWithInput("hunter2\r");
        Parser parser = reader.getParser();
        JLinePrompt.secret(reader).read("新密碼：");
        assertThat(reader.getParser()).isSameAs(parser);
        assertThat(reader.isSet(LineReader.Option.DISABLE_EVENT_EXPANSION)).isFalse();
    }

    // ---------------------------------------------------------------- 不遮蔽的文字（msg 的對象與訊息）

    @Test
    void readsTextWithQuotesAndBangAsTyped() throws IOException {
        // !mp 開頭的 BanchoBot 指令不能被歷史展開，引號也要原樣送出
        assertThat(JLinePrompt.text(shellLikeReaderWithInput("!mp make \"A\" vs B\r")).read("訊息："))
                .contains("!mp make \"A\" vs B");
    }

    @Test
    void textIsNotAddedToHistory() throws IOException {
        LineReader reader = shellLikeReaderWithInput("Minato Yukina\r");
        JLinePrompt.text(reader).read("對象：");
        assertThat(reader.getHistory().size()).isOne();
        assertThat(reader.getVariable(LineReader.DISABLE_HISTORY)).isNull();
    }

    @Test
    void blankTextCancels() throws IOException {
        assertThat(JLinePrompt.text(readerWithInput("   \r")).read("訊息：")).isEmpty();
    }
}
