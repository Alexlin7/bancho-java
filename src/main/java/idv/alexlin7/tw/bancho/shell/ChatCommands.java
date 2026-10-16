package idv.alexlin7.tw.bancho.shell;

import java.util.List;

import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Arguments;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import idv.alexlin7.tw.bancho.irc.IrcSender;
import idv.alexlin7.tw.bancho.match.MatchService;

/**
 * 一般聊天：在房間頻道說話、送私訊（例如直接對 BanchoBot 下指令）。
 */
@Component
public class ChatCommands {

    private static final String GROUP = "聊天";

    private final IrcSender sender;
    private final RoomResolver rooms;

    public ChatCommands(IrcSender sender, ShellSession session, MatchService matchService) {
        this.sender = sender;
        this.rooms = new RoomResolver(matchService, session);
    }

    @Command(name = "say", group = GROUP, description = "在房間頻道說話")
    public String say(@Arguments List<String> words,
                      @Option(longName = "room", description = "房號（預設為目前房間）") Long room) {
        String text = String.join(" ", words);
        return rooms.onRoom(room, r -> {
            r.say(requireText(text));
            return "已送出：" + text;
        });
    }

    @Command(name = "msg", group = GROUP, description = "送私訊給玩家或 BanchoBot")
    public String msg(@Argument(index = 0, description = "對象") String target, @Arguments List<String> words) {
        String text = String.join(" ", words);
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
