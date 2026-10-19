package cc.alexlin7.bancho.match;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import cc.alexlin7.bancho.irc.IrcSender;
import cc.alexlin7.bancho.match.event.MatchEvent;
import cc.alexlin7.bancho.match.preset.MatchPreset;

/**
 * 單一多人房：把 {@code !mp} 指令送到 {@code #mp_<id>}，並保存由事件更新的 {@link MatchState}。
 * 指令可由任何 thread 呼叫；事件只在 IRC dispatcher thread 上套用。
 */
public final class MatchRoom {

    private static final String MP = "!mp ";

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

    /** 邀請玩家，回傳實際入列指令，供前端顯示；不代表 BanchoBot 已確認成功。 */
    public String invite(String username) {
        return command(inviteCommand(username));
    }

    /** 指定房主；可用名稱、#ID 或 osu! 個人頁網址，回傳實際入列指令。 */
    public String host(String player) {
        return command(MP + "host " + PlayerTarget.commandArgument(player));
    }

    /**
     * @param teamMode  0–3
     * @param scoreMode 0–3
     * @param size      1–16
     * @return 實際入列指令
     */
    public String set(int teamMode, int scoreMode, int size) {
        return command(setCommand(teamMode, scoreMode, size));
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

    /** 新增裁判，回傳實際入列指令；含空白名稱請改用 #ID 或個人頁網址。 */
    public String addRef(String... usernames) {
        return command(addRefCommand(usernames));
    }

    /**
     * 依 set → password → mods → map → addref → invite 的順序送出，未填的欄位跳過。
     * 先組好並檢查全部指令才開始送，任何欄位不合法時一條都不送，避免房間停在套用一半的狀態。
     */
    public void apply(MatchPreset preset) {
        presetCommands(preset).forEach(this::command);
    }

    /** 驗證與套用使用同一份組裝流程，任何欄位失敗時都不會送出其他指令。 */
    private static List<String> presetCommands(MatchPreset preset) {
        List<String> commands = new ArrayList<>();
        if (preset.hasSettings()) {
            TeamMode teamMode = presetField("team-mode", () -> TeamMode.parse(preset.teamMode())
                    .orElseThrow(() -> new IllegalArgumentException(preset.teamMode()
                            + "（可用 h2h、tag、teamvs、tagvs 或 0–3）")));
            ScoreMode scoreMode = presetField("score-mode", () -> ScoreMode.parse(preset.scoreMode())
                    .orElseThrow(() -> new IllegalArgumentException(preset.scoreMode()
                            + "（可用 score、accuracy、combo、scorev2 或 0–3）")));
            commands.add(setCommand(teamMode.id(), scoreMode.id(), preset.size()));
        }
        if (preset.password() != null) {
            commands.add(passwordCommand(preset.password()));
        }
        if (!preset.mods().isEmpty()) {
            commands.add(presetField("mods", () -> modsCommand(preset.mods().toArray(String[]::new))));
        }
        if (preset.beatmap() != null) {
            GameMode mode = preset.mode() == null ? null : presetField("mode", () -> GameMode.parse(preset.mode())
                    .orElseThrow(() -> new IllegalArgumentException(preset.mode()
                            + "（可用 osu、taiko、catch、mania 或 0–3）")));
            commands.add(mapCommand(preset.beatmap(), mode));
        }
        if (!preset.referees().isEmpty()) {
            commands.add(presetField("referees", () -> addRefCommand(preset.referees().toArray(String[]::new))));
        }
        preset.invite().forEach(username -> commands.add(presetField("invite", () -> inviteCommand(username))));
        return commands;
    }

    private static <T> T presetField(String field, Supplier<T> value) {
        try {
            return value.get();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " 無效：" + e.getMessage(), e);
        }
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

    private String command(String text) {
        if (state.get().closed()) {
            throw new IllegalStateException("房間已關閉：" + channel());
        }
        sender.sendMessage(channel(), text);
        return text;
    }

    private static String inviteCommand(String username) {
        return MP + "invite " + PlayerTarget.commandArgument(username);
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

    /**
     * 使用與 apply 相同的指令組裝驗證所有欄位，讓設定錯誤在開房前就被發現，不送出指令。
     *
     * @param name   範本名稱，只用於錯誤訊息
     * @param preset 要檢查的範本
     * @throws IllegalArgumentException 欄位不合法時，訊息帶有範本名稱
     */
    public static void validatePreset(String name, MatchPreset preset) {
        try {
            presetCommands(preset);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("範本 " + name + " 的 " + e.getMessage(), e);
        }
    }

    private static String modsCommand(String... mods) {
        if (mods.length == 0) {
            throw new IllegalArgumentException("至少要指定一個 mod");
        }
        for (String mod : mods) {
            if (mod == null || mod.isBlank() || mod.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("mod 名稱不可為空白或含空白：" + mod);
            }
            if (!ModNames.isSingleMod(mod)) {
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
        // 多位裁判以空白分隔，含空白的名稱使用 #ID 或網址，避免被拆成不同玩家。
        List<String> names = Arrays.stream(usernames).map(PlayerTarget::commandArgument).toList();
        names.stream().filter(name -> name.codePoints().anyMatch(Character::isWhitespace)).findFirst().ifPresent(name -> {
            throw new IllegalArgumentException("addref 的名稱不可含空白：" + name + "；請改用 #ID 或個人頁網址");
        });
        return MP + "addref " + String.join(" ", names);
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
