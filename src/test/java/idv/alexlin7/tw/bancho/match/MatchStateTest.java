package idv.alexlin7.tw.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.match.MatchState.Beatmap;
import idv.alexlin7.tw.bancho.match.MatchState.ModSelection;
import idv.alexlin7.tw.bancho.match.MatchState.Settings;
import idv.alexlin7.tw.bancho.match.MatchState.Slot;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.BeatmapChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchClosed;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchCreated;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.ModsChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerInvited;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerJoined;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerLeft;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerMoved;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.SettingsChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.SlotStatus;

class MatchStateTest {

    private final MatchState initial = MatchState.initial("r");

    @Test
    void initialStateIsEmpty() {
        assertThat(initial.name()).isEqualTo("r");
        assertThat(initial.closed()).isFalse();
        assertThat(initial.settings()).isEqualTo(new Settings(null, null, null));
        assertThat(initial.beatmap()).isNull();
        assertThat(initial.mods()).isEqualTo(new ModSelection(List.of(), false));
        assertThat(initial.slots()).isEmpty();
    }

    @Test
    void playerJoinedOccupiesSlot() {
        assertThat(initial.apply(new PlayerJoined(1, "A", 1)).slots())
                .isEqualTo(Map.of(1, new Slot("A", null, null)));
    }

    @Test
    void playerMovedLeavesPreviousSlot() {
        MatchState state = initial.apply(new PlayerJoined(1, "A", 1)).apply(new PlayerMoved(1, "A", 3));

        assertThat(state.slots()).isEqualTo(Map.of(3, new Slot("A", null, null)));
    }

    @Test
    void playerLeftRemovesPlayer() {
        MatchState state = initial.apply(new PlayerJoined(1, "A", 1)).apply(new PlayerLeft(1, "A"));

        assertThat(state.slots()).isEmpty();
    }

    @Test
    void joiningOccupiedSlotReplacesPreviousOccupant() {
        MatchState state = initial.apply(new PlayerJoined(1, "A", 1)).apply(new PlayerJoined(1, "B", 1));

        assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("B", null, null)));
    }

    @Test
    void slotStatusOverwritesSlotWithDetails() {
        MatchState state = initial.apply(new PlayerJoined(1, "A", 2))
                .apply(new SlotStatus(1, 1, "Not Ready", 4841753, "A"));

        assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("A", 4841753L, "Not Ready")));
    }

    @Test
    void settingsBeatmapAndModsAreRecorded() {
        MatchState state = initial.apply(new SettingsChanged(1, 2, "HeadToHead", "ScoreV2"))
                .apply(new BeatmapChanged(1, 1502772, "Asriel - Kegare Naki Yume"))
                .apply(new ModsChanged(1, List.of("Hidden", "HardRock"), false));

        assertThat(state.settings()).isEqualTo(new Settings(2, "HeadToHead", "ScoreV2"));
        assertThat(state.beatmap()).isEqualTo(new Beatmap(1502772, "Asriel - Kegare Naki Yume"));
        assertThat(state.mods()).isEqualTo(new ModSelection(List.of("Hidden", "HardRock"), false));
    }

    @Test
    void matchClosedMarksClosed() {
        MatchState joined = initial.apply(new PlayerJoined(1, "A", 1));
        MatchState closed = joined.apply(new MatchClosed(1));

        assertThat(closed.closed()).isTrue();
        assertThat(closed.slots()).isEqualTo(joined.slots());
    }

    @Test
    void invitedDoesNotChangeSlots() {
        assertThat(initial.apply(new PlayerInvited(1, "A")).slots()).isEmpty();
    }

    @Test
    void createdFillsUnknownName() {
        assertThat(MatchState.initial(null).apply(new MatchCreated(1, "測試房")).name()).isEqualTo("測試房");
    }

    @Test
    void stateIsImmutable() {
        initial.apply(new PlayerJoined(1, "A", 1));

        assertThat(initial.slots()).isEmpty();
    }
}
