package idv.alexlin7.tw.bancho.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import idv.alexlin7.tw.bancho.irc.RecordingIrcSender;

class ConsoleCommandInterpreterTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private RecordingIrcSender sender;
    private ConsoleCommandInterpreter interpreter;

    @BeforeEach
    void setUp() {
        sender = new RecordingIrcSender();
        interpreter = new ConsoleCommandInterpreter(sender, new PrintStream(buffer, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void plainTextGoesToCurrentTarget() {
        interpreter.execute("/target BanchoBot");
        interpreter.execute("!mp make 測試房");

        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make 測試房");
        assertThat(output()).contains("目前對象：BanchoBot");
    }

    @Test
    void plainTextWithoutTargetIsNotSent() {
        interpreter.execute("hello");

        assertThat(sender.calls).isEmpty();
        assertThat(output()).contains("尚未設定對象");
    }

    @Test
    void joinSwitchesTargetAndPartClearsIt() {
        interpreter.execute("/join #mp_123");
        assertThat(interpreter.currentTarget()).isEqualTo("#mp_123");

        interpreter.execute("/part #mp_123");

        assertThat(sender.calls).containsExactly("join #mp_123", "part #mp_123");
        assertThat(interpreter.currentTarget()).isNull();
    }

    @Test
    void msgSendsWithoutChangingTarget() {
        interpreter.execute("/target #mp_1");
        interpreter.execute("/msg Some_Player  hi there ");

        assertThat(sender.calls).containsExactly("msg Some_Player hi there");
        assertThat(interpreter.currentTarget()).isEqualTo("#mp_1");
    }

    @Test
    void commandsAreCaseInsensitive() {
        interpreter.execute("/TARGET BanchoBot");

        assertThat(interpreter.currentTarget()).isEqualTo("BanchoBot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/target", "/join", "/part", "/msg", "/msg onlyTarget"})
    void missingArgumentsPrintUsage(String line) {
        interpreter.execute(line);

        assertThat(sender.calls).isEmpty();
        assertThat(output()).contains("用法：");
    }

    @Test
    void senderValidationErrorsArePrintedNotThrown() {
        interpreter.execute("/join mp_1");

        assertThat(output()).contains("錯誤：頻道名稱必須以 # 開頭");
        assertThat(interpreter.currentTarget()).isNull();
    }

    @Test
    void unknownCommandAndHelp() {
        interpreter.execute("/nope");
        interpreter.execute("/help");

        assertThat(output()).contains("未知指令：/nope", "/target <對象>");
    }

    @Test
    void blankLinesAreIgnored() {
        interpreter.execute("   ");

        assertThat(sender.calls).isEmpty();
        assertThat(output()).isEmpty();
    }

}
