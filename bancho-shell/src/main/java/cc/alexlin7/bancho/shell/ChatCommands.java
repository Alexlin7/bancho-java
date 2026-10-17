package cc.alexlin7.bancho.shell;

import java.util.List;

import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Arguments;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import cc.alexlin7.bancho.irc.IrcSender;
import cc.alexlin7.bancho.match.MatchService;

/**
 * 一般聊天：在房間頻道說話、送私訊（例如直接對 BanchoBot 下指令）。
 * 以 - 開頭的字會被 Spring Shell 當成選項，要用雙引號把整段文字包起來，例如 {@code say "score -5"}。
 */
@Component
public class ChatCommands {

    private static final String GROUP = "聊天";
    private static final String QUOTE_HINT = "；含 - 開頭的字時請用雙引號包起來";

    private final IrcSender sender;
    private final RoomResolver rooms;

    public ChatCommands(IrcSender sender, ShellSession session, MatchService matchService) {
        this.sender = sender;
        this.rooms = new RoomResolver(matchService, session);
    }

    @Command(name = "say", group = GROUP, completionProvider = "roomOptionCompletion",
            description = "在房間頻道說話" + QUOTE_HINT)
    public String say(@Arguments List<String> words,
                      @Option(longName = "room", description = "房號（預設為目前房間）") Long room,
                      CommandContext context) {
        String invalid = InputGuard.check(context, InputGuard.ANY, "room");
        if (invalid != null) {
            return invalid;
        }
        String text = InputGuard.join(words);
        return rooms.onRoom(room, r -> {
            r.say(requireText(text));
            return "已送出：" + text;
        });
    }

    @Command(name = "msg", group = GROUP, description = "送私訊給玩家或 BanchoBot" + QUOTE_HINT)
    public String msg(@Argument(index = 0, description = "對象") String target, @Arguments List<String> words,
                      CommandContext context) {
        String invalid = InputGuard.check(context, InputGuard.ANY);
        if (invalid != null) {
            return invalid;
        }
        String text = InputGuard.join(words);
        return RoomResolver.guarded(() -> {
            sender.sendMessage(target, requireText(text));
            return "已送出：" + target + " " + text;
        });
    }

    private static String requireText(String text) {
        if (text.isBlank()) {
            throw new IllegalArgumentException("訊息不可為空白");
        }
        return text;
    }
}
