package cc.alexlin7.bancho.shell;

import java.time.Instant;
import java.util.regex.Pattern;

import org.jline.reader.impl.history.DefaultHistory;

/**
 * 不把帶有房間密碼的指令寫進指令歷史（{@code bancho-history} 是純文字檔）。JLine 在指令執行前就把整行加入歷史，
 * 指令本身拒絕（例如 {@code mp password}、{@code password <密碼>}）也來不及，所以在這裡擋。
 * JLine 的 {@code history-ignore} 只支援區分大小寫的萬用字元，Bancho 指令不分大小寫，因此改用 regex。
 * 比對前先去掉續行、雙引號與反斜線：shell 的 parser 會移除它們，{@code say "!mp" "password" x} 送出的仍是 {@code !mp password x}。
 */
class PasswordSafeHistory extends DefaultHistory {

    /** 任何位置的 {@code !mp password <x>}（say、msg 等）、shell 的 {@code mp password …} 與 {@code password …}。 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(!mp\\s+password\\s+\\S|^\\s*mp\\s+password\\b|^\\s*password\\s)");
    private static final Pattern CONTINUATION = Pattern.compile("\\\\\\r?\\n");

    @Override
    public void add(Instant time, String line) {
        // 行尾反斜線續行：parser 會把反斜線和換行一起刪掉，把前後兩段直接接起來（pass\⏎word → password）
        String parsedLike = CONTINUATION.matcher(line).replaceAll("").replace("\"", "").replace("\\", "");
        if (SECRET.matcher(parsedLike).find()) {
            return;
        }
        super.add(time, line);
    }
}
