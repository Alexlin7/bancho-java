package idv.alexlin7.tw.bancho.console;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import idv.alexlin7.tw.bancho.irc.IrcSender;
import idv.alexlin7.tw.bancho.match.MatchRoom;
import idv.alexlin7.tw.bancho.match.MatchService;
import idv.alexlin7.tw.bancho.match.MatchState;

/**
 * 解析終端機輸入並透過 {@link IrcSender} 送出。只由單一 thread 呼叫（{@link ConsoleInputLoop}），
 * 但 {@code /make} 的結果會在 IRC dispatcher thread 上回呼，所以目前對象用 volatile。
 * 不以 / 開頭的文字會送給目前的對象，例如先 {@code /target BanchoBot} 再輸入 {@code !mp make 測試房}。
 */
public class ConsoleCommandInterpreter {

    static final String HELP = """
            指令：
              /target <對象>         設定之後一般文字的送出對象（頻道或玩家，例如 BanchoBot、#mp_123）
              /msg <對象> <內容>     送一則訊息給指定對象，不改變目前對象
              /join <#頻道>          加入頻道並設為目前對象
              /part <#頻道>          離開頻道
              /make <房名>           開房，成功後切換到房間頻道
              /rooms                 列出開著的房間
              /help                  顯示這份說明
            其他不以 / 開頭的文字會送給目前對象。""";

    private final IrcSender sender;
    private final MatchService matchService;
    private final PrintStream out;
    private volatile String currentTarget;
    private volatile CompletableFuture<?> lastMake = CompletableFuture.completedFuture(null);

    public ConsoleCommandInterpreter(IrcSender sender, MatchService matchService, PrintStream out) {
        this.sender = sender;
        this.matchService = matchService;
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.println("錯誤：" + e.getMessage());
        }
    }

    String currentTarget() {
        return currentTarget;
    }

    /** 最近一次 /make 的結果處理，供測試等待。 */
    CompletableFuture<?> lastMake() {
        return lastMake;
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
            case "/make" -> make(requireArgument(argument, "/make <房名>"));
            case "/rooms" -> printRooms();
            default -> out.println("未知指令：" + parts[0] + "（輸入 /help 查看說明）");
        }
    }

    private void make(String name) {
        lastMake = matchService.createMatch(name).whenComplete((room, error) -> {
            if (error == null) {
                out.println("房間已建立：" + room.channel() + " " + name);
                currentTarget = room.channel();
            } else if (unwrap(error) instanceof TimeoutException) {
                out.println("開房逾時：" + name);
            } else {
                out.println("開房失敗：" + name + "（" + unwrap(error).getMessage() + "）");
            }
        });
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private void printRooms() {
        List<MatchRoom> rooms = matchService.openRooms();
        if (rooms.isEmpty()) {
            out.println("目前沒有開著的房間");
            return;
        }
        rooms.forEach(room -> out.print(describe(room)));
    }

    private static String describe(MatchRoom room) {
        MatchState state = room.state();
        StringBuilder text = new StringBuilder()
                .append(room.channel()).append(' ').append(state.name() == null ? "(未知)" : state.name())
                .append("  size ").append(state.settings().size() == null ? "?" : state.settings().size())
                .append("  map ").append(state.beatmap() == null
                        ? "(未設定)" : state.beatmap().id() + " " + state.beatmap().title())
                .append("  mods ").append(state.mods().mods().isEmpty()
                        ? "(無)" : String.join(" ", state.mods().mods()))
                .append(System.lineSeparator());
        text.append(state.slots().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(slot -> "  [" + slot.getKey() + "] " + slot.getValue().username() + System.lineSeparator())
                .collect(Collectors.joining()));
        return text.toString();
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
