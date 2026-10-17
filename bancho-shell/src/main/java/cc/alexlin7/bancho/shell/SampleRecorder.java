package cc.alexlin7.bancho.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import cc.alexlin7.bancho.irc.IrcTrafficListener;

import lombok.extern.slf4j.Slf4j;

/**
 * 把 {@link SampleFilter} 篩過的原始行寫到 {@code samples-<日期>.irc}，格式與
 * {@code bancho-match/src/test/resources/banchobot/} 的樣本相同，可以直接回放：收到的行原樣寫入（保留行尾空白），
 * 時間與我們送出的指令寫成 {@code #} 註解。出站行由 client 套用遮蔽後才交過來，房間密碼不會出現在檔案裡。
 */
@Slf4j
class SampleRecorder implements IrcTrafficListener {

    static final String HEADER = "# bancho-shell 樣本檔；匯入測試資源前把自己的 nick 改成 test_user";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path directory;
    private final Clock clock;
    /** 上一筆的日期與時間；同一秒內的多行只寫一次時間註解。 */
    private String lastStamp;
    private boolean warned;

    SampleRecorder(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    Path directory() {
        return directory;
    }

    @Override
    public void received(String line) {
        if (SampleFilter.keepReceived(line)) {
            write(line, false);
        }
    }

    @Override
    public void sent(String line) {
        if (SampleFilter.keepSent(line)) {
            write(line, true);
        }
    }

    private synchronized void write(String line, boolean sent) {
        LocalDateTime now = LocalDateTime.now(clock);
        Path file = directory.resolve("samples-" + now.toLocalDate() + ".irc");
        String time = now.format(TIME);
        String stamp = now.toLocalDate() + " " + time;
        StringBuilder text = new StringBuilder();
        try {
            Files.createDirectories(directory);
            if (Files.notExists(file)) {
                text.append(HEADER).append('\n');
                lastStamp = null;
            }
            if (sent) {
                text.append("# ").append(time).append(" -> ").append(line).append('\n');
            } else {
                if (!stamp.equals(lastStamp)) {
                    text.append("# ").append(time).append('\n');
                }
                text.append(line).append('\n');
            }
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastStamp = stamp;
        } catch (IOException e) {
            if (!warned) {
                warned = true;
                log.warn("[shell] 無法寫入樣本檔：{}", file, e);
            }
        }
    }
}
