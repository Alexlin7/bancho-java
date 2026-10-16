package idv.alexlin7.tw.bancho.match;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import idv.alexlin7.tw.bancho.match.event.MatchEvent;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.BeatmapChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchClosed;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchCreated;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchStarted;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchAborted;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.ModsChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerInvited;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerJoined;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerLeft;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerMoved;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.SettingsChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.SlotStatus;

/**
 * 房間狀態的不可變快照。每個事件透過 {@link #apply(MatchEvent)} 產生新的實例。
 *
 * @param name 房名；手動加入、未看過開房訊息的房間為 null
 */
public record MatchState(String name, boolean closed, Settings settings, Beatmap beatmap,
                         ModSelection mods, Map<Integer, Slot> slots) {

    public MatchState {
        slots = Map.copyOf(slots);
    }

    public static MatchState initial(String name) {
        return new MatchState(name, false, new Settings(null, null, null), null,
                new ModSelection(List.of(), false), Map.of());
    }

    MatchState apply(MatchEvent event) {
        return switch (event) {
            case MatchCreated created -> name == null ? withName(created.name()) : this;
            case MatchClosed _ -> new MatchState(name, true, settings, beatmap, mods, slots);
            case PlayerInvited _ -> this;
            case MatchStarted _, MatchAborted _ -> this;
            case PlayerJoined joined -> withPlayerAt(joined.slot(), new Slot(joined.username(), null, null));
            case PlayerMoved moved -> withPlayerAt(moved.slot(), new Slot(moved.username(), null, null));
            case PlayerLeft left -> withSlots(without(left.username()));
            case SlotStatus status -> withPlayerAt(status.slot(),
                    new Slot(status.username(), status.userId(), status.status()));
            case SettingsChanged changed -> new MatchState(name, closed,
                    new Settings(changed.size(), changed.teamMode(), changed.winCondition()), beatmap, mods, slots);
            case BeatmapChanged changed -> new MatchState(name, closed, settings,
                    new Beatmap(changed.beatmapId(), changed.title()), mods, slots);
            case ModsChanged changed -> new MatchState(name, closed, settings, beatmap,
                    new ModSelection(changed.mods(), changed.freeMod()), slots);
        };
    }

    private MatchState withName(String newName) {
        return new MatchState(newName, closed, settings, beatmap, mods, slots);
    }

    private MatchState withSlots(Map<Integer, Slot> newSlots) {
        return new MatchState(name, closed, settings, beatmap, mods, newSlots);
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

    /** userId、status 只有 !mp settings 的 slot 行才會帶，否則為 null。 */
    public record Slot(String username, Long userId, String status) {
    }
}
