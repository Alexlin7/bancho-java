package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cc.alexlin7.bancho.irc.IrcMessage;
import cc.alexlin7.bancho.match.preset.MatchPreset;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerInvited;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerMoved;
import cc.alexlin7.bancho.match.event.MatchEvent.SlotStatus;
import cc.alexlin7.bancho.match.spring.MatchPresetProperties;

class KnownUsersTest {

    private final KnownUsers users = new KnownUsers(new MatchPresetProperties(Map.of()));

    private void receive(String rawLine) {
        users.onMessage(IrcMessage.parse(rawLine).orElseThrow());
    }

    @Test
    void collectsNamesFromRoomEventsAndPrivateMessages() {
        users.onMatchEvent(new PlayerJoined(1, "Alexlin86", 1));
        users.onMatchEvent(new PlayerInvited(1, "Some Player"));
        receive(":Friend!cho@ppy.sh PRIVMSG test_user :hi");

        assertThat(users.all()).containsExactly("Alexlin86", "Friend", "Some Player");
    }

    @Test
    void movedAndSlotStatusNamesAreCollected() {
        users.onMatchEvent(new PlayerMoved(1, "Mover", 2));
        users.onMatchEvent(new SlotStatus(1, 1, "Ready", 1, "Ready Player"));

        assertThat(users.all()).containsExactly("Mover", "Ready Player");
    }

    @Test
    void ignoresBanchoBotAndChannelSpeakers() {
        receive(":BanchoBot!cho@ppy.sh PRIVMSG test_user :Created the tournament match https://osu.ppy.sh/mp/1 r");
        receive(":Stranger!cho@ppy.sh PRIVMSG #mp_1 :hello");

        assertThat(users.all()).isEmpty();
    }

    @Test
    void presetNamesAreKnownFromStart() {
        KnownUsers withPresets = new KnownUsers(new MatchPresetProperties(Map.of("p",
                new MatchPreset(null, null, null, null, null, null, List.of("Ref1"), List.of("Invitee"), null))));

        assertThat(withPresets.all()).containsExactly("Invitee", "Ref1");
    }

    @Test
    void namesAreUnique() {
        users.onMatchEvent(new PlayerJoined(1, "A", 1));
        users.onMatchEvent(new PlayerMoved(1, "A", 2));

        assertThat(users.all()).containsExactly("A");
    }
}
