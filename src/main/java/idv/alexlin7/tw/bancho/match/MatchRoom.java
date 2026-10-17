package idv.alexlin7.tw.bancho.match;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import idv.alexlin7.tw.bancho.irc.IrcSender;
import idv.alexlin7.tw.bancho.match.event.MatchEvent;
import idv.alexlin7.tw.bancho.match.preset.MatchPreset;

/**
 * 單一多人房：把 {@code !mp} 指令送到 {@code #mp_<id>}，並保存由事件更新的 {@link MatchState}。
 * 指令可由任何 thread 呼叫；事件只在 IRC dispatcher thread 上套用。
 */
public final class MatchRoom {

    private static final String MP = "!mp ";
    /** 超過兩個字母、但 BanchoBot 確定接受的 mod 名稱（小寫）。 */
    private static final Set<String> LONG_MOD_NAMES = Set.of("freemod");

    private final long matchId;
    private final IrcSender sender;
    private final AtomicReference<MatchState> state;

    MatchRoom(long matchId, String name, IrcSender sender) {
        this.matchId = matchId;
        this.sender = sender;
        this.state = new AtomicReference<>(MatchState.initial(name));
    }

    public long matchId() {
        return matchId;
    }

    public String channel() {
        return "#mp_" + matchId;
    }

    public MatchState state() {
        return state.get();
    }

    void apply(MatchEvent event) {
        state.updateAndGet(current -> current.apply(event));
    }

    public void invite(String username) {
        command(inviteCommand(username));
    }

    /**
     * @param teamMode  0–3
     * @param scoreMode 0–3
     * @param size      1–16
     */
    public void set(int teamMode, int scoreMode, int size) {
        command(setCommand(teamMode, scoreMode, size));
    }

    public void map(long beatmapId) {
        map(beatmapId, null);
    }

    public void map(long beatmapId, GameMode mode) {
        command(mapCommand(beatmapId, mode));
    }

    public void mods(String... mods) {
        command(modsCommand(mods));
    }

    public void start() {
        command(MP + "start");
    }

    public void start(Duration delay) {
        command(MP + "start " + requireSeconds(delay));
    }

    /** 使用 BanchoBot 的預設倒數時間。 */
    public void timer() {
        command(MP + "timer");
    }

    public void timer(Duration duration) {
        command(MP + "timer " + requireSeconds(duration));
    }

    public void abort() {
        command(MP + "abort");
    }

    public void close() {
        command(MP + "close");
    }

    /** null 或空白代表移除密碼。 */
    public void password(String password) {
        command(passwordCommand(password));
    }

    public void addRef(String... usernames) {
        command(addRefCommand(usernames));
    }

    /**
     * 依 set → password → mods → map → addref → invite 的順序送出，未填的欄位跳過。
     * 先組好並檢查全部指令才開始送，任何欄位不合法時一條都不送，避免房間停在套用一半的狀態。
     */
    public void apply(MatchPreset preset) {
        List<String> commands = new ArrayList<>();
        if (preset.hasSettings()) {
            commands.add(setCommand(preset.teamMode(), preset.scoreMode(), preset.size()));
        }
        if (preset.password() != null) {
            commands.add(passwordCommand(preset.password()));
        }
        if (!preset.mods().isEmpty()) {
            commands.add(modsCommand(preset.mods().toArray(String[]::new)));
        }
        if (preset.beatmap() != null) {
            GameMode mode = preset.mode() == null ? null : GameMode.parse(preset.mode())
                    .orElseThrow(() -> new IllegalArgumentException("不認得的遊玩模式：" + preset.mode()));
            commands.add(mapCommand(preset.beatmap(), mode));
        }
        if (!preset.referees().isEmpty()) {
            commands.add(addRefCommand(preset.referees().toArray(String[]::new)));
        }
        preset.invite().forEach(username -> commands.add(inviteCommand(username)));
        commands.forEach(this::command);
    }

    public void settings() {
        command(MP + "settings");
    }

    /** 轉送尚未包裝的 BanchoBot 子指令。 */
    public void raw(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("指令不可為空白");
        }
        command(MP + arguments.strip());
    }

    /** 在房間頻道說一般的話。 */
    public void say(String text) {
        command(text);
    }

    private void command(String text) {
        if (state.get().closed()) {
            throw new IllegalStateException("房間已關閉：" + channel());
        }
        sender.sendMessage(channel(), text);
    }

    private static String inviteCommand(String username) {
        return MP + "invite " + requireName(username);
    }

    private static String setCommand(int teamMode, int scoreMode, int size) {
        requireRange("teamMode", teamMode, 0, 3);
        requireRange("scoreMode", scoreMode, 0, 3);
        requireRange("size", size, 1, 16);
        return MP + "set " + teamMode + " " + scoreMode + " " + size;
    }

    private static String mapCommand(long beatmapId, GameMode mode) {
        return MP + "map " + beatmapId + (mode == null ? "" : " " + mode.id());
    }

    private static String modsCommand(String... mods) {
        if (mods.length == 0) {
            throw new IllegalArgumentException("至少要指定一個 mod");
        }
        for (String mod : mods) {
            if (mod == null || mod.isBlank() || mod.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("mod 名稱不可為空白或含空白：" + mod);
            }
            // BanchoBot 看不懂 NFHD 這類連寫，會直接清空所有 mod；只放行確定接受的長名稱
            if (mod.length() > 2 && !LONG_MOD_NAMES.contains(mod.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("mod 要用空白分開，例如 mods NF HD");
            }
        }
        return MP + "mods " + String.join(" ", mods);
    }

    /** null 或空白代表移除密碼。 */
    private static String passwordCommand(String password) {
        return password == null || password.isBlank() ? MP + "password" : MP + "password " + password.strip();
    }

    private static String addRefCommand(String... usernames) {
        if (usernames.length == 0) {
            throw new IllegalArgumentException("至少要指定一位裁判");
        }
        // 多位裁判以空白分隔，名稱本身含空白會被 BanchoBot 拆成兩個人；空白是否可換成底線尚未實測，先拒絕
        List<String> names = Arrays.stream(usernames).map(MatchRoom::requireName).toList();
        names.stream().filter(name -> name.contains(" ")).findFirst().ifPresent(name -> {
            throw new IllegalArgumentException("addref 的名稱不可含空白：" + name);
        });
        return MP + "addref " + String.join(" ", names);
    }

    private static String requireName(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("使用者名稱不可為空白");
        }
        return username.strip();
    }

    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " 必須介於 " + min + " 到 " + max + "：" + value);
        }
    }

    private static long requireSeconds(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("時間不可為負數：" + duration);
        }
        return duration.toSeconds();
    }
}
