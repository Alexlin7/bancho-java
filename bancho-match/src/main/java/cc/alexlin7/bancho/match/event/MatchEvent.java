package cc.alexlin7.bancho.match.event;

import java.util.List;

import cc.alexlin7.bancho.match.Team;

/**
 * 房間事件契約；parser 只依真實樣本辨識訊息格式。
 * team mode、win condition、mod 名稱、slot 狀態維持 BanchoBot 原文字串，不轉成 enum。
 * 玩家隊伍轉成 {@link Team}，供前端分組；隊伍、host 與部分設定的新格式仍待樣本，尚未接上 parser。
 */
public sealed interface MatchEvent {

    long matchId();

    /** 私訊：Created the tournament match https://osu.ppy.sh/mp/{id} {name} */
    record MatchCreated(long matchId, String name) implements MatchEvent {
    }

    /** Closed the match */
    record MatchClosed(long matchId) implements MatchEvent {
    }

    /** The match has started! */
    record MatchStarted(long matchId) implements MatchEvent {
    }

    /** Aborted the match */
    record MatchAborted(long matchId) implements MatchEvent {
    }

    /** The match has finished! */
    record MatchFinished(long matchId) implements MatchEvent {
    }

    /** All players are ready */
    record AllPlayersReady(long matchId) implements MatchEvent {
    }

    /** Changed match mode to {mode}（目前只有 CatchTheBeat 的樣本） */
    record GameModeChanged(long matchId, String mode) implements MatchEvent {
    }

    /** {username} finished playing (Score: {score}, {result}).（result 目前只有 PASSED 的樣本） */
    record PlayerFinished(long matchId, String username, long score, String result) implements MatchEvent {
    }

    /** Invited {username} to the room */
    record PlayerInvited(long matchId, String username) implements MatchEvent {
    }

    /** {username} joined in slot {slot}. */
    record PlayerJoined(long matchId, String username, int slot, Team team) implements MatchEvent {

        public PlayerJoined(long matchId, String username, int slot) {
            this(matchId, username, slot, null);
        }
    }

    /** 玩家換隊的事件契約；真實訊息格式仍待樣本。 */
    record PlayerTeamChanged(long matchId, String username, Team team) implements MatchEvent {
    }

    /** 房主異動的事件契約；真實訊息格式仍待樣本。 */
    record HostChanged(long matchId, String username) implements MatchEvent {
    }

    /** {username} moved to slot {slot} */
    record PlayerMoved(long matchId, String username, int slot) implements MatchEvent {
    }

    /** {username} left the game. */
    record PlayerLeft(long matchId, String username) implements MatchEvent {
    }

    /** Changed match settings to {size} slots, {teamMode}, {winCondition}；null 欄位表示此次未更新。 */
    record SettingsChanged(long matchId, Integer size, String teamMode, String winCondition) implements MatchEvent {
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
