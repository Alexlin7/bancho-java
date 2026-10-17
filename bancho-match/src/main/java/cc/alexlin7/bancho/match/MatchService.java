package cc.alexlin7.bancho.match;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import cc.alexlin7.bancho.irc.IrcSender;
import cc.alexlin7.bancho.match.PendingMatch.Status;
import cc.alexlin7.bancho.match.event.MatchEvent;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchClosed;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchCreated;
import cc.alexlin7.bancho.match.event.MatchEventListener;

import lombok.extern.slf4j.Slf4j;

/**
 * 開房與追蹤房間。{@link #createMatch} 由前端 thread 呼叫；{@link #onMatchEvent} 在 IRC dispatcher thread 上依序執行。
 * 要排在 {@link BanchoBotListener} 的第一個 listener，其他 listener 收到事件時查到的才是更新後的房間。
 * <p>
 * 每個帳號最多 {@value #MAX_ROOMS} 間錦標賽房：未關閉的房間（含 {@link #attach} 與被動登記的）、
 * 建房中與結果未知的請求共用名額。{@code !mp make} 實際送出後逾時，請求轉為結果未知並繼續佔用名額，
 * 晚到的同名回覆照常接上；確認遊戲內沒有這間房後可用 {@link #drop} 釋放。
 */
@Slf4j
public class MatchService implements MatchEventListener {

    public static final int MAX_ROOMS = 4;

    private final IrcSender sender;
    private final Duration createTimeout;
    private final Map<Long, MatchRoom> rooms = new ConcurrentHashMap<>();

    /** 保護 {@link #requests}、{@link #droppedNames}，以及名額檢查與房間登記的先後。 */
    private final Object lock = new Object();
    private final Map<String, Request> requests = new LinkedHashMap<>();
    private final Set<String> droppedNames = new HashSet<>();

    public MatchService(IrcSender sender, Duration createTimeout) {
        this.sender = sender;
        this.createTimeout = createTimeout;
    }

    /**
     * 對 BanchoBot 送出 {@code !mp make}。BanchoBot 的回覆只能靠房名對應回請求，所以同名請求還在等待或結果未知時拒絕；
     * drop 過的房名在本次執行中也不能再用，否則舊的回覆晚到時會配給新請求。
     *
     * @throws IllegalArgumentException 房名空白或含換行
     * @throws IllegalStateException    房名不能使用，或名額已滿
     */
    public MatchCreation createMatch(String name) {
        String roomName = requireRoomName(name);
        Request request = new Request(roomName);
        synchronized (lock) {
            if (droppedNames.contains(roomName)) {
                throw new IllegalStateException("此房名已 drop，本次執行不能再用，請換一個房名：" + roomName);
            }
            Request existing = requests.get(roomName);
            if (existing != null) {
                throw new IllegalStateException(existing.status == Status.CREATING
                        ? "已有同名的開房請求在等待中：" + roomName
                        : "同名的開房請求結果未知：" + roomName + "；確認遊戲內沒有這間房後用 drop 釋放");
            }
            if (usedSlotsLocked() >= MAX_ROOMS) {
                throw new IllegalStateException("名額已滿（" + MAX_ROOMS + "/" + MAX_ROOMS + "）");
            }
            requests.put(roomName, request);
        }
        // 逾時從實際送出開始算：還在出站佇列裡（例如斷線中）的請求不會變成結果未知
        sender.sendMessage(BanchoBotParser.BANCHO_BOT, "!mp make " + roomName, () -> scheduleTimeout(request));
        return request.creation;
    }

