package idv.alexlin7.tw.bancho.shell;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Arguments;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import idv.alexlin7.tw.bancho.match.MatchRoom;
import idv.alexlin7.tw.bancho.match.GameMode;
import idv.alexlin7.tw.bancho.match.MatchService;
import idv.alexlin7.tw.bancho.match.MatchState;
import idv.alexlin7.tw.bancho.match.preset.MatchPreset;
import idv.alexlin7.tw.bancho.match.preset.MatchPresetProperties;

/**
 * 房間指令。房間相關的指令預設作用在目前房間，可用 {@code --room <房號>} 指定其他房間。
 * 回傳的字串由 Spring Shell 印出；make 的結果在 IRC dispatcher thread 上經 {@link ShellOutput} 印出。
 * 每個指令都先經過 {@link InputGuard}，打錯的選項與多餘的參數會被拒絕而不是悄悄忽略。
 */
@Component
public class MatchCommands {

    private static final String GROUP = "房間";
    private static final String ROOM = "room";
    private static final String ROOM_OPTION = "房號（預設為目前房間）";
    private static final String SENT = "已送出：";
    private static final String USAGE = "錯誤：用法：";
    private static final String SET_USAGE = USAGE + "set <team mode> <score mode> <人數> [--room <房號>]";
    private static final String MAP_USAGE = USAGE + "map <beatmap id> [模式] [--room <房號>]";

    private final MatchService matchService;
    private final ShellSession session;
    private final ShellOutput out;
    private final RoomResolver rooms;
    private final MatchPresetProperties presets;
    private final SecretPrompt secretPrompt;
    private final AtomicReference<CompletableFuture<MatchRoom>> lastMake =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    public MatchCommands(MatchService matchService, ShellSession session, ShellOutput out,
                         MatchPresetProperties presets, SecretPrompt secretPrompt) {
        this.matchService = matchService;
        this.presets = presets;
        this.secretPrompt = secretPrompt;
        this.session = session;
        this.out = out;
        this.rooms = new RoomResolver(matchService, session);
    }

    @Command(name = "make", group = GROUP, completionProvider = "makeCompletion",
            description = "開房，成功後切換到該房間；房名可含空白；可用 --preset 套用設定範本")
    public String make(@Arguments List<String> words,
                       @Option(longName = "preset", description = "設定範本名稱") String preset,
                       CommandContext context) {
        String invalid = InputGuard.check(context, InputGuard.ANY, "preset");
        if (invalid != null) {
            return invalid;
        }
        MatchPreset template = preset == null ? null : presets.presets().get(preset);
        if (preset != null && template == null) {
            return "找不到範本：" + preset;
        }
        String name = InputGuard.join(words);
        return RoomResolver.guarded(() -> {
            lastMake.set(matchService.createMatch(name).whenComplete((room, error) -> {
                if (error == null) {
                    session.use(room.matchId());
                    out.println("房間已建立：" + room.channel() + " " + name);
                    applyPreset(room, preset, template);
                } else if (unwrap(error) instanceof TimeoutException) {
                    out.println("開房逾時：" + name);
                } else {
                    out.println("開房失敗：" + name + "（" + unwrap(error).getMessage() + "）");
                }
            }));
            return "已送出開房請求：" + name;
        });
    }

    @Command(name = "attach", group = GROUP, completionProvider = "roomCompletion",
            description = "接手一間已存在的房間並切換過去")
    public String attach(@Argument(index = 0, description = "房號") String matchIdText, CommandContext context) {
        String invalid = InputGuard.check(context, 1);
        if (invalid != null) {
            return invalid;
        }
        Long matchId = parseLong(matchIdText);
        if (matchId == null) {
            return USAGE + "attach <房號>";
        }
        MatchRoom room = matchService.attach(matchId);
        session.use(matchId);
        return "已接手：" + room.channel();
    }

    @Command(name = "use", group = GROUP, completionProvider = "roomCompletion", description = "切換目前房間")
    public String use(@Argument(index = 0, description = "房號") String matchIdText, CommandContext context) {
        String invalid = InputGuard.check(context, 1);
        if (invalid != null) {
            return invalid;
        }
        Long matchId = parseLong(matchIdText);
        if (matchId == null) {
            return USAGE + "use <房號>";
        }
        if (matchService.room(matchId).isEmpty()) {
            return "找不到房間：" + matchId;
        }
        session.use(matchId);
        return "目前房間：#mp_" + matchId;
    }

    @Command(name = "rooms", group = GROUP, description = "列出開著的房間")
    public String rooms(CommandContext context) {
        String invalid = InputGuard.check(context, 0);
        if (invalid != null) {
            return invalid;
        }
        List<MatchRoom> open = matchService.openRooms();
        if (open.isEmpty()) {
            return "目前沒有開著的房間";
        }
        return open.stream().map(MatchCommands::describe).collect(Collectors.joining(System.lineSeparator()));
    }

