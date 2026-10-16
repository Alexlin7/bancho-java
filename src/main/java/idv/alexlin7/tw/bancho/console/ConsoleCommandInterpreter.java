package idv.alexlin7.tw.bancho.console;

import java.io.PrintStream;
import java.util.Locale;

import idv.alexlin7.tw.bancho.IrcSender;

/**
 * 解析終端機輸入並透過 {@link IrcSender} 送出。只由單一 thread 呼叫（{@link ConsoleInputLoop}）。
 * 不以 / 開頭的文字會送給目前的對象，例如先 {@code /target BanchoBot} 再輸入 {@code !mp make 測試房}。
 */
public class ConsoleCommandInterpreter {

    static final String HELP = """
            指令：
              /target <對象>         設定之後一般文字的送出對象（頻道或玩家，例如 BanchoBot、#mp_123）
              /msg <對象> <內容>     送一則訊息給指定對象，不改變目前對象
              /join <#頻道>          加入頻道並設為目前對象
              /part <#頻道>          離開頻道
              /help                  顯示這份說明
            其他不以 / 開頭的文字會送給目前對象。""";

    private final IrcSender sender;
    private final PrintStream out;
    private String currentTarget;

    public ConsoleCommandInterpreter(IrcSender sender, PrintStream out) {
        this.sender = sender;
        this.out = out;
    }

    private static String requireArgument(String argument, String usage) {
        if (argument.isEmpty()) {
            throw new IllegalArgumentException("用法：" + usage);
        }
        return argument;
    }

    public void execute(String line) {
        String input = line.strip();
        if (input.isEmpty()) {
            return;
        }
        try {
            if (input.startsWith("/")) {
                executeCommand(input);
            } else {
                sendToCurrentTarget(input);
            }
        } catch (IllegalArgumentException e) {
            out.println("錯誤：" + e.getMessage());
        }
    }

    String currentTarget() {
        return currentTarget;
    }

    private void executeCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String argument = parts.length > 1 ? parts[1].strip() : "";
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "/help" -> out.println(HELP);
            case "/target" -> setTarget(requireArgument(argument, "/target <對象>"));
            case "/join" -> {
                String channel = requireArgument(argument, "/join <#頻道>");
                sender.join(channel);
                setTarget(channel);
            }
            case "/part" -> {
                String channel = requireArgument(argument, "/part <#頻道>");
                sender.part(channel);
                if (channel.equals(currentTarget)) {
                    currentTarget = null;
                }
            }
            case "/msg" -> {
                String[] msg = requireArgument(argument, "/msg <對象> <內容>").split("\\s+", 2);
                if (msg.length < 2) {
                    throw new IllegalArgumentException("用法：/msg <對象> <內容>");
                }
                sender.sendMessage(msg[0], msg[1]);
            }
            default -> out.println("未知指令：" + parts[0] + "（輸入 /help 查看說明）");
        }
    }

    private void sendToCurrentTarget(String text) {
        if (currentTarget == null) {
            out.println("尚未設定對象，請先 /target <對象> 或 /join <#頻道>");
            return;
        }
        sender.sendMessage(currentTarget, text);
    }

    private void setTarget(String target) {
        currentTarget = target;
        out.println("目前對象：" + target);
    }
}
