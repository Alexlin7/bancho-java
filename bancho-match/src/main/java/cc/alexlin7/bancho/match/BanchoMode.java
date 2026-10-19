package cc.alexlin7.bancho.match;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** BanchoBot 模式共用的編號、別名與解析；三種模式的語意仍由各自 enum 決定。 */
public sealed interface BanchoMode permits GameMode, TeamMode, ScoreMode {

    int id();

    List<String> aliases();

    static <M extends BanchoMode> Optional<M> parse(String text, M[] modes) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(modes)
                .filter(mode -> Integer.toString(mode.id()).equals(normalized) || mode.aliases().contains(normalized))
                .findFirst();
    }
}
