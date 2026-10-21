package cc.alexlin7.bancho.shell;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.shell.core.command.CommandArgument;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandOption;

/**
 * Spring Shell 會悄悄忽略沒有對應參數的選項與位置參數（例如打錯的 {@code --dealy}），
 * 對裁判來說等於「下錯指令卻沒有任何提示」。每個指令先經過這裡檢查。
 */
final class InputGuard {

    /** 位置參數數量不限（用 {@code @Arguments} 收集剩餘文字的指令）。 */
    static final int ANY = -1;

    static final String DASH_HINT = "（以 - 開頭的文字會被當成選項：用雙引號包起來，例如 \"-SH-\"；"
            + "或緊接在原指令後面加 --，例如 -- -SH-；-- 之後不能再加選項）";

    static final String ESCAPE_HELP = """
            addref 接受多位裁判：名稱、#ID 或個人頁網址，目標之間以空白分隔。
              addref #2548278 osu.ppy.sh/users/42/fruits
            含空白的玩家名稱請改用 #ID 或個人頁網址，避免被當成不同裁判。
            名稱以 - 開頭（例如 -SH-）會被當成選項，需要跳脫：
              用雙引號包起來：addref "-SH-"
              或緊接在指令後面加 --：addref -- -SH-（-- 之後的字全部當成名稱，不能再加 --room 或 --room=…）
              要指定房間時用雙引號：addref "-SH-" --room 42
              單引號沒有作用：'-SH-' 會連引號一起送出
            看到「Option '-SH-' requires a value」就是這個原因。
            """;

    static final String INVITE_ESCAPE_HELP = """
            玩家目標可用完整名稱、#ID（例如 #2548278）或 https://osu.ppy.sh/users/2548278。
            一次只能邀請一位玩家；多個 #ID 或網址請分別執行 invite，含空白的完整名稱仍視為同一位玩家。
            網址可省略 https://，也可帶 /osu、/taiko、/fruits、/mania；名稱型網址請改用數字 ID 或直接輸入名稱。
            全形字保留原樣；無法辨識名字時可直接貼個人頁網址。純數字視為名稱，ID 請加 #。
            名稱以 - 開頭（例如 -SH-）會被當成選項，需要跳脫：
              用雙引號包起來：invite "-SH-"
              或緊接在指令後面加 --：invite -- -SH-（-- 之後的字全部當成名稱，不能再加 --room 或 --room=…）
              要指定房間時用雙引號：invite "-SH-" --room 42
              單引號沒有作用：'-SH-' 會連引號一起送出
            看到「Option '-SH-' requires a value」就是這個原因。
            """;

    static final String HOST_HELP = """
            指定房主：host <名稱|#ID|個人頁網址> [--room <房號>]
            網址可省略 https://，也可帶 /osu、/taiko、/fruits、/mania；名稱型網址請改用數字 ID 或直接輸入名稱。
              host #2548278
              host https://osu.ppy.sh/users/2548278
              host "- Ｓ Ｈ -" --room 42
              host -- -SH-（目前房間；-- 之後不能再加 --room）
            名稱中的全形字保留原樣；純數字視為名稱，ID 請加 #。
            以 - 開頭的名稱請用雙引號，或緊接在 host 後面加 --；單引號沒有作用。
            """;

    static final String TEAM_HELP = """
            把玩家換隊：team <blue|red> <名稱|#ID|個人頁網址> [--room <房號>]
            顏色放第一位，其後的完整名稱可以含空白：
              team blue Tomari Mari
              team red #2548278
              team red "-SH-" --room 42
              team -- blue -SH-（目前房間；-- 之後不能再加 --room 或 --room=…）
            名稱以 - 開頭時請用雙引號，或緊接在 team 後面加 --；單引號沒有作用。
            """;

    static final String SET_HELP = """
            set <隊伍模式> <計分模式> <人數 1–16> [--room <房號>]
            只打 set 會逐項提示，Enter 沿用目前設定；目前值未知時 Enter 取消，Ctrl+C 隨時取消。
            三項都填妥才送出；也可以直接在一行填完三個參數。
            模式不分大小寫，也接受原本的數字 0–3：
              隊伍 0：h2h / headtohead（個人混戰）
              隊伍 1：tag / tagteam / tagcoop（輪流擊打）
              隊伍 2：teamvs（紅藍隊對抗）
              隊伍 3：tagvs / tagteamvs（隊伍輪流對抗）
              計分 0：score / scorev1 / v1
              計分 1：accuracy / acc
              計分 2：combo
              計分 3：scorev2 / v2
            例如 set teamvs v2 4 等同 set 2 3 4。
            catch、osu、taiko、mania 是遊玩模式，請用 map <圖號> catch 設定。
            """;

    static final String MSG_ESCAPE_HELP = """
            msg 後面的字全部是對象（osu! 的名稱可以有空白，例如 msg Minato Yukina），訊息在提示中輸入。
            只打 msg 時會依序提示對象與訊息；空白輸入或 Ctrl+C 取消。
            名稱以 - 開頭（例如 -SH-）會被當成選項，需要跳脫：
              用雙引號包起來：msg "-SH-"
              或緊接在指令後面加 --：msg -- -SH-
            訊息提示中的文字原樣送出，引號與 - 都不必跳脫。
            """;

    private InputGuard() {
    }

    /**
     * @return 錯誤訊息；輸入合法或沒有 context（直接呼叫方法的單元測試）時回傳 null
     */
    static String check(CommandContext context, int maxArguments, String... allowedOptions) {
        if (context == null) {
            return null;
        }
        Set<String> allowed = Set.of(allowedOptions);
        for (CommandOption option : context.parsedInput().options()) {
            String name = option.longName();
            if (name == null || !allowed.contains(name)) {
                return "錯誤：不認得的選項：" + display(option) + DASH_HINT;
            }
        }
        List<CommandArgument> arguments = context.parsedInput().arguments();
        if (maxArguments != ANY && arguments.size() > maxArguments) {
            return "錯誤：多餘的參數：" + arguments.subList(maxArguments, arguments.size()).stream()
                    .map(CommandArgument::value)
                    .collect(Collectors.joining(" "));
        }
        return null;
    }

    /** 以空白合併收集到的文字，並還原 JLine 補全時加上的 {@code \ } 跳脫。 */
    static String join(List<String> words) {
        return String.join(" ", words).replace("\\ ", " ").strip();
    }

    /** parser 在 -- 後會把房間選項當名稱；拒絕整條指令以免誤送到目前房間。 */
    static String rejectRoomAfterDoubleDash(List<String> words, String command) {
        String targetExample = "team".equals(command) ? "blue \"-SH-\"" : "\"-SH-\"";
        return words.stream().anyMatch(word -> word.equals("--room") || word.startsWith("--room="))
                ? "錯誤：-- 之後不能再加 --room 或 --room=…；要指定房間請用雙引號，例如 " + command + " " + targetExample + " --room 42"
                : null;
    }

    private static String display(CommandOption option) {
        String name = option.longName();
        return name != null && !name.isEmpty() ? "--" + name : "-" + option.shortName();
    }
}
