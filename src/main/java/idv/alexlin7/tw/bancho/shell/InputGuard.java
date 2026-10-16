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
                return "錯誤：不認得的選項：" + display(option) + "（以 - 開頭的文字請用雙引號包起來）";
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

    private static String display(CommandOption option) {
        String name = option.longName();
        return name != null && !name.isEmpty() ? "--" + name : "-" + option.shortName();
    }
}
