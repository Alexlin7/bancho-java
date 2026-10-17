package cc.alexlin7.bancho.irc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import lombok.extern.slf4j.Slf4j;

/**
 * 不依賴 Spring 的 osu! Bancho IRC client，由三條 virtual thread 組成：
 * <ul>
 *   <li>connection：連線、登入、讀取；處理 PING/PONG、001、464，斷線時以指數 backoff 重連</li>
 *   <li>dispatcher：依到達順序把 PRIVMSG 逐一通知每個 {@link IrcMessageListener}，listener 的例外只記 log 不中斷</li>
 *   <li>sender：從出站佇列取訊息，等到登入完成後依 {@code sendInterval} 間隔送出</li>
 * </ul>
 */
@Slf4j
public class OsuIrcClient implements IrcSender, AutoCloseable {

    private static final String CRLF = "\r\n";
    private static final String KEEPALIVE_TOKEN = "bancho-keepalive";
    private static final Duration REGISTRATION_POLL = Duration.ofMillis(50);
    private static final Runnable NO_CALLBACK = () -> {
    };

    private final OsuIrcSettings settings;
    private final OsuIrcSettings.Timing timing;
    /** dispatcher 迭代時取快照，其他 thread 可以同時 {@link #addListener}。 */
    private final List<IrcMessageListener> listeners;

    private final BlockingQueue<IrcMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingDeque<Outbound> outbound = new LinkedBlockingDeque<>();
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Connection> connection = new AtomicReference<>();
    private final List<Thread> workers = new CopyOnWriteArrayList<>();

    private final List<IrcConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final List<IrcTrafficListener> trafficListeners = new CopyOnWriteArrayList<>();
    /** 套用在出站行的 log 與 {@link IrcTrafficListener#sent}，不影響實際送出的內容。 */
    private volatile UnaryOperator<String> outboundRedactor = UnaryOperator.identity();
    private final AtomicReference<IrcConnectionState> state = new AtomicReference<>(IrcConnectionState.IDLE);

    private final Object lifecycleLock = new Object();
    private boolean started;
    private volatile boolean closed;
    /** 來自 settings 或 {@link #login}；connection thread 每次登入時讀取。 */
    private volatile Credentials credentials;

    /**
     * 只由 connection thread 讀寫。
     */
    private Duration reconnectDelay;

    public OsuIrcClient(OsuIrcSettings settings, List<IrcMessageListener> listeners) {
        this.settings = settings;
        this.timing = settings.timing();
        // CopyOnWriteArrayList 允許 null，要自己檢查：null listener 會讓 dispatcher 在第一則訊息就結束
        listeners.forEach(listener -> Objects.requireNonNull(listener, "listener"));
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        this.reconnectDelay = timing.reconnectMinDelay();
        this.credentials = settings.hasCredentials() ? new Credentials(settings.username(), settings.password()) : null;
    }

    private boolean trySend(Connection current, String line) {
        try {
            current.writeLine(line);
            log.debug("[osu! IRC] -> {}", outboundRedactor.apply(line));
            return true;
        } catch (IOException e) {
            log.warn("[osu! IRC] 發送失敗，重新連線後重送", e);
            return false;
        }
    }

    private static String normalizeTarget(String target) {
        if (target == null || target.isBlank() || target.chars().anyMatch(c -> c == '\r' || c == '\n' || c == '\0')) {
            throw new IllegalArgumentException("不合法的訊息對象：" + target);
        }
        return target.strip().replace(' ', '_');
    }

    private static String normalizeChannel(String channel) {
        String name = normalizeTarget(channel);
        if (!name.startsWith("#")) {
            throw new IllegalArgumentException("頻道名稱必須以 # 開頭：" + channel);
        }
        return name;
    }

    private static int toMillis(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }

