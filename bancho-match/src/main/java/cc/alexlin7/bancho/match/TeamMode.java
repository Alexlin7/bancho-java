package cc.alexlin7.bancho.match;

import java.util.List;
import java.util.Optional;

/** 隊伍模式；與 map 的遊玩模式 GameMode 分開。 */
public enum TeamMode implements BanchoMode {
    HEAD_TO_HEAD(0, "h2h", "headtohead"),
    TAG_TEAM(1, "tag", "tagteam", "tagcoop"),
    TEAM_VS(2, "teamvs"),
    TAG_TEAM_VS(3, "tagvs", "tagteamvs");

    private final int id;
    private final List<String> aliases;

    TeamMode(int id, String... aliases) {
        this.id = id;
        this.aliases = List.of(aliases);
    }

    public int id() {
        return id;
    }

    public List<String> aliases() {
        return aliases;
    }

    public boolean hasTeams() {
        return this == TEAM_VS || this == TAG_TEAM_VS;
    }

    public static Optional<TeamMode> parse(String text) {
        return BanchoMode.parse(text, values());
    }
}
