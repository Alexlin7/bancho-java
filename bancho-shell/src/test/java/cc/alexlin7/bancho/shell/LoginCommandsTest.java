package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cc.alexlin7.bancho.irc.FakeIrcServer;
import cc.alexlin7.bancho.irc.IrcConnectionState;
import cc.alexlin7.bancho.irc.OsuIrcClient;
import cc.alexlin7.bancho.irc.OsuIrcSettings;

class LoginCommandsTest {

    private static final Duration WAIT = Duration.ofSeconds(3);
    private static final String AUTH_FAILED = "登入失敗：帳號或 IRC 密碼錯誤，請重新 login";
    private static final String ENV_HINT = "（有設定 OSU_IRC_USERNAME／OSU_IRC_PASSWORD 環境變數時，啟動就會自動登入）；"
            + "要換帳號請修改或清除環境變數後重開程式";

    private final Deque<Optional<String>> usernames = new ArrayDeque<>();
    private final Deque<Optional<String>> passwords = new ArrayDeque<>();
    private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
    private final List<String> printed = Collections.synchronizedList(new ArrayList<>());

    private FakeIrcServer server;
    private OsuIrcClient client;
    private LoginCommands commands;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeIrcServer();
        client = new OsuIrcClient(new OsuIrcSettings("127.0.0.1", server.port(), null, null,
                new OsuIrcSettings.Timing(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ZERO,
                        Duration.ofMillis(100), Duration.ofSeconds(1))), List.of());
        commands = new LoginCommands(client,
                prompt -> {
                    prompts.add(prompt);
                    return usernames.removeFirst();
                },
                prompt -> {
                    prompts.add(prompt);
                    return passwords.removeFirst();
                });
        new ConnectionStatusPrinter(client, printed::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.close();
    }

    private String login(String username, String password) {
        // 取消帳號時不會讀密碼，上一輪沒用到的回答要清掉
        usernames.clear();
        passwords.clear();
        usernames.add(Optional.ofNullable(username));
        passwords.add(Optional.ofNullable(password));
        return commands.login(null);
    }

    @Test
    void loginPromptsAndConnects() throws Exception {
        assertThat(login("test user", "secret")).isEqualTo("登入中：test user");

        assertThat(server.awaitLine("PASS secret", WAIT)).isNotNull();
        assertThat(prompts).containsExactly("osu! 帳號：",
                "IRC 密碼（不是帳號密碼，到 https://osu.ppy.sh/home/account/edit#legacy-api 取得）：");
        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
        server.welcome();
        await().atMost(WAIT).until(() -> printed.contains("已登入 Bancho：test user"));
    }

    @Test
    void cancelledPromptDoesNotLogin() throws Exception {
        assertThat(login(null, null)).isEqualTo("已取消登入");
        assertThat(client.state()).isEqualTo(IrcConnectionState.IDLE);
        assertThat(server.awaitConnection(Duration.ofMillis(300))).isFalse();

        assertThat(login("test user", null)).isEqualTo("已取消登入");
        assertThat(client.state()).isEqualTo(IrcConnectionState.IDLE);

        assertThat(login("test user", "secret")).isEqualTo("登入中：test user");
    }

    @Test
    void loginWhileConnectingSaysSoAndMentionsEnvironmentVariables() {
        login("test user", "secret");

        assertThat(commands.login(null)).isEqualTo("錯誤：正在以 test user 連線 Bancho" + ENV_HINT);
    }

    @Test
    void loginWhenAlreadyRegisteredNamesAccountAndMentionsEnvironmentVariables() throws Exception {
        login("test user", "secret");
        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
        server.welcome();
        await().atMost(WAIT).until(() -> client.state() == IrcConnectionState.REGISTERED);

        assertThat(commands.login(null)).isEqualTo("錯誤：已登入為 test user" + ENV_HINT);
    }

    @Test
    void authFailurePrintsHintAndAllowsRetry() throws Exception {
        server.onConnect(s -> s.send(":cho.ppy.sh 464 test_user :Bad authentication token."));
        login("test user", "wrong");
        await().atMost(WAIT).until(() -> printed.contains(AUTH_FAILED));

        server.onConnect(s -> {
        });
        assertThat(login("test user", "right")).isEqualTo("登入中：test user");
        assertThat(server.awaitLine("PASS right", WAIT)).isNotNull();
    }

    @Test
    void startupMessageNamesAccountWhenCredentialsWereConfigured() throws Exception {
        try (OsuIrcClient configured = new OsuIrcClient(
                new OsuIrcSettings("127.0.0.1", server.port(), "env user", "secret", null), List.of())) {
            new ConnectionStatusPrinter(configured, printed::add).onApplicationStarted();
        }

        assertThat(printed).containsExactly("以設定的帳號 env user 自動登入（OSU_IRC_USERNAME／OSU_IRC_PASSWORD）");
    }

    @Test
    void startupHintWhenNoCredentials() {
        new ConnectionStatusPrinter(client, printed::add).onApplicationStarted();

        assertThat(printed).containsExactly("尚未登入，請先執行 login");
    }
}
