package idv.alexlin7.tw.bancho.shell;

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
            名稱以 - 開頭（例如 -SH-）會被當成選項，需要跳脫：
              用雙引號包起來：addref "-SH-"
              或緊接在指令後面加 --：addref -- -SH-（-- 之後的字全部當成名稱，不能再加 --room 或 --room=…）
              要指定房間時用雙引號：addref "-SH-" --room 42
              單引號沒有作用：'-SH-' 會連引號一起送出
            看到「Option '-SH-' requires a value」就是這個原因。
            """;

    static final String INVITE_ESCAPE_HELP = """
            名稱以 - 開頭（例如 -SH-）會被當成選項，需要跳脫：
              用雙引號包起來：invite "-SH-"
              或緊接在指令後面加 --：invite -- -SH-（-- 之後的字全部當成名稱，不能再加 --room 或 --room=…）
              要指定房間時用雙引號：invite "-SH-" --room 42
              單引號沒有作用：'-SH-' 會連引號一起送出
            看到「Option '-SH-' requires a value」就是這個原因。
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
        return words.stream().anyMatch(word -> word.equals("--room") || word.startsWith("--room="))
                ? "錯誤：-- 之後不能再加 --room 或 --room=…；要指定房間請用雙引號，例如 " + command + " \"-SH-\" --room 42"
                : null;
    }

    private static String display(CommandOption option) {
        String name = option.longName();
        return name != null && !name.isEmpty() ? "--" + name : "-" + option.shortName();
    }
}
