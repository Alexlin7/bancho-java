package idv.alexlin7.tw.bancho.shell;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Arguments;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import idv.alexlin7.tw.bancho.match.MatchRoom;
import idv.alexlin7.tw.bancho.match.MatchService;
import idv.alexlin7.tw.bancho.match.MatchState;

/**
 * 房間指令。房間相關的指令預設作用在目前房間，可用 {@code --room <房號>} 指定其他房間。
 * 回傳的字串由 Spring Shell 印出；make 的結果在 IRC dispatcher thread 上經 {@link ShellOutput} 印出。
 */
@Component
public class MatchCommands {

    private static final String GROUP = "房間";
    private static final String ROOM_OPTION = "房號（預設為目前房間）";
    private static final String SENT = "已送出：";

    private final MatchService matchService;
    private final ShellSession session;
    private final ShellOutput out;
    private final RoomResolver rooms;
    private volatile CompletableFuture<?> lastMake = CompletableFuture.completedFuture(null);

    public MatchCommands(MatchService matchService, ShellSession session, ShellOutput out) {
        this.matchService = matchService;
        this.session = session;
        this.out = out;
        this.rooms = new RoomResolver(matchService, session);
    }

    @Command(name = "make", group = GROUP, description = "開房，成功後切換到該房間")
    public String make(@Argument(index = 0, description = "房名") String name) {
        return RoomResolver.guarded(() -> {
            lastMake = matchService.createMatch(name).whenComplete((room, error) -> {
                if (error == null) {
                    session.use(room.matchId());
                    out.println("房間已建立：" + room.channel() + " " + name);
                } else if (unwrap(error) instanceof TimeoutException) {
                    out.println("開房逾時：" + name);
                } else {
                    out.println("開房失敗：" + name + "（" + unwrap(error).getMessage() + "）");
                }
            });
            return "已送出開房請求：" + name;
        });
    }

    @Command(name = "attach", group = GROUP, description = "接手一間已存在的房間並切換過去")
    public String attach(@Argument(index = 0, description = "房號") long matchId) {
        MatchRoom room = matchService.attach(matchId);
        session.use(matchId);
        return "已接手：" + room.channel();
    }

    @Command(name = "use", group = GROUP, description = "切換目前房間")
    public String use(@Argument(index = 0, description = "房號") long matchId) {
        if (matchService.room(matchId).isEmpty()) {
            return "找不到房間：" + matchId;
        }
        session.use(matchId);
        return "目前房間：#mp_" + matchId;
    }

    @Command(name = "rooms", group = GROUP, description = "列出開著的房間")
    public String rooms() {
        List<MatchRoom> open = matchService.openRooms();
        if (open.isEmpty()) {
            return "目前沒有開著的房間";
        }
        return open.stream().map(MatchCommands::describe).collect(Collectors.joining(System.lineSeparator()));
    }

    @Command(name = "invite", group = GROUP, description = "邀請玩家")
    public String invite(@Argument(index = 0, description = "玩家名稱") String username,
                         @Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.invite(username);
            return SENT + "!mp invite " + username.strip();
        });
    }

    @Command(name = "map", group = GROUP, description = "換圖")
    public String map(@Argument(index = 0, description = "beatmap id") long beatmapId,
                      @Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.map(beatmapId);
            return SENT + "!mp map " + beatmapId;
        });
    }

    @Command(name = "mods", group = GROUP, description = "設定 mod（會先清掉原本的 mod）")
    public String mods(@Arguments List<String> mods,
                       @Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.mods(mods.toArray(String[]::new));
            return SENT + "!mp mods " + String.join(" ", mods);
        });
    }

    @Command(name = "set", group = GROUP, description = "設定 team mode（0–3）、score mode（0–3）、人數（1–16）")
    public String set(@Argument(index = 0, description = "team mode") int teamMode,
                      @Argument(index = 1, description = "score mode") int scoreMode,
                      @Argument(index = 2, description = "人數") int size,
                      @Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.set(teamMode, scoreMode, size);
            return SENT + "!mp set " + teamMode + " " + scoreMode + " " + size;
        });
    }

    @Command(name = "start", group = GROUP, description = "開始比賽")
    public String start(@Option(longName = "delay", description = "幾秒後開始（預設立即）") Integer delaySeconds,
                        @Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            if (delaySeconds == null) {
                r.start();
                return SENT + "!mp start";
            }
            r.start(Duration.ofSeconds(delaySeconds));
            return SENT + "!mp start " + delaySeconds;
        });
    }

    @Command(name = "timer", group = GROUP, description = "開始倒數")
    public String timer(@Option(longName = "seconds", description = "秒數（預設為 BanchoBot 的 30 秒）") Integer seconds,
                        @Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            if (seconds == null) {
                r.timer();
                return SENT + "!mp timer";
            }
            r.timer(Duration.ofSeconds(seconds));
            return SENT + "!mp timer " + seconds;
        });
    }

    @Command(name = "abort", group = GROUP, description = "中止比賽")
    public String abort(@Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.abort();
            return SENT + "!mp abort";
        });
    }

    @Command(name = "close", group = GROUP, description = "關房")
    public String close(@Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.close();
            return SENT + "!mp close";
        });
    }

    @Command(name = "settings", group = GROUP, description = "查看房間設定（BanchoBot 會回覆在房間頻道）")
    public String settings(@Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.settings();
            return SENT + "!mp settings";
        });
    }

    @Command(name = "password", group = GROUP, description = "設定房間密碼；不給密碼則移除")
    public String password(@Option(longName = "value", description = "密碼") String password,
                           @Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.password(password);
            return SENT + (password == null || password.isBlank() ? "!mp password" : "!mp password ***");
        });
    }

    @Command(name = "addref", group = GROUP, description = "加入裁判")
    public String addref(@Arguments List<String> usernames,
                         @Option(longName = "room", description = ROOM_OPTION) Long room) {
        return rooms.onRoom(room, r -> {
            r.addRef(usernames.toArray(String[]::new));
            return SENT + "!mp addref " + String.join(" ", usernames);
        });
    }

    /** 最近一次 make 的結果處理，供測試等待。 */
    CompletableFuture<?> lastMake() {
        return lastMake;
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static String describe(MatchRoom room) {
        MatchState state = room.state();
        String header = room.channel() + " " + (state.name() == null ? "(未知)" : state.name())
                + "  size " + (state.settings().size() == null ? "?" : state.settings().size())
                + "  map " + (state.beatmap() == null ? "(未設定)" : state.beatmap().id() + " " + state.beatmap().title())
                + "  mods " + (state.mods().mods().isEmpty() ? "(無)" : String.join(" ", state.mods().mods()));
        String slots = state.slots().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(slot -> System.lineSeparator() + "  [" + slot.getKey() + "] " + slot.getValue().username())
                .collect(Collectors.joining());
        return header + slots;
    }
}