    /**
     * 釋放一筆結果未知的請求。它的 {@code !mp make} 已經送出，回覆仍可能晚到，所以這個房名在本次執行中不能再用；
     * 晚到的回覆會被當成一般房間登記。
     *
     * @return 找不到結果未知的同名請求（包括還在建房中的）時為 false
     */
    public boolean drop(String name) {
        String roomName = name == null ? "" : name.strip();
        Request request;
        synchronized (lock) {
            request = requests.get(roomName);
            if (request == null || request.status != Status.UNKNOWN) {
                return false;
            }
            requests.remove(roomName);
            droppedNames.add(roomName);
        }
        request.creation.room().cancel(false);
        return true;
    }

    /** 依建立順序。 */
    public List<PendingMatch> pendingMatches() {
        synchronized (lock) {
            return requests.values().stream().map(request -> new PendingMatch(request.name, request.status)).toList();
        }
    }

    /** 未關閉的房間數加上建房中與結果未知的請求數。 */
    public int usedSlots() {
        synchronized (lock) {
            return usedSlotsLocked();
        }
    }

    /** 接手一間已存在的房間（例如程式重啟後）：加入頻道並開始追蹤。已存在的房間本來就佔用帳號的名額，不受上限阻擋。 */
    public MatchRoom attach(long matchId) {
        MatchRoom room = register(matchId, null);
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

    @Override
    public void onMatchEvent(MatchEvent event) {
        if (event instanceof MatchCreated created) {
            onCreated(created);
            return;
        }
        // 收到事件代表已經在頻道內，未登記的房間只登記不 join
        MatchRoom room = register(event.matchId(), null);
        room.apply(event);
        if (event instanceof MatchClosed) {
            sender.part(room.channel());
        }
    }

    private void onCreated(MatchCreated created) {
        MatchRoom room;
        Request request;
        boolean late;
        synchronized (lock) {
            // 房間登記與請求移除在同一個鎖內，名額不會重複計算
            room = rooms.computeIfAbsent(created.matchId(), id -> new MatchRoom(id, created.name(), sender));
            request = requests.remove(created.name());
            late = request != null && request.status == Status.UNKNOWN;
        }
        room.apply(created);
        sender.join(room.channel());
        if (request != null) {
            if (late) {
                // 計時器可能剛把狀態改成結果未知、還沒完成 unknown；先完成它，前端看到成功時才會一致地當成晚到的回覆
                request.creation.unknown().complete(null);
            }
            request.creation.room().complete(room);
        } else {
            log.atInfo().addKeyValue("match.id", created.matchId())
                    .log("[match] 追蹤非 createMatch 建立的房間：{} {}", room.channel(), created.name());
        }
    }

    private MatchRoom register(long matchId, String name) {
        synchronized (lock) {
            return rooms.computeIfAbsent(matchId, id -> new MatchRoom(id, name, sender));
        }
    }

    /** 送出後才開始計時；計時器只作用在同一個仍在建房中的請求，已完成或被同名新請求取代的一律略過。 */
    private void scheduleTimeout(Request request) {
        CompletableFuture.delayedExecutor(createTimeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            synchronized (lock) {
                if (requests.get(request.name) != request || request.status != Status.CREATING) {
                    return;
                }
                request.status = Status.UNKNOWN;
            }
            log.atInfo().addKeyValue("match.name", request.name).log("[match] 開房結果未知：{}", request.name);
            request.creation.unknown().complete(null);
        });
    }

    private int usedSlotsLocked() {
        long open = rooms.values().stream().filter(room -> !room.state().closed()).count();
        return Math.toIntExact(open) + requests.size();
    }

    private static String requireRoomName(String name) {
        if (name == null || name.isBlank() || name.chars().anyMatch(c -> c == '\r' || c == '\n')) {
            throw new IllegalArgumentException("房名不可為空白或含換行：" + name);
        }
        return name.strip();
    }

    private static final class Request {

        private final String name;
        private final MatchCreation creation;
        /** 由 {@link MatchService#lock} 保護。 */
        private Status status = Status.CREATING;

        Request(String name) {
            this.name = name;
            this.creation = new MatchCreation(name, new CompletableFuture<>(), new CompletableFuture<>());
        }
    }
}
