package cc.alexlin7.bancho.match;

import java.util.regex.Pattern;

/**
 * 遮蔽出站 IRC 行中的房間密碼，交給 {@code OsuIrcClient.setOutboundRedactor} 使用：
 * 套用在 log 與原始行觀察者，實際送出的內容不變。
 */
public final class BanchoRedaction {

    /** {@code !mp password <密碼>}，不分大小寫；不帶密碼的 {@code !mp password}（移除密碼）不必遮蔽。 */
    private static final Pattern PASSWORD = Pattern.compile("(?i)^(PRIVMSG \\S+ :!mp\\s+password\\s+)\\S.*$");

    private BanchoRedaction() {
    }

    public static String redactOutbound(String line) {
        return PASSWORD.matcher(line).replaceFirst("$1***");
    }
}
