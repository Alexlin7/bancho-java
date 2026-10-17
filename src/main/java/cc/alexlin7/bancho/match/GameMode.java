package cc.alexlin7.bancho.match;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** BanchoBot 的遊玩模式編號與 shell／範本可接受的名稱。 */
public enum GameMode {
    OSU(0, "osu", "std"),
    TAIKO(1, "taiko"),
    CATCH(2, "catch", "fruits", "ctb"),
    MANIA(3, "mania");

    private final int id;
    private final List<String> aliases;

    GameMode(int id, String... aliases) {
        this.id = id;
        this.aliases = List.of(aliases);
    }

    public int id() {
        return id;
    }

    public static Optional<GameMode> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(mode -> Integer.toString(mode.id).equals(normalized) || mode.aliases.contains(normalized))
                .findFirst();
    }
}
