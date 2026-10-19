package cc.alexlin7.bancho.match.preset;

import java.util.List;

/**
 * 房間設定範本（{@code bancho.match.presets.<名稱>.*}），所有欄位皆可省略。
 *
 * @param teamMode  !mp set 的 team mode（0–3 或 TeamMode 別名），與 scoreMode、size 必須一起設定
 * @param scoreMode !mp set 的 score mode（0–3 或 ScoreMode 別名）
 * @param size      人數（1–16）
 * @param mode      遊玩模式（數字或名稱），必須搭配 beatmap；由 match 層驗證值
 */
public record MatchPreset(String teamMode, String scoreMode, Integer size, String password,
                          List<String> mods, Long beatmap, List<String> referees, List<String> invite, String mode) {

    public MatchPreset {
        boolean anySet = teamMode != null || scoreMode != null || size != null;
        boolean allSet = teamMode != null && scoreMode != null && size != null;
        if (anySet && !allSet) {
            throw new IllegalArgumentException("team-mode、score-mode、size 必須一起設定");
        }
        if (mode != null && beatmap == null) {
            throw new IllegalArgumentException("mode 必須和 beatmap 一起設定");
        }
        mods = mods == null ? List.of() : List.copyOf(mods);
        referees = referees == null ? List.of() : List.copyOf(referees);
        invite = invite == null ? List.of() : List.copyOf(invite);
    }

    public boolean hasSettings() {
        return teamMode != null;
    }
}
