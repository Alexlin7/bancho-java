package idv.alexlin7.tw.bancho.irc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 測試用的最小 IRC server：記錄 client 送來的每一行，並可主動推送訊息或斷線。
 */
public final class FakeIrcServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<Long> connectionTimes = new LinkedBlockingQueue<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private volatile Socket current;
    private volatile BufferedWriter writer;
    private volatile boolean answerPing;
    private volatile Consumer<FakeIrcServer> onConnect = server -> {
    };

    public FakeIrcServer() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("fake-irc-accept").start(this::acceptLoop);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int connectionCount() {
        return connectionCount.get();
    }

    /**
     * client 主動送 PING 時自動回 PONG。
     */
    public void answerPing() {
        this.answerPing = true;
    }

    /**
     * 每次有新連線時執行（例如回 464 後斷線）。
     */
    public void onConnect(Consumer<FakeIrcServer> action) {
        this.onConnect = action;
    }

    public synchronized void send(String line) {
        try {
            writer.write(line + "\r\n");
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void welcome() {
        send(":cho.ppy.sh 001 test_user :Welcome to the osu!Bancho.");
    }

    public void dropConnection() {
        try {
            current.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 等待新連線，回傳是否在時限內出現。
     */
    public boolean awaitConnection(Duration timeout) throws InterruptedException {
        return connectionTimes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) != null;
    }

    /**
     * 等待並回傳下一個新連線的時間點（nanoTime），逾時回傳 null。
     */
    public Long awaitConnectionTime(Duration timeout) throws InterruptedException {
        return connectionTimes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 等待第一行符合條件的訊息（略過不符合的），逾時回傳 null。
     */
    public String awaitLine(Predicate<String> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            String line = received.poll(remaining, TimeUnit.NANOSECONDS);
            if (line != null && condition.test(line)) {
                return line;
            }
        }
        return null;
    }

    public String awaitLine(String exact, Duration timeout) throws InterruptedException {
        return awaitLine(exact::equals, timeout);
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        if (current != null) {
            current.close();
        }
    }

    private void acceptLoop() {
        try {
            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                current = socket;
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                connectionCount.incrementAndGet();
                Thread.ofPlatform().daemon().name("fake-irc-read").start(() -> readLoop(socket));
                onConnect.accept(this);
                connectionTimes.add(System.nanoTime());
            }
        } catch (IOException e) {
            // server 已關閉
        }
    }

    private void readLoop(Socket socket) {
        try (var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                received.add(line);
                if (answerPing && line.startsWith("PING ")) {
                    send(":cho.ppy.sh PONG cho.ppy.sh " + line.substring(5));
                }
            }
        } catch (IOException e) {
            // 連線已關閉
        }
    }
}
