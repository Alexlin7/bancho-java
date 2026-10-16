package idv.alexlin7.tw.bancho;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 解析後的 IRC 訊息（RFC 1459 §2.3.1）：{@code [:prefix] command params... [:trailing]}。
 * trailing 會作為 {@link #params()} 的最後一個元素。
 */
public record IrcMessage(String prefix, String command, List<String> params) {

    private static final Pattern COMMAND = Pattern.compile("[A-Za-z]+|\\d{3}");
    private static final Pattern SPACES = Pattern.compile(" +");

    public IrcMessage {
        params = List.copyOf(params);
    }

    /**
     * 解析一行原始訊息；空行或格式不合法時回傳 empty。
     */
    public static Optional<IrcMessage> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String rest = line;
        // IRCv3 message tags（Bancho 不會送，但遇到時略過而不是誤判）
        if (rest.startsWith("@")) {
            int space = rest.indexOf(' ');
            if (space < 0) {
                return Optional.empty();
            }
            rest = rest.substring(space + 1).stripLeading();
        }
        String prefix = null;
        if (rest.startsWith(":")) {
            int space = rest.indexOf(' ');
            if (space < 0) {
                return Optional.empty();
            }
            prefix = rest.substring(1, space);
            rest = rest.substring(space + 1).stripLeading();
        }
        String trailing = null;
        int trailingStart = rest.indexOf(" :");
        if (trailingStart >= 0) {
            trailing = rest.substring(trailingStart + 2);
            rest = rest.substring(0, trailingStart);
        }
        String[] parts = SPACES.split(rest.strip());
        if (!COMMAND.matcher(parts[0]).matches()) {
            return Optional.empty();
        }
        List<String> params = new ArrayList<>(Arrays.asList(parts).subList(1, parts.length));
        if (trailing != null) {
            params.add(trailing);
        }
        return Optional.of(new IrcMessage(prefix, parts[0].toUpperCase(Locale.ROOT), params));
    }

    /**
     * prefix 中 {@code !} 之前的暱稱；沒有 prefix 時回傳空字串。
     */
    public String nick() {
        if (prefix == null) {
            return "";
        }
        int bang = prefix.indexOf('!');
        return bang < 0 ? prefix : prefix.substring(0, bang);
    }

    /**
     * 第 index 個參數；不存在時回傳空字串。
     */
    public String param(int index) {
        return index < params.size() ? params.get(index) : "";
    }

    /**
     * 最後一個參數（通常是 trailing 文字）；沒有參數時回傳空字串。
     */
    public String trailing() {
        return params.isEmpty() ? "" : params.getLast();
    }
}
