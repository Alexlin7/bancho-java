package idv.alexlin7.tw.bancho.shell;

import org.springframework.stereotype.Component;

import idv.alexlin7.tw.bancho.irc.IrcMessage;
import idv.alexlin7.tw.bancho.irc.IrcMessageListener;

/**
 * 把收到的訊息印到 shell：頻道訊息 {@code [#mp_1] 名稱: 內容}，私訊 {@code [PM] 名稱: 內容}。
 */
@Component
class ShellMessagePrinter implements IrcMessageListener {

    /**
     * CTCP ACTION（/me）的包裝：\u0001ACTION 內容\u0001
     */
    private static final String ACTION_PREFIX = "\u0001ACTION ";

    private final ShellOutput out;

    ShellMessagePrinter(ShellOutput out) {
        this.out = out;
    }

    @Override
    public void onMessage(IrcMessage message) {
        String target = message.param(0);
        String source = target.startsWith("#") ? target : "PM";
        String text = message.trailing();
        if (text.startsWith(ACTION_PREFIX)) {
            String action = text.substring(ACTION_PREFIX.length()).replace("\u0001", "");
            out.println("[" + source + "] * " + message.nick() + " " + action);
        } else {
            out.println("[" + source + "] " + message.nick() + ": " + text);
        }
    }
}
