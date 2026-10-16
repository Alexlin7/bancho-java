package idv.alexlin7.tw.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.io.OutputStream;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.shell.jline.ExtendedDefaultParser;

class ShellLineEditingTest {

    private final ShellLineEditing customizer = new ShellLineEditing();

    @Test
    void completionIgnoresCase() throws Exception {
        try (Terminal terminal = TerminalBuilder.builder().system(false).dumb(true)
                .streams(InputStream.nullInputStream(), OutputStream.nullOutputStream()).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            customizer.postProcessAfterInitialization(reader, "lineReader");

            assertThat(reader.isSet(LineReader.Option.CASE_INSENSITIVE)).isTrue();
        }
    }

    @Test
    void apostropheDoesNotOpenAQuote() {
        ExtendedDefaultParser parser = new ExtendedDefaultParser();
        parser.setEofOnUnclosedQuote(true);

        customizer.postProcessAfterInitialization(parser, "parser");

        assertThatCode(() -> parser.parse("say I'm ready", 13, Parser.ParseContext.ACCEPT_LINE))
                .doesNotThrowAnyException();
        assertThat(parser.parse("say \"a b\" c", 11, Parser.ParseContext.ACCEPT_LINE).words())
                .containsExactly("say", "a b", "c");
    }
}