    /**
     * 加入 listener，排在建構子傳入的 listener 之後，從下一則訊息開始通知。
     * 需要拿這個 client 當 {@link IrcSender} 回話的 listener，只能在 client 建好之後用這個方法加入。
     */
    public void addListener(IrcMessageListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 觀察原始進出站行，從下一行開始通知。
     */
    public void addTrafficListener(IrcTrafficListener listener) {
        trafficListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 出站行寫進 log 或交給 {@link IrcTrafficListener#sent} 前先經過這個函式（例如遮蔽密碼）；實際送出的內容不變。
     * client 不認識上層的指令格式，遮蔽規則由使用端提供。
     */
    public void setOutboundRedactor(UnaryOperator<String> redactor) {
        this.outboundRedactor = Objects.requireNonNull(redactor, "redactor");
    }

    /**
     * 連線狀態改變時通知，從下一次改變開始。
     */
    public void addConnectionListener(IrcConnectionListener listener) {
        connectionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public IrcConnectionState state() {
        return state.get();
    }

    /** settings 帶有帳密，或已經呼叫過 {@link #login}。 */
    public boolean hasCredentials() {
        return credentials != null;
    }

    /**
     * @throws IllegalStateException 沒有帳密（延後登入時請改用 {@link #login}）
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (started || closed) {
                return;
            }
            if (credentials == null) {
                throw new IllegalStateException("尚未提供帳密，請先呼叫 login");
            }
            startWorkers();
        }
    }

    /**
     * 以這組帳密登入：還沒開始連線時等同 {@link #start()}，認證失敗後則以新帳密重新連線。
     *
     * @throws IllegalArgumentException 帳號或密碼空白
     * @throws IllegalStateException    已登入、正在連線，或已關閉
     */
    public void login(String username, String password) {
        Credentials given = new Credentials(OsuIrcSettings.requireNotBlank(username, "username"),
                OsuIrcSettings.requireNotBlank(password, "password"));
        synchronized (lifecycleLock) {
            IrcConnectionState current = state.get();
            if (closed || current != IrcConnectionState.IDLE && current != IrcConnectionState.AUTH_FAILED) {
                throw new IllegalStateException(closed ? "連線已關閉" : "已登入或正在連線");
            }
            credentials = given;
            if (!started) {
                startWorkers();
            } else {
                // 認證失敗後 connection thread 已結束，dispatcher 與 sender 仍在執行，只要重開 connection thread
                reconnectDelay = timing.reconnectMinDelay();
                setState(IrcConnectionState.CONNECTING);
                workers.add(Thread.ofVirtual().name("osu-irc-connection").start(this::runConnectionLoop));
            }
        }
    }

    /** 呼叫端必須持有 lifecycleLock。先設成 CONNECTING，login 才不會在 thread 啟動前再開一條連線。 */
    private void startWorkers() {
        started = true;
        setState(IrcConnectionState.CONNECTING);
        workers.add(Thread.ofVirtual().name("osu-irc-connection").start(this::runConnectionLoop));
        workers.add(Thread.ofVirtual().name("osu-irc-dispatcher").start(this::runDispatchLoop));
        workers.add(Thread.ofVirtual().name("osu-irc-sender").start(this::runSendLoop));
    }

    private void setState(IrcConnectionState next) {
        IrcConnectionState previous = state.getAndSet(next);
        if (previous == next) {
            return;
        }
        for (IrcConnectionListener listener : connectionListeners) {
            try {
                listener.onStateChanged(next);
            } catch (RuntimeException e) {
                log.error("[osu! IRC] {} 處理連線狀態 {} 時發生例外", listener.getClass().getSimpleName(), next, e);
            }
        }
    }

    // ---------------------------------------------------------------- connection thread

    @Override
    public void sendMessage(String target, String message) {
        sendMessage(target, message, NO_CALLBACK);
    }

    /**
     * {@code onSent} 在 sender thread 上、該行實際寫出後執行；斷線重送時只在最後成功的那次執行，
     * {@link #close()} 時還沒送出的不會執行。拋出的例外只記 log，該行仍視為已送出、不會重送。
     */
    @Override
    public void sendMessage(String target, String message, Runnable onSent) {
        Objects.requireNonNull(onSent, "onSent");
        String prefix = "PRIVMSG " + normalizeTarget(target) + " :";
        String text = IrcText.stripLineBreaks(message);
        int available = IrcText.MAX_LINE_BYTES - IrcText.utf8Length(prefix);
        String truncated = IrcText.truncateUtf8(text, available);
        if (truncated.length() < text.length()) {
            log.warn("[osu! IRC] 訊息超過 {} bytes，已截斷：{}", IrcText.MAX_LINE_BYTES,
                    outboundRedactor.apply(prefix + truncated));
        }
        outbound.add(new Outbound(prefix + truncated, onSent));
    }

    @Override
    public void join(String channel) {
        String name = normalizeChannel(channel);
        // 未登入時不必排入：登入完成（001）後會統一加入 channels 內的所有頻道
        if (channels.add(name) && isRegistered()) {
            outbound.add(new Outbound("JOIN " + name, NO_CALLBACK));
        }
    }

    @Override
    public void part(String channel) {
        String name = normalizeChannel(channel);
        if (channels.remove(name) && isRegistered()) {
            outbound.add(new Outbound("PART " + name, NO_CALLBACK));
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        setState(IrcConnectionState.CLOSED);
        Connection current = connection.get();
        if (current != null) {
            try {
                current.writeLine("QUIT :Shutdown");
            } catch (IOException e) {
                log.debug("[osu! IRC] 送出 QUIT 失敗", e);
            }
            current.closeQuietly();
        }
        workers.forEach(Thread::interrupt);
        log.info("[osu! IRC] 連線已關閉");
    }

    private void runConnectionLoop() {
        while (!closed) {
            setState(IrcConnectionState.CONNECTING);
            try {
                connectAndRead();
            } catch (AuthenticationFailedException e) {
                log.error("[osu! IRC] 認證失敗，停止重連，請檢查帳號與 IRC 密碼", e);
                if (!closed) {
                    setState(IrcConnectionState.AUTH_FAILED);
                }
                return;
            } catch (IOException e) {
                if (!closed) {
                    log.warn("[osu! IRC] 連線中斷", e);
                }
            }
            if (closed || !waitBeforeReconnect()) {
                return;
            }
        }
    }

    private boolean waitBeforeReconnect() {
        Duration delay = reconnectDelay;
        Duration doubled = delay.multipliedBy(2);
        reconnectDelay = doubled.compareTo(timing.reconnectMaxDelay()) > 0 ? timing.reconnectMaxDelay() : doubled;
        log.info("[osu! IRC] {} 秒後重新連線", delay.toMillis() / 1000.0);
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void connectAndRead() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(settings.host(), settings.port()),
                    toMillis(timing.connectTimeout()));
            socket.setSoTimeout(toMillis(timing.readTimeout()));
            Connection current = new Connection(socket);
            connection.set(current);
            try {
                // close() 可能在 connect 期間被呼叫：此時它看不到這條新連線，由這裡負責收尾
                if (closed) {
                    return;
                }
                register(current);
                readLoop(current);
            } finally {
                connection.compareAndSet(current, null);
            }
        }
    }

    // ---------------------------------------------------------------- dispatcher thread

    private void register(Connection current) throws IOException {
        Credentials login = credentials;
        String nick = login.username().replace(' ', '_');
        current.writeLine("PASS " + login.password());
        current.writeLine("NICK " + nick);
        current.writeLine("USER " + nick + " 0 * :" + login.username());
    }

    /**
     * 讀取直到斷線。超過 readTimeout 沒收到資料就主動送 PING，再逾時一次即判定為半開連線。
     * 不依賴 Bancho 自己送 PING 的間隔（官方文件未載明）。
     */
    private void readLoop(Connection current) throws IOException {
        boolean awaitingPong = false;
        while (!closed) {
            String line;
            try {
                line = current.reader.readLine();
            } catch (SocketTimeoutException e) {
                if (awaitingPong) {
                    throw new IOException("超過 " + timing.readTimeout().multipliedBy(2) + " 沒有收到伺服器任何資料", e);
                }
                current.writeLine("PING :" + KEEPALIVE_TOKEN);
                awaitingPong = true;
                continue;
            }
            if (line == null) {
                throw new EOFException("伺服器關閉連線");
            }
            awaitingPong = false;
            notifyReceived(line);
            Optional<IrcMessage> message = IrcMessage.parse(line);
            if (message.isPresent()) {
                handleServerMessage(current, message.get());
            }
        }
    }

    // ---------------------------------------------------------------- sender thread

    private void handleServerMessage(Connection current, IrcMessage message) throws IOException {
        switch (message.command()) {
            case "PING" -> current.writeLine("PONG :" + message.trailing());
            case "PONG" -> log.trace("[osu! IRC] <- PONG");
            case "001" -> onRegistered(current);
            case "464" -> throw new AuthenticationFailedException(message.trailing());
            case "PRIVMSG" -> inbound.add(message);
            default -> {
                if (message.trailing().toLowerCase(Locale.ROOT).contains("bad authentication")) {
                    throw new AuthenticationFailedException(message.trailing());
                }
                log.debug("[osu! IRC] <- {}", message);
            }
        }
    }

    private void onRegistered(Connection current) {
        reconnectDelay = timing.reconnectMinDelay();
        // 重新加入頻道要排在斷線期間累積的訊息之前
        channels.forEach(channel -> outbound.addFirst(new Outbound("JOIN " + channel, NO_CALLBACK)));
        current.registered = true;
        log.info("[osu! IRC] 登入成功，加入頻道：{}", channels);
        setState(IrcConnectionState.REGISTERED);
    }

    private void runDispatchLoop() {
        try {
            while (!closed) {
                dispatch(inbound.take());
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void dispatch(IrcMessage message) {
        for (IrcMessageListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (RuntimeException e) {
                log.error("[osu! IRC] {} 處理訊息時發生例外：{}", listener.getClass().getSimpleName(), message, e);
            }
        }
    }

    private void runSendLoop() {
        try {
            while (!closed) {
                Outbound next = outbound.take();
                if (trySend(awaitRegisteredConnection(), next.line())) {
                    notifySent(next);
                    Thread.sleep(timing.sendInterval());
                } else {
                    outbound.addFirst(next);
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void notifySent(Outbound sent) {
        String redacted = outboundRedactor.apply(sent.line());
        try {
            sent.onSent().run();
        } catch (RuntimeException e) {
            log.error("[osu! IRC] 送出後的通知發生例外：{}", redacted, e);
        }
        for (IrcTrafficListener listener : trafficListeners) {
            try {
                listener.sent(redacted);
            } catch (RuntimeException e) {
                log.error("[osu! IRC] {} 處理出站行時發生例外", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyReceived(String line) {
        for (IrcTrafficListener listener : trafficListeners) {
            try {
                listener.received(line);
            } catch (RuntimeException e) {
                log.error("[osu! IRC] {} 處理收到的行時發生例外", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private Connection awaitRegisteredConnection() throws InterruptedException {
        while (true) {
            Connection current = connection.get();
            if (current != null && current.isUsable()) {
                return current;
            }
            Thread.sleep(REGISTRATION_POLL);
        }
    }

    private boolean isRegistered() {
        Connection current = connection.get();
        return current != null && current.isUsable();
    }

    /** 出站佇列的一行與送出後的通知。 */
    private record Outbound(String line, Runnable onSent) {
    }

    /** 不輸出密碼，避免寫進 log。 */
    private record Credentials(String username, String password) {

        @Override
        public String toString() {
            return "Credentials[username=" + username + ", password=***]";
        }
    }

    private static final class Connection {

        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private volatile boolean registered;
        private volatile boolean broken;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized void writeLine(String line) throws IOException {
            try {
                writer.write(line);
                writer.write(CRLF);
                writer.flush();
            } catch (IOException e) {
                broken = true;
                throw e;
            }
        }

        boolean isUsable() {
            return registered && !broken;
        }

        void closeQuietly() {
            try {
                socket.close();
            } catch (IOException e) {
                log.debug("[osu! IRC] 關閉 socket 失敗", e);
            }
        }
    }
}
