package cc.alexlin7.bancho.match;

import java.util.List;
import java.util.Optional;

/** BanchoBot 的計分模式編號及 shell 可接受的名稱。 */
public enum ScoreMode implements BanchoMode {
    SCORE(0, "score", "scorev1", "v1"),
    ACCURACY(1, "accuracy", "acc"),
    COMBO(2, "combo"),
    SCORE_V2(3, "scorev2", "v2");

    private final int id;
    private final List<String> aliases;

    ScoreMode(int id, String... aliases) {
        this.id = id;
        this.aliases = List.of(aliases);
    }

    public int id() {
        return id;
    }

    public List<String> aliases() {
        return aliases;
    }

    public static Optional<ScoreMode> parse(String text) {
        return BanchoMode.parse(text, values());
    }
}
