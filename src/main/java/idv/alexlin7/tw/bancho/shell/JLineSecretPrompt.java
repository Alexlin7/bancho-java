package idv.alexlin7.tw.bancho.shell;

import java.util.Optional;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

/**
 * 用 JLine 讀取密碼，輸入以 {@code *} 回顯（看得出打了幾個字）。
 */
final class JLineSecretPrompt implements SecretPrompt {

    private static final char MASK = '*';

    private final LineReader reader;

    JLineSecretPrompt(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public Optional<String> read(String prompt) {
        try {
            String line = reader.readLine(prompt, MASK);
            return line == null || line.isBlank() ? Optional.empty() : Optional.of(line.strip());
        } catch (UserInterruptException | EndOfFileException e) {
            return Optional.empty();
        }
    }
}
