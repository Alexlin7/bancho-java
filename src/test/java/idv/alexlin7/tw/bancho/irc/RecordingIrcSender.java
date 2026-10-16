package idv.alexlin7.tw.bancho.irc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 記錄呼叫內容的假 sender：{@code "msg <對象> <內容>"}、{@code "join <頻道>"}、{@code "part <頻道>"}。
 * join / part 不以 # 開頭時比照真實 client 拋出 IllegalArgumentException。
 */
public class RecordingIrcSender implements IrcSender {

    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void sendMessage(String target, String message) {
        calls.add("msg " + target + " " + message);
    }

    @Override
    public void join(String channel) {
        requireChannel(channel);
        calls.add("join " + channel);
    }

    @Override
    public void part(String channel) {
        requireChannel(channel);
        calls.add("part " + channel);
    }

    private static void requireChannel(String channel) {
        if (!channel.startsWith("#")) {
            throw new IllegalArgumentException("頻道名稱必須以 # 開頭：" + channel);
        }
    }
}
