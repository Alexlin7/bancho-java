package cc.alexlin7.bancho.irc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OsuIrcClientTest {

    private static final Duration WAIT = Duration.ofSeconds(3);
    private static final Duration SHORT = Duration.ofMillis(300);
    private static final String MP_CHANNEL = "#mp_1";

    private FakeIrcServer server;
    private OsuIrcClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeIrcServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        server.close();
    }

    private OsuIrcSettings.Timing timing(Duration readTimeout, Duration sendInterval) {
        return new OsuIrcSettings.Timing(Duration.ofSeconds(2), readTimeout, sendInterval,
                Duration.ofMillis(100), Duration.ofSeconds(1));
    }

    private void startClient(OsuIrcSettings.Timing timing, IrcMessageListener handler) {
        client = new OsuIrcClient(new OsuIrcSettings("127.0.0.1", server.port(), "test user", "secret", timing),
                List.of(handler));
        client.start();
    }

    private void startClient(IrcMessageListener handler) {
        startClient(timing(Duration.ofSeconds(10), Duration.ZERO), handler);
    }

    private void startAndRegister(IrcMessageListener handler) throws InterruptedException {
        startClient(handler);
        assertThat(server.awaitConnection(WAIT)).isTrue();
        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
        server.welcome();
    }

    // ---------------------------------------------------------------- 登入

    @Test
    void registersWithCredentialsAndDoesNotJoinOsuChannel() throws Exception {
        startClient(message -> {
        });

        assertThat(server.awaitLine("PASS secret", WAIT)).isNotNull();
        assertThat(server.awaitLine("NICK test_user", WAIT)).isNotNull();
        assertThat(server.awaitLine("USER test_user 0 * :test user", WAIT)).isNotNull();
        server.welcome();
        assertThat(server.awaitLine(line -> line.startsWith("JOIN"), SHORT)).isNull();
    }

    @Test
    void answersServerPing() throws Exception {
        startAndRegister(message -> {
        });

        server.send("PING :cho.ppy.sh");

        assertThat(server.awaitLine("PONG :cho.ppy.sh", WAIT)).isNotNull();
    }

    // ---------------------------------------------------------------- 入站

    @Test
    void dispatchesPrivmsgInArrivalOrder() throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(200);
        startAndRegister(message -> {
            received.add(message.trailing());
            done.countDown();
        });

        IntStream.range(0, 200).forEach(i -> server.send(":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :" + i));

        assertThat(done.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(received).containsExactlyElementsOf(IntStream.range(0, 200).mapToObj(String::valueOf).toList());
    }

    @Test
    void messageBodyLookingLikeNumericIsDeliveredAndDoesNotTriggerJoin() throws Exception {
        BlockingQueue<IrcMessage> received = new LinkedBlockingQueue<>();
        startAndRegister(received::add);

        server.send(":cho.ppy.sh 332 test_user #mp_1 :topic mentions PRIVMSG");
        server.send(":someone!a@ppy.sh PRIVMSG #mp_1 :team 001 ready");

        IrcMessage message = received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(message).isNotNull();
        assertThat(message.trailing()).isEqualTo("team 001 ready");
        assertThat(received).isEmpty();
        assertThat(server.awaitLine(line -> line.startsWith("JOIN"), SHORT)).isNull();
    }

    @Test
    void handlerExceptionIsLoggedAndDispatchContinues(CapturedOutput output) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        startAndRegister(message -> {
            if (message.trailing().equals("boom")) {
                throw new IllegalStateException("handler exploded");
            }
            received.add(message.trailing());
        });

        server.send(":a!a@x PRIVMSG #mp_1 :boom");
        server.send(":a!a@x PRIVMSG #mp_1 :after");

        assertThat(received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("after");
        assertThat(output).contains("處理訊息時發生例外", "handler exploded");
    }

    @Test
    void notifiesEveryListenerEvenIfOneFails() throws Exception {
        BlockingQueue<String> second = new LinkedBlockingQueue<>();
        client = new OsuIrcClient(new OsuIrcSettings("127.0.0.1", server.port(), "test user", "secret",
                timing(Duration.ofSeconds(10), Duration.ZERO)), List.of(
                message -> {
                    throw new IllegalStateException("first listener exploded");
                },
                message -> second.add(message.trailing())));
        client.start();
        assertThat(server.awaitConnection(WAIT)).isTrue();

        server.send(":a!a@x PRIVMSG #mp_1 :one");
        server.send(":a!a@x PRIVMSG #mp_1 :two");

        assertThat(second.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("one");
        assertThat(second.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("two");
    }

    @Test
    void rejectsNullListeners() {
        OsuIrcSettings settings = new OsuIrcSettings("127.0.0.1", server.port(), "test user", "secret", null);
        List<IrcMessageListener> withNull = new ArrayList<>();
        withNull.add(null);
        // null 混進清單時，dispatcher 處理第一則訊息就會因 NPE 結束，之後的 listener 全部收不到訊息
        assertThatThrownBy(() -> new OsuIrcClient(settings, withNull)).isInstanceOf(NullPointerException.class);
        try (OsuIrcClient other = new OsuIrcClient(settings, List.of())) {
            assertThatThrownBy(() -> other.addListener(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void addedListenerIsNotifiedAfterConstructorListeners() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        client = new OsuIrcClient(new OsuIrcSettings("127.0.0.1", server.port(), "test user", "secret",
                timing(Duration.ofSeconds(10), Duration.ZERO)), List.of(message -> received.add("constructor")));
        // 需要回話的 listener 要拿 client 當 IrcSender，只能在 client 建好之後才加入
        client.addListener(message -> received.add("added:" + message.trailing()));
        client.start();
        assertThat(server.awaitConnection(WAIT)).isTrue();

        server.send(":a!a@x PRIVMSG #mp_1 :hi");

        assertThat(received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("constructor");
        assertThat(received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("added:hi");
    }

    // ---------------------------------------------------------------- 出站

    @Test
    void stripsLineBreaksFromMessageBody() throws Exception {
        startAndRegister(message -> {
        });

        client.sendMessage(MP_CHANNEL, "hello\r\n!mp close");

        assertThat(server.awaitLine(line -> line.startsWith("PRIVMSG"), WAIT)).isEqualTo("PRIVMSG #mp_1 :hello  !mp " +
                "close");
        assertThat(server.awaitLine("!mp close", SHORT)).isNull();
    }

    @Test
    void rejectsTargetsContainingLineBreaks() {
        startClient(message -> {
        });

        assertThatThrownBy(() -> client.sendMessage("#mp_1\r\nQUIT", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.sendMessage(" ", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.join("mp_1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void privateMessageTargetReplacesSpacesWithUnderscores() throws Exception {
        startAndRegister(message -> {
        });

        client.sendMessage("Some Player", "hi");

        assertThat(server.awaitLine("PRIVMSG Some_Player :hi", WAIT)).isNotNull();
    }

    @Test
    void truncatesOverlongMessagesWithinLineLimit() throws Exception {
        startAndRegister(message -> {
        });

        client.sendMessage(MP_CHANNEL, "あ".repeat(600));

        String line = server.awaitLine(l -> l.startsWith("PRIVMSG"), WAIT);
        assertThat(line).isNotNull().startsWith("PRIVMSG #mp_1 :あ").doesNotContain("�");
        assertThat(line.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(IrcText.MAX_LINE_BYTES);
    }

    @Test
    void queuesMessagesUntilRegistered() throws Exception {
        startClient(message -> {
        });
        assertThat(server.awaitConnection(WAIT)).isTrue();

        client.sendMessage(MP_CHANNEL, "early");
        assertThat(server.awaitLine(line -> line.startsWith("PRIVMSG"), SHORT)).isNull();

        server.welcome();
        assertThat(server.awaitLine("PRIVMSG #mp_1 :early", WAIT)).isNotNull();
    }

    @Test
    void throttlesOutgoingMessages() throws Exception {
        Duration interval = Duration.ofMillis(200);
        startClient(timing(Duration.ofSeconds(10), interval), message -> {
        });
        assertThat(server.awaitConnection(WAIT)).isTrue();
        server.welcome();

        IntStream.range(0, 3).forEach(i -> client.sendMessage(MP_CHANNEL, "m" + i));

        assertThat(server.awaitLine("PRIVMSG #mp_1 :m0", WAIT)).isNotNull();
        long first = System.nanoTime();
        assertThat(server.awaitLine("PRIVMSG #mp_1 :m2", WAIT)).isNotNull();
        long elapsed = System.nanoTime() - first;
        assertThat(Duration.ofNanos(elapsed)).isGreaterThanOrEqualTo(interval.multipliedBy(2).minusMillis(30));
    }

    // ---------------------------------------------------------------- 連線生命週期

    @Test
    void sendsKeepalivePingAndReconnectsWhenServerGoesSilent() throws Exception {
        startClient(timing(Duration.ofMillis(200), Duration.ZERO), message -> {
        });
        assertThat(server.awaitConnection(WAIT)).isTrue();
        server.welcome();

        assertThat(server.awaitLine(line -> line.startsWith("PING "), WAIT)).isNotNull();
        assertThat(server.awaitConnection(WAIT)).isTrue();
        assertThat(server.connectionCount()).isEqualTo(2);
    }

    @Test
    void staysConnectedWhileServerAnswersKeepalivePing() throws Exception {
        server.answerPing();
        startClient(timing(Duration.ofMillis(200), Duration.ZERO), message -> {
        });
        assertThat(server.awaitConnection(WAIT)).isTrue();
        server.welcome();

        assertThat(server.awaitConnection(Duration.ofSeconds(1))).isFalse();
        assertThat(server.connectionCount()).isEqualTo(1);
    }

    @Test
    void stopsReconnectingAfterAuthenticationFailure(CapturedOutput output) throws Exception {
        server.onConnect(s -> {
            s.send(":cho.ppy.sh 464 test_user :Bad authentication token.");
            s.dropConnection();
        });
        startClient(message -> {
        });

        assertThat(server.awaitConnection(WAIT)).isTrue();
        assertThat(server.awaitConnection(Duration.ofSeconds(1))).isFalse();
        assertThat(output).contains("認證失敗，停止重連");
    }

    @Test
    void treatsBadAuthenticationNoticeAsAuthenticationFailure() throws Exception {
        server.onConnect(s -> s.send(":cho.ppy.sh NOTICE * :Bad authentication token."));
        startClient(message -> {
        });

        assertThat(server.awaitConnection(WAIT)).isTrue();
        assertThat(server.awaitConnection(Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void reconnectDelayGrowsExponentially() throws Exception {
        server.onConnect(FakeIrcServer::dropConnection);
        startClient(message -> {
        });

        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Long time = server.awaitConnectionTime(WAIT);
            assertThat(time).isNotNull();
            times.add(time);
        }
        Duration firstGap = Duration.ofNanos(times.get(1) - times.get(0));
        Duration thirdGap = Duration.ofNanos(times.get(3) - times.get(2));
        // 100ms → 200ms → 400ms
        assertThat(firstGap).isGreaterThanOrEqualTo(Duration.ofMillis(90));
        assertThat(thirdGap).isGreaterThanOrEqualTo(Duration.ofMillis(350));
    }

    @Test
    void rejoinsChannelsAfterReconnect() throws Exception {
        startAndRegister(message -> {
        });
        client.join(MP_CHANNEL);
        client.join("#mp_2");
        client.part("#mp_2");
        assertThat(server.awaitLine("JOIN #mp_1", WAIT)).isNotNull();

        server.dropConnection();
        assertThat(server.awaitConnection(WAIT)).isTrue();
        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
        server.welcome();

        assertThat(server.awaitLine("JOIN #mp_1", WAIT)).isNotNull();
        assertThat(server.awaitLine("JOIN #mp_2", SHORT)).isNull();
    }

    @Test
    void closeSendsQuit() throws Exception {
        startAndRegister(message -> {
        });
        assertThat(server.awaitLine(line -> line.startsWith("PING "), SHORT)).isNull();

        client.close();

        assertThat(server.awaitLine("QUIT :Shutdown", WAIT)).isNotNull();
    }

    @Test
    void closeIsSafeBeforeStartAndIdempotent() {
        OsuIrcClient neverStarted = new OsuIrcClient(new OsuIrcSettings("127.0.0.1", server.port(), "u", "p", null)
                , List.of(message -> {
        }));

        assertThatCode(() -> {
            neverStarted.close();
            neverStarted.close();
            neverStarted.start();
        }).doesNotThrowAnyException();
        assertThat(server.connectionCount()).isZero();
    }

    // ---------------------------------------------------------------- 延後登入

    private void createDeferredClient(IrcConnectionListener stateListener) {
        client = new OsuIrcClient(new OsuIrcSettings("127.0.0.1", server.port(), null, null,
                timing(Duration.ofSeconds(10), Duration.ZERO)), List.of(message -> {
        }));
        if (stateListener != null) {
            client.addConnectionListener(stateListener);
        }
    }

    @Test
    void startWithoutCredentialsFails() throws Exception {
        createDeferredClient(null);

        assertThatThrownBy(client::start).isInstanceOf(IllegalStateException.class);
        assertThat(client.state()).isEqualTo(IrcConnectionState.IDLE);
        assertThat(client.hasCredentials()).isFalse();
        assertThat(server.awaitConnection(SHORT)).isFalse();
    }

    @Test
    void loginConnectsWithGivenCredentials() throws Exception {
        createDeferredClient(null);

        client.login("test user", "secret");

        assertThat(server.awaitLine("PASS secret", WAIT)).isNotNull();
        assertThat(server.awaitLine("NICK test_user", WAIT)).isNotNull();
        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
        server.welcome();
        awaitState(IrcConnectionState.REGISTERED);
        assertThat(client.hasCredentials()).isTrue();
    }

    @Test
    void notifiesStateChanges() throws Exception {
        BlockingQueue<IrcConnectionState> states = new LinkedBlockingQueue<>();
        createDeferredClient(states::add);

        client.login("test user", "secret");
        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
        server.welcome();

        assertThat(states.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(IrcConnectionState.CONNECTING);
        assertThat(states.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(IrcConnectionState.REGISTERED);

        server.send(":cho.ppy.sh 464 test_user :Bad authentication token.");
        assertThat(states.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(IrcConnectionState.AUTH_FAILED);
    }

    @Test
    void loginRejectedWhileConnected() throws Exception {
        createDeferredClient(null);
        client.login("test user", "secret");
        assertThat(server.awaitLine(line -> line.startsWith("USER "), WAIT)).isNotNull();
        server.welcome();
        awaitState(IrcConnectionState.REGISTERED);

        assertThatThrownBy(() -> client.login("test user", "secret"))
                .isInstanceOf(IllegalStateException.class).hasMessage("已登入或正在連線");
    }

    @Test
    void loginAgainAfterAuthFailureOpensExactlyOneNewConnection() throws Exception {
        server.onConnect(s -> s.send(":cho.ppy.sh 464 test_user :Bad authentication token."));
        createDeferredClient(null);
        client.login("test user", "wrong");
        assertThat(server.awaitConnection(WAIT)).isTrue();
        awaitState(IrcConnectionState.AUTH_FAILED);

        server.onConnect(s -> {
        });
        client.login("test user", "right");

        assertThat(server.awaitLine("PASS right", WAIT)).isNotNull();
        assertThat(server.awaitConnection(WAIT)).isTrue();
        assertThat(server.awaitConnection(SHORT)).isFalse();
        assertThat(server.connectionCount()).isEqualTo(2);
    }

    @Test
    void loginRejectsBlankCredentials() {
        createDeferredClient(null);

        assertThatThrownBy(() -> client.login("", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("username 不得空白");
        assertThat(client.state()).isEqualTo(IrcConnectionState.IDLE);
    }

    /** 狀態在 connection thread 上改變，以有時限的輪詢等待（不用 Thread.sleep）。 */
    private void awaitState(IrcConnectionState expected) throws InterruptedException {
        BlockingQueue<IrcConnectionState> reached = new LinkedBlockingQueue<>();
        client.addConnectionListener(state -> {
            if (state == expected) {
                reached.add(state);
            }
        });
        if (client.state() != expected) {
            assertThat(reached.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(expected);
        }
    }
}
