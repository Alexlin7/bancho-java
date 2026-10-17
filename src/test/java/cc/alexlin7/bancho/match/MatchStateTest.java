package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cc.alexlin7.bancho.match.MatchState.Beatmap;
import cc.alexlin7.bancho.match.MatchState.ModSelection;
import cc.alexlin7.bancho.match.MatchState.Settings;
import cc.alexlin7.bancho.match.MatchState.Slot;
import cc.alexlin7.bancho.match.event.MatchEvent.BeatmapChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchClosed;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchCreated;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchStarted;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchAborted;
import cc.alexlin7.bancho.match.event.MatchEvent.ModsChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerInvited;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerLeft;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerMoved;
import cc.alexlin7.bancho.match.event.MatchEvent.SettingsChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.SlotStatus;
import cc.alexlin7.bancho.match.event.MatchEvent.AllPlayersReady;
import cc.alexlin7.bancho.match.event.MatchEvent.GameModeChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchFinished;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerFinished;

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
    void disabledAllModsClearsMods() {
        MatchState state = initial.apply(new ModsChanged(42, List.of("Hidden"), false))
                .apply(new ModsChanged(42, List.of(), false));
        assertThat(state.mods()).isEqualTo(new ModSelection(List.of(), false));
    }

    @Test
    void startedAndAbortedKeepState() {
        MatchState state = initial.apply(new PlayerJoined(42, "A", 1))
                .apply(new ModsChanged(42, List.of("Hidden"), false));
        assertThat(state.apply(new MatchStarted(42))).isEqualTo(state);
        assertThat(state.apply(new MatchAborted(42))).isEqualTo(state);
    }

    @Test
    void stateIsImmutable() {
        initial.apply(new PlayerJoined(1, "A", 1));

        assertThat(initial.slots()).isEmpty();
    }

    @Test
    void gameModeChangedUpdatesMode() {
        assertThat(initial.gameMode()).isNull();
        assertThat(initial.apply(new GameModeChanged(1, "CatchTheBeat")).gameMode()).isEqualTo("CatchTheBeat");
    }

    @Test
    void finishAndReadyEventsKeepState() {
        MatchState state = initial.apply(new PlayerFinished(1, "Alexlin86", 9245186, "PASSED"))
                .apply(new MatchFinished(1))
                .apply(new AllPlayersReady(1));
        assertThat(state).isEqualTo(initial);
    }
}
