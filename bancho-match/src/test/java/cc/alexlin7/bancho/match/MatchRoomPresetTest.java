package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import cc.alexlin7.bancho.irc.RecordingIrcSender;
import cc.alexlin7.bancho.match.preset.MatchPreset;

class MatchRoomPresetTest {

    private final RecordingIrcSender sender = new RecordingIrcSender();
    private final MatchRoom room = new MatchRoom(123, "r", sender);

    @Test
    void appliesAllFieldsInOrder() {
        room.apply(new MatchPreset("0", "3", 2, "abc", List.of("HD", "HR"), 1502772L, List.of("A", "#2"),
                List.of("Alexlin86", "Friend"), null));

        assertThat(sender.calls).containsExactly(
                "msg #mp_123 !mp set 0 3 2",
                "msg #mp_123 !mp password abc",
                "msg #mp_123 !mp mods HD HR",
                "msg #mp_123 !mp map 1502772",
                "msg #mp_123 !mp addref A",
                "msg #mp_123 !mp addref #2",
                "msg #mp_123 !mp invite Alexlin86",
                "msg #mp_123 !mp invite Friend");
    }

    @Test
    void appliesOnlyFilledFields() {
        room.apply(new MatchPreset(null, null, null, null, List.of("HD"), null, null, null, null));

        assertThat(sender.calls).containsExactly("msg #mp_123 !mp mods HD");
    }

    @Test
    void presetInvitesAcceptProfilesAndPreserveUnicodeNames() {
        room.apply(new MatchPreset(null, null, null, null, null, null, null,
                List.of("https://osu.ppy.sh/users/2548278", "- Ｓ Ｈ -"), null));
        assertThat(sender.calls).containsExactly("msg #mp_123 !mp invite #2548278", "msg #mp_123 !mp invite - Ｓ Ｈ -");
    }

    @Test
    void invalidInviteInPresetSendsNothing() {
        MatchPreset preset = new MatchPreset("0", "3", 2, null, null, null, null,
                List.of("Alexlin86", "https://osu.ppy.sh/users/nope"), null);
        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void invalidLaterFieldSendsNothing() {
        MatchPreset preset = new MatchPreset("0", "3", 2, "abc", List.of("HD"), 1502772L, List.of("Two Words"), null, null);

        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void invalidModSendsNothing() {
        MatchPreset preset = new MatchPreset("0", "3", 2, null, List.of("H D"), null, null, null, null);

        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void concatenatedModsSendNothing() {
        MatchPreset preset = new MatchPreset("0", "3", 2, null, List.of("NFHD"), null, null, null, null);

        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void outOfRangeSettingsSendNothing() {
        MatchPreset preset = new MatchPreset("9", "3", 2, null, null, null, null, List.of("Alexlin86"), null);

        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void partialSetIsRejected() {
        assertThatThrownBy(() -> new MatchPreset("0", null, 2, null, null, null, null, null, null))
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
        MatchPreset preset = new MatchPreset("0", "3", 2, null, null, 1502772L, null, null, "9");
        assertThatThrownBy(() -> room.apply(preset)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void modeWithoutBeatmapIsRejected() {
        assertThatThrownBy(() -> new MatchPreset(null, null, null, null, null, null, null, null, "mania"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("mode 必須和 beatmap 一起設定");
    }

    // ---------------------------------------------------------------- 開房前檢查

    @Test
    void validatePresetRejectsInvalidModeWithPresetName() {
        MatchPreset preset = new MatchPreset(null, null, null, null, null, 1502772L, null, null, "9");
        assertThatThrownBy(() -> MatchRoom.validatePreset("bad", preset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("範本 bad 的 mode 無效：9（可用 osu、taiko、catch、mania 或 0–3）");
    }

    @Test
    void validatePresetRejectsConcatenatedMods() {
        MatchPreset preset = new MatchPreset(null, null, null, null, List.of("NFHD"), null, null, null, null);
        assertThatThrownBy(() -> MatchRoom.validatePreset("bad", preset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("範本 bad 的 mods 無效：mod 要用空白分開");
    }

    @Test
    void validatePresetRejectsInvalidInviteBeforeOpeningRoom() {
        MatchPreset preset = new MatchPreset("0", "3", 2, null, null, null, null, List.of("#0"), null);
        assertThatThrownBy(() -> MatchRoom.validatePreset("bad", preset))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("範本 bad 的 invite 無效：");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void validatePresetAcceptsValidPreset() {
        MatchPreset preset = new MatchPreset("0", "3", 2, null, List.of("HD", "HR", "Freemod"), 1502772L, null, null, "ctb");
        assertThatCode(() -> MatchRoom.validatePreset("ok", preset)).doesNotThrowAnyException();
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void validationRejectsEveryInvalidSetRangeBeforeOpening() {
        for (MatchPreset preset : List.of(
                new MatchPreset("5", "0", 2, null, null, null, null, null, null),
                new MatchPreset("0", "-1", 2, null, null, null, null, null, null),
                new MatchPreset("0", "3", 17, null, null, null, null, null, null))) {
            assertThatThrownBy(() -> MatchRoom.validatePreset("bad", preset))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("範本 bad");
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void invalidSizeIdentifiesThePresetWithoutRepeatingTheField() {
        MatchPreset preset = new MatchPreset("0", "3", 20, null, null, null, null, null, null);
        assertThatThrownBy(() -> MatchRoom.validatePreset("bad", preset))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("範本 bad 的 size 必須介於 1 到 16：20");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void validationRejectsInvalidRefereesBeforeOpening() {
        for (String referee : List.of("Minato Yukina", "#0", "https://example.com/users/42")) {
            MatchPreset preset = new MatchPreset("0", "3", 2, null, null, null, List.of(referee), null, null);
            assertThatThrownBy(() -> MatchRoom.validatePreset("bad", preset))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("範本 bad", "referees");
        }
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void presetRefereesUseSameProfileResolutionAsCommands() {
        room.apply(new MatchPreset("0", "3", 2, null, null, null,
                List.of("https://osu.ppy.sh/users/2548278/fruits", "#42"), null, null));
        assertThat(sender.calls).containsExactly("msg #mp_123 !mp set 0 3 2",
                "msg #mp_123 !mp addref #2548278", "msg #mp_123 !mp addref #42");
    }
}
