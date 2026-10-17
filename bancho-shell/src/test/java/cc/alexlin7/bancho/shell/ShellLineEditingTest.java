package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.shell.jline.ExtendedDefaultParser;
import org.springframework.shell.jline.JLineInputProvider;
import org.springframework.shell.jline.CommandCompleter;
import org.springframework.shell.core.command.Command;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.completion.CompletionProposal;

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

    /** 上游不使用 dontQuote；既有開引號會殘留，因此不啟用帶引號的使用者候選值。 */
    @Test
    @Timeout(5)
    void prequotedCandidateDuplicatesAnAlreadyTypedOpeningQuote() throws Exception {
        var command = Command.builder().name("addref")
                .completionProvider(context -> java.util.List.of(new CompletionProposal("\"-SH-\"").dontQuote(true)))
                .execute(context -> { });
        var registry = new CommandRegistry(Set.of(command));
        try (var input = new PipedInputStream(); var keyboard = new PipedOutputStream(input);
             Terminal terminal = TerminalBuilder.builder().system(false).type("xterm")
                     .streams(input, OutputStream.nullOutputStream()).build()) {
            keyboard.write("addref \"-S\t\r".getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
            ExtendedDefaultParser parser = new ExtendedDefaultParser();
            customizer.postProcessAfterInitialization(parser, "parser");
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).parser(parser)
                    .completer(new CommandCompleter(registry)).build();
            customizer.postProcessAfterInitialization(reader, "lineReader");
            assertThat(reader.readLine()).isEqualTo("addref \"\"-SH-\" ");
        }
    }

    @Test
    void wrapsInputProviderOnce() throws Exception {
        try (Terminal terminal = TerminalBuilder.builder().system(false).dumb(true)
                .streams(InputStream.nullInputStream(), OutputStream.nullOutputStream()).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            Object wrapped = customizer.postProcessAfterInitialization(new JLineInputProvider(reader), "inputProvider");

            assertThat(wrapped).isInstanceOf(InterruptAwareInputProvider.class);
            assertThat(((JLineInputProvider) wrapped).getLineReader()).isSameAs(reader);
            assertThat(customizer.postProcessAfterInitialization(wrapped, "inputProvider")).isSameAs(wrapped);
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
