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
import cc.alexlin7.bancho.match.event.MatchEvent;
import cc.alexlin7.bancho.match.event.MatchEvent.HostChanged;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerTeamChanged;

class MatchStateTest {

    // 下列隊伍、host 與部分設定案例直接建立合成事件，不驗證真實 IRC 格式。

    @Test
    void joinedWithTeamRecordsTeam() {
        assertThat(initial.apply(new PlayerJoined(42, "A", 1, Team.BLUE)).slots())
                .isEqualTo(Map.of(1, new Slot("A", null, null, Team.BLUE)));
    }

    @Test
    void movedKeepsTeamAndDetails() {
        MatchState state = initial.apply(new SlotStatus(42, 1, "Not Ready", 7L, "A"))
                .apply(new PlayerTeamChanged(42, "A", Team.RED))
                .apply(new PlayerMoved(42, "A", 3));

        assertThat(state.slots()).isEqualTo(Map.of(3, new Slot("A", 7L, "Not Ready", Team.RED)));
    }

    @Test
    void teamChangedUpdatesPlayerSlot() {
        MatchState state = initial.apply(new PlayerJoined(42, "A", 1, Team.BLUE))
                .apply(new PlayerTeamChanged(42, "A", Team.RED));

        assertThat(state.slots().get(1).team()).isEqualTo(Team.RED);
    }

    @Test
    void teamChangedForUnknownPlayerIsIgnored() {
        assertThat(initial.apply(new PlayerTeamChanged(42, "A", Team.RED))).isEqualTo(initial);
    }

    @Test
    void slotStatusKeepsSamePlayersTeamWhenMovingIntoOccupiedSlot() {
        MatchState state = initial.apply(new PlayerJoined(42, "A", 1, Team.RED))
                .apply(new PlayerJoined(42, "B", 2, Team.BLUE))
                .apply(new SlotStatus(42, 2, "Ready", 7L, "A"));

        assertThat(state.slots()).isEqualTo(Map.of(2, new Slot("A", 7L, "Ready", Team.RED)));
    }

    @Test
    void slotStatusDoesNotGivePreviousOccupantsTeamToNewPlayer() {
        MatchState state = initial.apply(new PlayerJoined(42, "A", 1, Team.BLUE))
                .apply(new SlotStatus(42, 1, "Ready", 8L, "B"));

        assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("B", 8L, "Ready", null)));
    }

    @Test
    void hostChangedRecordsHost() {
        assertThat(initial.host()).isNull();
        assertThat(initial.apply(new HostChanged(42, "A")).host()).isEqualTo("A");
    }

    @Test
    void hostLeavingClearsHost() {
        MatchState state = initial.apply(new PlayerJoined(42, "A", 1))
                .apply(new HostChanged(42, "A")).apply(new PlayerLeft(42, "A"));

        assertThat(state.host()).isNull();
        assertThat(state.slots()).isEmpty();
    }

    @Test
    void otherPlayerLeavingKeepsHost() {
        MatchState state = initial.apply(new HostChanged(42, "A"))
                .apply(new PlayerLeft(42, "B"));

        assertThat(state.host()).isEqualTo("A");
    }

    @Test
    void otherStateUpdatesKeepHost() {
        MatchState state = MatchState.initial(null).apply(new HostChanged(42, "A"));
        List<MatchEvent> events = List.of(new MatchCreated(42, "r"),
                new PlayerJoined(42, "B", 1, Team.RED), new PlayerMoved(42, "B", 2),
                new SlotStatus(42, 2, "Ready", 7L, "B"), new PlayerTeamChanged(42, "B", Team.BLUE),
                new SettingsChanged(42, 4, "TeamVs", "ScoreV2"),
                new BeatmapChanged(42, 1L, "map"), new ModsChanged(42, List.of("Hidden"), false),
                new GameModeChanged(42, "CatchTheBeat"), new MatchClosed(42));

        for (MatchEvent event : events) {
            state = state.apply(event);
            assertThat(state.host()).as("事件 %s 後保留 host", event).isEqualTo("A");
        }
    }

    @Test
    void partialSettingsKeepsKnownFields() {
        MatchState state = initial.apply(new SettingsChanged(42, 4, "TeamVs", "ScoreV2"))
                .apply(new SettingsChanged(42, null, "TagTeamVs", null));

        assertThat(state.settings()).isEqualTo(new Settings(4, "TagTeamVs", "ScoreV2"));
    }

    @Test
    void omittedTeamModeKeepsKnownMode() {
        MatchState state = initial.apply(new SettingsChanged(42, 4, "TeamVs", "ScoreV2"))
                .apply(new PlayerJoined(42, "A", 1, Team.BLUE))
                .apply(new SettingsChanged(42, 8, null, null));

        assertThat(state.settings()).isEqualTo(new Settings(8, "TeamVs", "ScoreV2"));
        assertThat(state.slots().get(1).team()).isEqualTo(Team.BLUE);
    }

    @Test
    void switchingToNonTeamModeClearsTeams() {
        MatchState state = initial.apply(new PlayerJoined(42, "A", 1, Team.BLUE))
                .apply(new SlotStatus(42, 1, "Ready", 7L, "A"))
                .apply(new PlayerJoined(42, "B", 2, Team.RED))
                .apply(new SettingsChanged(42, 2, "HeadToHead", "ScoreV2"));

        assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("A", 7L, "Ready"),
                2, new Slot("B", null, null)));
    }

    @Test
    void switchingToTeamModeKeepsUnknownTeams() {
        MatchState state = initial.apply(new SettingsChanged(42, 2, "HeadToHead", "ScoreV2"))
                .apply(new PlayerJoined(42, "A", 1))
                .apply(new SettingsChanged(42, null, "TeamVs", null));

        assertThat(state.slots().get(1).team()).isNull();
    }

    @Test
    void unknownTeamModeKeepsTeams() {
        MatchState state = initial.apply(new PlayerJoined(42, "A", 1, Team.BLUE))
                .apply(new SettingsChanged(42, 4, "FutureMode", "ScoreV2"));

        assertThat(state.slots().get(1).team()).isEqualTo(Team.BLUE);
    }

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
