package idv.alexlin7.tw.bancho.match.event;

import java.util.List;

/**
 * 由 BanchoBot 訊息解析出的房間事件。只收錄有真實樣本的訊息格式；
 * team mode、win condition、mod 名稱、slot 狀態維持 BanchoBot 原文字串，不轉成 enum。
 */
public sealed interface MatchEvent {

    long matchId();

    /** 私訊：Created the tournament match https://osu.ppy.sh/mp/{id} {name} */
    record MatchCreated(long matchId, String name) implements MatchEvent {
    }

    /** Closed the match */
    record MatchClosed(long matchId) implements MatchEvent {
    }

    /** Invited {username} to the room */
    record PlayerInvited(long matchId, String username) implements MatchEvent {
    }

    /** {username} joined in slot {slot}. */
    record PlayerJoined(long matchId, String username, int slot) implements MatchEvent {
    }

    /** {username} moved to slot {slot} */
    record PlayerMoved(long matchId, String username, int slot) implements MatchEvent {
    }

    /** {username} left the game. */
    record PlayerLeft(long matchId, String username) implements MatchEvent {
    }

    /** Changed match settings to {size} slots, {teamMode}, {winCondition} */
    record SettingsChanged(long matchId, int size, String teamMode, String winCondition) implements MatchEvent {
    }

    /** Changed beatmap to https://osu.ppy.sh/b/{id} {title}，或 !mp settings 的 Beatmap: 行 */
    record BeatmapChanged(long matchId, long beatmapId, String title) implements MatchEvent {
    }

    /** Enabled {mods}, disabled FreeMod */
    record ModsChanged(long matchId, List<String> mods, boolean freeMod) implements MatchEvent {

        public ModsChanged {
            mods = List.copyOf(mods);
        }
    }

    /** !mp settings 的 Slot {slot} {status} https://osu.ppy.sh/u/{userId} {username} */
    record SlotStatus(long matchId, int slot, String status, long userId, String username) implements MatchEvent {
    }
}
