package cc.alexlin7.bancho.shell;

import java.time.Instant;
import java.util.regex.Pattern;

import org.jline.reader.impl.history.DefaultHistory;

/**
 * 不把帶有房間密碼的指令寫進指令歷史（{@code bancho-history} 是純文字檔）。JLine 在指令執行前就把整行加入歷史，
 * 指令本身拒絕（例如 {@code mp password}、{@code password <密碼>}）也來不及，所以在這裡擋。
 * JLine 的 {@code history-ignore} 只支援區分大小寫的萬用字元，Bancho 指令不分大小寫，因此改用 regex。
 */
class PasswordSafeHistory extends DefaultHistory {

    /** 任何位置的 {@code !mp password <x>}（say、msg 等）、shell 的 {@code mp password …} 與 {@code password …}。 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(!mp\\s+password\\s+\\S|^\\s*mp\\s+password\\b|^\\s*password\\s)");

    @Override
    public void add(Instant time, String line) {
        if (SECRET.matcher(line).find()) {
            return;
        }
        super.add(time, line);
    }
}
