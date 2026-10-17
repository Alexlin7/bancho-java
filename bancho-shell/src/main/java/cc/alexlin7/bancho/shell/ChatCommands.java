package cc.alexlin7.bancho.shell;

import java.util.List;
import java.util.Optional;

import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Arguments;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import cc.alexlin7.bancho.irc.IrcSender;
import cc.alexlin7.bancho.match.MatchService;

/**
 * 一般聊天：在房間頻道說話、送私訊（例如直接對 BanchoBot 下指令）。
 * 以 - 開頭的字會被 Spring Shell 當成選項，要用雙引號把整段文字包起來，例如 {@code say "score -5"}。
 * 私訊的對象與內容都可能有空白，寫在同一行就分不出界線，所以 {@code msg} 只收對象，內容另外提示輸入。
 */
@Component
public class ChatCommands {

    private static final String GROUP = "聊天";
    private static final String QUOTE_HINT = "；含 - 開頭的字時請用雙引號包起來";

    private static final String CANCELLED = "已取消，沒有送出訊息";

    private final IrcSender sender;
    private final RoomResolver rooms;
    private final TextPrompt prompt;
    private final LoginGuard loginGuard;

    public ChatCommands(IrcSender sender, ShellSession session, MatchService matchService, TextPrompt prompt,
                        LoginGuard loginGuard) {
        this.loginGuard = loginGuard;
        this.sender = sender;
        this.rooms = new RoomResolver(matchService, session);
        this.prompt = prompt;
    }

    @Command(name = "say", group = GROUP, completionProvider = "roomOptionCompletion",
            description = "在房間頻道說話" + QUOTE_HINT)
    public String say(@Arguments List<String> words,
                      @Option(longName = "room", description = "房號（預設為目前房間）") Long room,
                      CommandContext context) {
        String invalid = InputGuard.check(context, InputGuard.ANY, "room");
        if (invalid == null) {
            invalid = loginGuard.check();
        }
        if (invalid != null) {
            return invalid;
        }
        String text = InputGuard.join(words);
        return rooms.onRoom(room, r -> {
            r.say(requireText(text));
            return "已送出：" + text;
        });
    }

    @Command(name = "msg", group = GROUP, completionProvider = "userCompletion",
            description = "送私訊給玩家或 BanchoBot：msg <對象> 後在提示中輸入訊息，不給對象時也會提示",
            help = InputGuard.MSG_ESCAPE_HELP)
    public String msg(@Arguments List<String> targetWords, CommandContext context) {
        String invalid = InputGuard.check(context, InputGuard.ANY);
        if (invalid == null) {
            invalid = loginGuard.check();
        }
        if (invalid != null) {
            return invalid;
        }
        String named = InputGuard.join(targetWords);
        return RoomResolver.guarded(() -> {
            Optional<String> target = named.isEmpty() ? prompt.read("對象：") : Optional.of(named);
            if (target.isEmpty()) {
                return CANCELLED;
            }
            Optional<String> text = prompt.read("訊息：");
            if (text.isEmpty()) {
                return CANCELLED;
            }
            sender.sendMessage(target.get(), text.get());
            return "已送出給 " + target.get() + "：" + text.get();
        });
    }

    private static String requireText(String text) {
        if (text.isBlank()) {
            throw new IllegalArgumentException("訊息不可為空白");
        }
        return text;
    }
}
