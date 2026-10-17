package idv.alexlin7.tw.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.irc.RecordingIrcSender;
import idv.alexlin7.tw.bancho.match.preset.MatchPreset;

class MatchRoomPresetTest {

    private final RecordingIrcSender sender = new RecordingIrcSender();
    private final MatchRoom room = new MatchRoom(123, "r", sender);

    @Test
    void appliesAllFieldsInOrder() {
        room.apply(new MatchPreset(0, 3, 2, "abc", List.of("HD", "HR"), 1502772L, List.of("Ref1"),
                List.of("Alexlin86", "Friend"), null));

        assertThat(sender.calls).containsExactly(
                "msg #mp_123 !mp set 0 3 2",
                "msg #mp_123 !mp password abc",
                "msg #mp_123 !mp mods HD HR",
                "msg #mp_123 !mp map 1502772",
                "msg #mp_123 !mp addref Ref1",
                "msg #mp_123 !mp invite Alexlin86",
                "msg #mp_123 !mp invite Friend");
    }

    @Test
    void appliesOnlyFilledFields() {
        room.apply(new MatchPreset(null, null, null, null, List.of("HD"), null, null, null, null));

        assertThat(sender.calls).containsExactly("msg #mp_123 !mp mods HD");
    }

    @Test
    void invalidLaterFieldSendsNothing() {
        MatchPreset preset = new MatchPreset(0, 3, 2, "abc", List.of("HD"), 1502772L, List.of("Two Words"), null, null);

        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void invalidModSendsNothing() {
        MatchPreset preset = new MatchPreset(0, 3, 2, null, List.of("H D"), null, null, null, null);

        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void concatenatedModsSendNothing() {
        MatchPreset preset = new MatchPreset(0, 3, 2, null, List.of("NFHD"), null, null, null, null);

        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void outOfRangeSettingsSendNothing() {
        MatchPreset preset = new MatchPreset(9, 3, 2, null, null, null, null, List.of("Alexlin86"), null);

        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void partialSetIsRejected() {
        assertThatThrownBy(() -> new MatchPreset(0, null, 2, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("team-mode、score-mode、size 必須一起設定");
    }

    @Test
    void appliesModeWithBeatmap() {
        room.apply(new MatchPreset(null, null, null, null, null, 1502772L, null, null, "mania"));
        assertThat(sender.calls).containsExactly("msg #mp_123 !mp map 1502772 3");
    }

    @Test
    void invalidModeSendsNothing() {
        MatchPreset preset = new MatchPreset(0, 3, 2, null, null, 1502772L, null, null, "9");
        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void modeWithoutBeatmapIsRejected() {
        assertThatThrownBy(() -> new MatchPreset(null, null, null, null, null, null, null, null, "mania"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("mode 必須和 beatmap 一起設定");
    }
}