    @Command(name = "invite", group = GROUP, completionProvider = "userCompletion",
            description = "邀請玩家（名稱可含空白）；名稱以 - 開頭時見 invite --help", help = InputGuard.INVITE_ESCAPE_HELP)
    public String invite(@Arguments List<String> words,
                         @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                         CommandContext context) {
        String invalid = InputGuard.rejectRoomAfterDoubleDash(words, "invite");
        if (invalid != null) {
            return invalid;
        }
        String username = InputGuard.join(words);
        return guardedOnRoom(context, InputGuard.ANY, room, r -> {
            r.invite(username);
            return SENT + "!mp invite " + username;
        });
    }

    @Command(name = "map", group = GROUP, completionProvider = "mapCompletion",
            description = "換圖；可指定遊玩模式（osu、taiko、catch、mania 或 0–3），std 圖可轉成其他模式")
    public String map(@Argument(index = 0, description = "beatmap id") String beatmapIdText,
                      @Argument(index = 1, description = "遊玩模式（預設為圖本身的模式）") String mode,
                      @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                      CommandContext context) {
        Long beatmapId = parseLong(beatmapIdText);
        if (beatmapId == null) {
            return guardedUsage(context, 2, MAP_USAGE);
        }
        return guardedOnRoom(context, 2, room, r -> {
            GameMode parsed = mode == null || mode.isEmpty() ? null : GameMode.parse(mode)
                    .orElseThrow(() -> new IllegalArgumentException("不認得的遊玩模式：" + mode
                            + "（可用 osu、taiko、catch、mania 或 0–3）"));
            r.map(beatmapId, parsed);
            return SENT + "!mp map " + beatmapId + (parsed == null ? "" : " " + parsed.id());
        });
    }

    @Command(name = "mods", group = GROUP, completionProvider = "modsCompletion",
            description = "設定 mod（會先清掉原本的 mod）")
    public String mods(@Arguments List<String> mods,
                       @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                       CommandContext context) {
        return guardedOnRoom(context, InputGuard.ANY, room, r -> {
            r.mods(mods.toArray(String[]::new));
            return SENT + "!mp mods " + String.join(" ", mods);
        });
    }

    @Command(name = "set", group = GROUP, completionProvider = "roomOptionCompletion",
            description = "設定 team mode（0–3）、score mode（0–3）、人數（1–16）")
    public String set(@Argument(index = 0, description = "team mode") String teamModeText,
                      @Argument(index = 1, description = "score mode") String scoreModeText,
                      @Argument(index = 2, description = "人數") String sizeText,
                      @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                      CommandContext context) {
        Integer teamMode = parseInt(teamModeText);
        Integer scoreMode = parseInt(scoreModeText);
        Integer size = parseInt(sizeText);
        if (teamMode == null || scoreMode == null || size == null) {
            return guardedUsage(context, 3, SET_USAGE);
        }
        return guardedOnRoom(context, 3, room, r -> {
            r.set(teamMode, scoreMode, size);
            return SENT + "!mp set " + teamMode + " " + scoreMode + " " + size;
        });
    }

    @Command(name = "start", group = GROUP, completionProvider = "roomOptionCompletion",
            description = "開始比賽；可給秒數延遲開始")
    public String start(@Argument(index = 0, description = "幾秒後開始（預設立即）") Integer delaySeconds,
                        @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                        CommandContext context) {
        return guardedOnRoom(context, 1, room, r -> {
            if (delaySeconds == null) {
                r.start();
                return SENT + "!mp start";
            }
            r.start(Duration.ofSeconds(delaySeconds));
            return SENT + "!mp start " + delaySeconds;
        });
    }

    @Command(name = "timer", group = GROUP, completionProvider = "roomOptionCompletion", description = "開始倒數")
    public String timer(@Argument(index = 0, description = "秒數（預設為 BanchoBot 的 30 秒）") Integer seconds,
                        @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                        CommandContext context) {
        return guardedOnRoom(context, 1, room, r -> {
            if (seconds == null) {
                r.timer();
                return SENT + "!mp timer";
            }
            r.timer(Duration.ofSeconds(seconds));
            return SENT + "!mp timer " + seconds;
        });
    }

    @Command(name = "abort", group = GROUP, completionProvider = "roomOptionCompletion", description = "中止比賽")
    public String abort(@Option(longName = ROOM, description = ROOM_OPTION) Long room, CommandContext context) {
        return guardedOnRoom(context, 0, room, r -> {
            r.abort();
            return SENT + "!mp abort";
        });
    }

    @Command(name = "close", group = GROUP, completionProvider = "roomOptionCompletion", description = "關房")
    public String close(@Option(longName = ROOM, description = ROOM_OPTION) Long room, CommandContext context) {
        return guardedOnRoom(context, 0, room, r -> {
            r.close();
            return SENT + "!mp close";
        });
    }

    @Command(name = "settings", group = GROUP, completionProvider = "roomOptionCompletion",
            description = "查看房間設定（BanchoBot 會回覆在房間頻道）")
    public String settings(@Option(longName = ROOM, description = ROOM_OPTION) Long room, CommandContext context) {
        return guardedOnRoom(context, 0, room, r -> {
            r.settings();
            return SENT + "!mp settings";
        });
    }

