package cc.alexlin7.bancho.shell;

import java.util.Locale;
import java.util.Optional;

import cc.alexlin7.bancho.irc.IrcMessage;

/**
 * 樣本檔只收能補解析規則的行：BanchoBot 的訊息、Bancho 的系統訊息（NOTICE、數字回覆），以及我們送給 BanchoBot 或
 * {@code #mp_} 的指令。房間玩家的聊天與其他人的私訊不收。
 */
final class SampleFilter {

    private static final String BANCHO_BOT = "banchobot";

    private SampleFilter() {
    }

    static boolean keepReceived(String line) {
        Optional<IrcMessage> parsed = IrcMessage.parse(line);
        if (parsed.isEmpty()) {
            return false;
        }
        IrcMessage message = parsed.get();
        String command = message.command();
        if ("PRIVMSG".equals(command) || "NOTICE".equals(command)) {
            if (BANCHO_BOT.equals(message.nick().toLowerCase(Locale.ROOT))) {
                return true;
            }
            // 伺服器送的 NOTICE 沒有 prefix，或 prefix 是不含 ! 的主機名稱
            return "NOTICE".equals(command) && (message.prefix() == null || message.prefix().indexOf('!') < 0);
        }
        return command.length() == 3 && command.chars().allMatch(Character::isDigit);
    }

    static boolean keepSent(String line) {
        if (!line.startsWith("PRIVMSG ")) {
            return false;
        }
        int end = line.indexOf(' ', "PRIVMSG ".length());
        String target = end < 0 ? "" : line.substring("PRIVMSG ".length(), end);
        return BANCHO_BOT.equals(target.toLowerCase(Locale.ROOT)) || target.startsWith("#mp_");
    }
}
