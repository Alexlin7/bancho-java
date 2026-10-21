package cc.alexlin7.bancho.match;

import java.util.Optional;

/** 房間中的紅、藍隊；未得知玩家隊伍時使用 null。 */
public enum Team {
    BLUE, RED;

    public static Optional<Team> parse(String text) {
        if ("blue".equalsIgnoreCase(text)) {
            return Optional.of(BLUE);
        }
        if ("red".equalsIgnoreCase(text)) {
            return Optional.of(RED);
        }
        return Optional.empty();
    }
}
