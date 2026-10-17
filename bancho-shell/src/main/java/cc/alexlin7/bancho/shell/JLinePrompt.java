package cc.alexlin7.bancho.shell;

import java.util.Optional;

import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;

/**
 * 在指令執行中用 JLine 讀取一行輸入：密碼以 {@code *} 回顯（看得出打了幾個字），一般文字照常顯示。
 * 沿用 shell 自己的 reader（IRC 訊息的 printAbove 才會重繪提示），但讀取期間暫時換掉會改寫輸入的設定：
 * 歷史展開（{@code !!}、{@code !mp}）、shell parser 的引號與反斜線跳脫、Tab 補全，以及寫入指令歷史；讀完一律還原。
 */
final class JLinePrompt {

    private static final char MASK = '*';

    /** 不認任何引號與跳脫字元：輸入原樣保留。 */
    private static final Parser LITERAL = new DefaultParser().quoteChars(new char[0]).escapeChars(new char[0]);

    private final LineReader reader;
    private final Character mask;

    private JLinePrompt(LineReader reader, Character mask) {
        this.reader = reader;
        this.mask = mask;
    }

    static SecretPrompt secret(LineReader reader) {
        return new JLinePrompt(reader, MASK)::read;
    }

    static TextPrompt text(LineReader reader) {
        return new JLinePrompt(reader, null)::read;
    }

    private Optional<String> read(String prompt) {
        boolean expansionDisabled = reader.isSet(LineReader.Option.DISABLE_EVENT_EXPANSION);
        Object historyDisabled = reader.getVariable(LineReader.DISABLE_HISTORY);
        Parser parser = reader.getParser();
        Completer completer = reader instanceof LineReaderImpl impl ? impl.getCompleter() : null;
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        // 遮蔽輸入本來就不寫入歷史；一般文字（例如私訊內容）也不該在按上鍵時當成指令叫出來
        reader.setVariable(LineReader.DISABLE_HISTORY, true);
        if (reader instanceof LineReaderImpl impl) {
            impl.setParser(LITERAL);
            impl.setCompleter(null);
        }
        try {
            String line = reader.readLine(prompt, mask);
            return line == null || line.isBlank() ? Optional.empty() : Optional.of(line.strip());
        } catch (UserInterruptException | EndOfFileException _) {
            return Optional.empty();
        } finally {
            if (!expansionDisabled) {
                reader.unsetOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            }
            reader.setVariable(LineReader.DISABLE_HISTORY, historyDisabled);
            if (reader instanceof LineReaderImpl impl) {
                impl.setParser(parser);
                impl.setCompleter(completer);
            }
        }
    }
}
