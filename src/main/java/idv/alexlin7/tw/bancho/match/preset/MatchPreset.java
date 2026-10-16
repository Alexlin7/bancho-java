package idv.alexlin7.tw.bancho.match.preset;

import java.util.List;

/**
 * 房間設定範本（{@code bancho.match.presets.<名稱>.*}），所有欄位皆可省略。
 *
 * @param teamMode  !mp set 的 team mode（0–3），與 scoreMode、size 必須一起設定
 * @param scoreMode !mp set 的 score mode（0–3）
 * @param size      人數（1–16）
 */
public record MatchPreset(Integer teamMode, Integer scoreMode, Integer size, String password,
                          List<String> mods, Long beatmap, List<String> referees, List<String> invite) {

    public MatchPreset {
        boolean anySet = teamMode != null || scoreMode != null || size != null;
        boolean allSet = teamMode != null && scoreMode != null && size != null;
        if (anySet && !allSet) {
            throw new IllegalArgumentException("team-mode、score-mode、size 必須一起設定");
        }
        mods = mods == null ? List.of() : List.copyOf(mods);
        referees = referees == null ? List.of() : List.copyOf(referees);
        invite = invite == null ? List.of() : List.copyOf(invite);
    }

    public boolean hasSettings() {
        return teamMode != null;
    }
}
