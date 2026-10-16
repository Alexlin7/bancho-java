package idv.alexlin7.tw.bancho.console;

import java.io.PrintStream;

import idv.alexlin7.tw.bancho.IrcMessage;
import idv.alexlin7.tw.bancho.IrcMessageListener;

/**
 * 把收到的訊息印到終端機：頻道訊息 {@code [#mp_1] 名稱: 內容}，私訊 {@code [PM] 名稱: 內容}。
 */
public class ConsoleMessageListener implements IrcMessageListener {

    /**
     * CTCP ACTION（/me）的包裝：\u0001ACTION 內容\u0001
     */
    private static final String ACTION_PREFIX = "\u0001ACTION ";

    private final PrintStream out;

    public ConsoleMessageListener(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onMessage(IrcMessage message) {
        String target = message.param(0);
        String source = target.startsWith("#") ? target : "PM";
        String text = message.trailing();
        if (text.startsWith(ACTION_PREFIX)) {
            String action = text.substring(ACTION_PREFIX.length()).replace("\u0001", "");
            out.printf("[%s] * %s %s%n", source, message.nick(), action);
        } else {
            out.printf("[%s] %s: %s%n", source, message.nick(), text);
        }
    }
}
