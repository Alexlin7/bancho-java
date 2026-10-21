package cc.alexlin7.bancho.match;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cc.alexlin7.bancho.match.event.MatchEvent;
import cc.alexlin7.bancho.match.event.MatchEvent.AllPlayersReady;
import cc.alexlin7.bancho.match.event.MatchEvent.BeatmapChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.GameModeChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchClosed;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchCreated;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchFinished;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchStarted;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchAborted;
import cc.alexlin7.bancho.match.event.MatchEvent.ModsChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerFinished;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerInvited;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerLeft;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerMoved;
import cc.alexlin7.bancho.match.event.MatchEvent.SettingsChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.SlotStatus;
import cc.alexlin7.bancho.match.event.MatchEvent.HostChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerTeamChanged;

/**
 * 房間狀態的不可變快照。每個事件透過 {@link #apply(MatchEvent)} 產生新的實例。
 *
 * @param name     房名；手動加入、未看過開房訊息的房間為 null
 * @param gameMode BanchoBot 回覆的遊玩模式原文（例如 CatchTheBeat）；尚未收到時為 null
 * @param host     房主名稱；尚未得知或房主離開時為 null
 */
public record MatchState(String name, boolean closed, Settings settings, Beatmap beatmap,
                         ModSelection mods, Map<Integer, Slot> slots, String gameMode, String host) {

    public MatchState {
        slots = Map.copyOf(slots);
    }

    public static MatchState initial(String name) {
        return new MatchState(name, false, new Settings(null, null, null), null,
                new ModSelection(List.of(), false), Map.of(), null, null);
    }

    MatchState apply(MatchEvent event) {
        return switch (event) {
            case MatchCreated created -> name == null ? withName(created.name()) : this;
            case MatchClosed _ -> new MatchState(name, true, settings, beatmap, mods, slots, gameMode, host);
            case PlayerInvited _ -> this;
            case MatchStarted _, MatchAborted _, MatchFinished _, AllPlayersReady _, PlayerFinished _ -> this;
            case PlayerJoined joined -> withPlayerAt(joined.slot(), new Slot(joined.username(), null, null, joined.team()));
            case PlayerMoved moved -> withPlayerAt(moved.slot(),
                    player(moved.username()).orElse(new Slot(moved.username(), null, null)));
            case PlayerLeft left -> withSlots(without(left.username()))
                    .withHost(left.username().equals(host) ? null : host);
            case SlotStatus status -> withPlayerAt(status.slot(),
                    new Slot(status.username(), status.userId(), status.status(),
                            player(status.username()).map(Slot::team).orElse(null)));
            case PlayerTeamChanged changed -> withPlayerTeam(changed.username(), changed.team());
            case HostChanged changed -> withHost(changed.username());
            case SettingsChanged changed -> withSettings(changed);
            case BeatmapChanged changed -> new MatchState(name, closed, settings,
                    new Beatmap(changed.beatmapId(), changed.title()), mods, slots, gameMode, host);
            case ModsChanged changed -> new MatchState(name, closed, settings, beatmap,
                    new ModSelection(changed.mods(), changed.freeMod()), slots, gameMode, host);
            case GameModeChanged changed -> new MatchState(name, closed, settings, beatmap, mods, slots, changed.mode(), host);
        };
    }

    private MatchState withName(String newName) {
        return new MatchState(newName, closed, settings, beatmap, mods, slots, gameMode, host);
    }

    private MatchState withSlots(Map<Integer, Slot> newSlots) {
        return new MatchState(name, closed, settings, beatmap, mods, newSlots, gameMode, host);
    }

    private MatchState withHost(String newHost) {
        return new MatchState(name, closed, settings, beatmap, mods, slots, gameMode, newHost);
    }

    private Optional<Slot> player(String username) {
        return slots.values().stream().filter(slot -> slot.username().equals(username)).findFirst();
    }

    private MatchState withPlayerTeam(String username, Team team) {
        for (var entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (slot.username().equals(username)) {
                return withPlayerAt(entry.getKey(), new Slot(username, slot.userId(), slot.status(), team));
            }
        }
        return this;
    }

    private MatchState withSettings(SettingsChanged changed) {
        Settings updated = new Settings(changed.size() != null ? changed.size() : settings.size(),
                changed.teamMode() != null ? changed.teamMode() : settings.teamMode(),
                changed.winCondition() != null ? changed.winCondition() : settings.winCondition());
        Map<Integer, Slot> updatedSlots = slots;
        if (TeamMode.parse(updated.teamMode()).filter(mode -> !mode.hasTeams()).isPresent()) {
            updatedSlots = new HashMap<>(slots);
            updatedSlots.replaceAll((_, slot) -> new Slot(slot.username(), slot.userId(), slot.status()));
        }
        return new MatchState(name, closed, updated, beatmap, mods, updatedSlots, gameMode, host);
    }

    /** 先把同名玩家從其他 slot 移除，再放進指定 slot（覆蓋原本的人）。 */
    private MatchState withPlayerAt(int slot, Slot player) {
        Map<Integer, Slot> updated = without(player.username());
        updated.put(slot, player);
        return withSlots(updated);
    }

    private Map<Integer, Slot> without(String username) {
        Map<Integer, Slot> updated = new HashMap<>(slots);
        updated.values().removeIf(slot -> slot.username().equals(username));
        return updated;
    }

    /** 欄位在尚未收到對應事件時為 null。 */
    public record Settings(Integer size, String teamMode, String winCondition) {
    }

    public record Beatmap(long id, String title) {
    }

    public record ModSelection(List<String> mods, boolean freeMod) {

        public ModSelection {
            mods = List.copyOf(mods);
        }
    }

    /** userId、status 只有 !mp settings 的 slot 行才會帶；team 尚未得知時為 null。 */
    public record Slot(String username, Long userId, String status, Team team) {

        public Slot(String username, Long userId, String status) {
            this(username, userId, status, null);
        }
    }
}