    @Command(name = "password", group = GROUP, completionProvider = "roomOptionCompletion",
            description = "設定房間密碼（在提示中輸入，以 * 顯示）；--clear 移除密碼")
    public String password(@Arguments List<String> words,
                           @Option(longName = "clear", description = "移除密碼") boolean clear,
                           @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                           CommandContext context) {
        String invalid = InputGuard.check(context, InputGuard.ANY, ROOM, "clear");
        if (invalid != null) {
            return invalid;
        }
        if (!words.isEmpty()) {
            return "錯誤：密碼請在提示中輸入，不要寫在指令裡（會留在指令歷史）";
        }
        // 先確定房間再提示輸入，避免打完密碼才發現房間不存在
        return rooms.onRoom(room, r -> {
            if (clear) {
                r.password(null);
                return SENT + "!mp password（移除密碼）";
            }
            Optional<String> password = secretPrompt.read("新密碼：");
            if (password.isEmpty()) {
                return "已取消，密碼沒有變更";
            }
            r.password(password.get());
            return SENT + "!mp password ***";
        });
    }

    @Command(name = "addref", group = GROUP, completionProvider = "userCompletion",
            description = "加入裁判；名稱以 - 開頭時見 addref --help", help = InputGuard.ESCAPE_HELP)
    public String addref(@Arguments List<String> usernames,
                         @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                         CommandContext context) {
        String invalid = InputGuard.rejectRoomAfterDoubleDash(usernames, "addref");
        if (invalid != null) {
            return invalid;
        }
        return guardedOnRoom(context, InputGuard.ANY, room, r -> {
            r.addRef(usernames.toArray(String[]::new));
            return SENT + "!mp addref " + String.join(" ", usernames);
        });
    }

    /*
     * 數字參數收 String 自行解析：交給 Spring Shell 轉換時，少給或非數字只會得到看不懂的英文例外。
     */
    private static Integer parseInt(String text) {
        try {
            return text == null ? null : Integer.valueOf(text.strip());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static Long parseLong(String text) {
        try {
            return text == null ? null : Long.valueOf(text.strip());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** 必填參數缺少時：打錯的選項與多餘的參數仍優先回報，否則回用法。 */
    private static String guardedUsage(CommandContext context, int maxArguments, String usage) {
        String invalid = InputGuard.check(context, maxArguments, ROOM);
        return invalid != null ? invalid : usage;
    }

    private String guardedOnRoom(CommandContext context, int maxArguments, Long room,
                                 Function<MatchRoom, String> action) {
        String invalid = InputGuard.check(context, maxArguments, ROOM);
        return invalid != null ? invalid : rooms.onRoom(room, action);
    }

    @Command(name = "mp", group = GROUP, completionProvider = "mpCompletion",
            description = "轉送任意 !mp 指令到房間（例如 mp host 玩家）；以 - 開頭的字請用雙引號包起來")
    public String mp(@Arguments List<String> words,
                     @Option(longName = ROOM, description = ROOM_OPTION) Long room,
                     CommandContext context) {
        String invalid = InputGuard.check(context, InputGuard.ANY, ROOM);
        if (invalid != null) {
            return invalid;
        }
        String arguments = InputGuard.join(words);
        if (arguments.isEmpty()) {
            return "錯誤：請輸入 !mp 之後的指令，例如 mp host Alexlin86";
        }
        String subcommand = arguments.split("\\s+", 2)[0];
        if ("make".equalsIgnoreCase(subcommand) || "makeprivate".equalsIgnoreCase(subcommand)) {
            return "錯誤：開房請用 make 指令";
        }
        if ("password".equalsIgnoreCase(subcommand)) {
            return "錯誤：設定密碼請用 password 指令（移除請用 password --clear）";
        }
        return rooms.onRoom(room, r -> {
            r.raw(arguments);
            return SENT + "!mp " + arguments;
        });
    }

    private void applyPreset(MatchRoom room, String name, MatchPreset template) {
        if (template == null) {
            return;
        }
        out.println(RoomResolver.guarded(() -> {
            room.apply(template);
            return "已套用範本：" + name;
        }));
    }

    /** 最近一次 make 的結果處理，供測試等待。 */
    CompletableFuture<MatchRoom> lastMake() {
        return lastMake.get();
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static String describe(MatchRoom room) {
        MatchState state = room.state();
        String header = room.channel() + " " + (state.name() == null ? "(未知)" : state.name())
                + "  size " + (state.settings().size() == null ? "?" : state.settings().size())
                + "  map " + (state.beatmap() == null ? "(未設定)" : state.beatmap().id() + " " + state.beatmap().title())
                + "  mode " + (state.gameMode() == null ? "?" : state.gameMode())
                + "  mods " + (state.mods().mods().isEmpty() ? "(無)" : String.join(" ", state.mods().mods()));
        String slots = state.slots().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(slot -> System.lineSeparator() + "  [" + slot.getKey() + "] " + slot.getValue().username())
                .collect(Collectors.joining());
        return header + slots;
    }
}
