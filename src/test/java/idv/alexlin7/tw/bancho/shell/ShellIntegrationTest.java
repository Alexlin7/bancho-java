package idv.alexlin7.tw.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.test.ShellTestClient;

/**
 * 經由 Spring Shell 真正的 parser 與 registry 執行指令，確認註解、參數綁定與 bean 接線正確。
 */
@SpringBootTest
class ShellIntegrationTest {

    @Autowired
    private CommandParser parser;

    @Autowired
    private CommandRegistry registry;

    private ShellTestClient client;

    @BeforeEach
    void setUp() {
        client = new ShellTestClient(parser, registry);
    }

    private String screen(String command) throws Exception {
        return String.join("\n", client.sendCommand(command).lines());
    }

    @Test
    void roomsWithoutRooms() throws Exception {
        assertThat(screen("rooms")).contains("目前沒有開著的房間");
    }

    @Test
    void helpListsCommands() throws Exception {
        assertThat(screen("help")).contains("make", "invite", "say", "quit");
    }

    @Test
    void roomCommandWithoutRoomExplains() throws Exception {
        assertThat(screen("invite Alexlin86")).contains("請先用 make / use / attach 選擇房間");
    }

    @Test
    void optionalPositionalAndRoomOptionBind() throws Exception {
        assertThat(screen("timer --room 999")).contains("找不到房間：999");
        assertThat(screen("mods HD HR --room 999")).contains("找不到房間：999");
    }
}
