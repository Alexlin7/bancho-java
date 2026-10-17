package idv.alexlin7.tw.bancho.shell;

import java.util.Optional;

import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;

/**
 * 用 JLine 讀取密碼，輸入以 {@code *} 回顯（看得出打了幾個字）。
 * 沿用 shell 自己的 reader（IRC 訊息的 printAbove 才會重繪提示），但讀取期間暫時換掉會改寫輸入的設定：
 * 歷史展開（{@code !!}）、shell parser 的引號與反斜線跳脫，以及 Tab 補全；讀完一律還原。
 */
final class JLineSecretPrompt implements SecretPrompt {

    private static final char MASK = '*';

    /** 不認任何引號與跳脫字元：輸入原樣當成密碼。 */
    private static final Parser LITERAL = new DefaultParser().quoteChars(new char[0]).escapeChars(new char[0]);

    private final LineReader reader;

    JLineSecretPrompt(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public Optional<String> read(String prompt) {
        boolean expansionDisabled = reader.isSet(LineReader.Option.DISABLE_EVENT_EXPANSION);
        Parser parser = reader.getParser();
        Completer completer = reader instanceof LineReaderImpl impl ? impl.getCompleter() : null;
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        if (reader instanceof LineReaderImpl impl) {
            impl.setParser(LITERAL);
            impl.setCompleter(null);
        }
        try {
            String line = reader.readLine(prompt, MASK);
            return line == null || line.isBlank() ? Optional.empty() : Optional.of(line.strip());
        } catch (UserInterruptException | EndOfFileException _) {
            return Optional.empty();
        } finally {
            if (!expansionDisabled) {
                reader.unsetOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            }
            if (reader instanceof LineReaderImpl impl) {
                impl.setParser(parser);
                impl.setCompleter(completer);
            }
        }
    }
}
