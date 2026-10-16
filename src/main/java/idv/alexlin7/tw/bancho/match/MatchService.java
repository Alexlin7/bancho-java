package idv.alexlin7.tw.bancho.match;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import idv.alexlin7.tw.bancho.irc.IrcSender;
import idv.alexlin7.tw.bancho.match.event.MatchEvent;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchClosed;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchCreated;

import lombok.extern.slf4j.Slf4j;

/**
 * 開房與追蹤房間。{@link #createMatch} 由前端 thread 呼叫；{@link #onMatchEvent} 在 IRC dispatcher thread 上依序執行。
 */
@Slf4j
public class MatchService {

    private final IrcSender sender;
    private final Duration createTimeout;
    private final Map<Long, MatchRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<MatchRoom>> pending = new ConcurrentHashMap<>();

    public MatchService(IrcSender sender, Duration createTimeout) {
        this.sender = sender;
        this.createTimeout = createTimeout;
    }

    /**
     * 對 BanchoBot 送出 {@code !mp make}，收到同名的開房回覆後完成。逾時以 {@link TimeoutException} 失敗。
     * BanchoBot 的回覆只能靠房名對應回請求，所以同名請求在等待中時會直接拒絕。
     */
    public CompletableFuture<MatchRoom> createMatch(String name) {
        String roomName = requireRoomName(name);
        CompletableFuture<MatchRoom> future = new CompletableFuture<>();
        if (pending.putIfAbsent(roomName, future) != null) {
            throw new IllegalStateException("已有同名的開房請求在等待中：" + roomName);
        }
        // 先移出 pending 再讓 future 失敗，呼叫端看到逾時時就能立刻用同名重試
        CompletableFuture.delayedExecutor(createTimeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            if (pending.remove(roomName, future)) {
                future.completeExceptionally(new TimeoutException("開房逾時：" + roomName));
            }
        });
        sender.sendMessage(BanchoBotParser.BANCHO_BOT, "!mp make " + roomName);
        return future;
    }

    /** 接手一間已存在的房間（例如程式重啟後）：加入頻道並開始追蹤。 */
    public MatchRoom attach(long matchId) {
        MatchRoom room = rooms.computeIfAbsent(matchId, id -> new MatchRoom(id, null, sender));
        sender.join(room.channel());
        return room;
    }

    /** 已關閉的房間也查得到。 */
    public Optional<MatchRoom> room(long matchId) {
        return Optional.ofNullable(rooms.get(matchId));
    }

    public List<MatchRoom> openRooms() {
        return rooms.values().stream()
                .filter(room -> !room.state().closed())
                .sorted(Comparator.comparingLong(MatchRoom::matchId))
                .toList();
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onMatchEvent(MatchEvent event) {
        if (event instanceof MatchCreated created) {
            onCreated(created);
            return;
        }
        // 收到事件代表已經在頻道內，未登記的房間只登記不 join
        MatchRoom room = rooms.computeIfAbsent(event.matchId(), id -> new MatchRoom(id, null, sender));
        room.apply(event);
        if (event instanceof MatchClosed) {
            sender.part(room.channel());
        }
    }

    private void onCreated(MatchCreated created) {
        MatchRoom room = rooms.computeIfAbsent(created.matchId(), id -> new MatchRoom(id, created.name(), sender));
        room.apply(created);
        sender.join(room.channel());
        CompletableFuture<MatchRoom> future = pending.remove(created.name());
        if (future != null) {
            future.complete(room);
        } else {
            log.atInfo().addKeyValue("match.id", created.matchId())
                    .log("[match] 追蹤非 createMatch 建立的房間：{} {}", room.channel(), created.name());
        }
    }

    private static String requireRoomName(String name) {
        if (name == null || name.isBlank() || name.chars().anyMatch(c -> c == '\r' || c == '\n')) {
            throw new IllegalArgumentException("房名不可為空白或含換行：" + name);
        }
        return name.strip();
    }
}
