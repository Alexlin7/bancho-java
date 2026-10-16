package idv.alexlin7.tw.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.irc.RecordingIrcSender;
import idv.alexlin7.tw.bancho.match.MatchState.Slot;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchClosed;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchCreated;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerJoined;

class MatchServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(2);

    private final RecordingIrcSender sender = new RecordingIrcSender();
    private final MatchService service = new MatchService(sender, Duration.ofMillis(200));

    @Test
    void createMatchSendsMakeCommandAndCompletesOnCreated() {
        CompletableFuture<MatchRoom> future = service.createMatch("測試房");

        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make 測試房");
        assertThat(future).isNotDone();

        service.onMatchEvent(new MatchCreated(122021273, "測試房"));

        MatchRoom room = future.join();
        assertThat(room.matchId()).isEqualTo(122021273);
        assertThat(sender.calls).contains("join #mp_122021273");
        assertThat(service.openRooms()).containsExactly(room);
    }

    @Test
    void createMatchMatchesRoomNameWithSpaces() {
        CompletableFuture<MatchRoom> future = service.createMatch("第一場 練習");

        service.onMatchEvent(new MatchCreated(5, "第一場 練習"));

        assertThat(future.join().matchId()).isEqualTo(5);
    }

    @Test
    void rejectsDuplicatePendingName() {
        service.createMatch("r");

        assertThatThrownBy(() -> service.createMatch("r")).isInstanceOf(IllegalStateException.class);
        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make r");
    }

    @Test
    void rejectsBlankOrMultilineName() {
        assertThatThrownBy(() -> service.createMatch(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createMatch("a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void createMatchTimesOut() {
        CompletableFuture<MatchRoom> future = service.createMatch("r");

        assertThat(future).failsWithin(WAIT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause().isInstanceOf(TimeoutException.class);
        assertThatCode(() -> service.createMatch("r")).doesNotThrowAnyException();
    }

    @Test
    void lateMatchCreatedStillRegistersRoom() {
        CompletableFuture<MatchRoom> future = service.createMatch("r");
        assertThat(future).failsWithin(WAIT).withThrowableOfType(ExecutionException.class);

        assertThatCode(() -> service.onMatchEvent(new MatchCreated(9, "r"))).doesNotThrowAnyException();

        assertThat(service.room(9)).isPresent();
        assertThat(sender.calls).contains("join #mp_9");
    }

    @Test
    void manualMatchCreatedIsTrackedAndJoined() {
        service.onMatchEvent(new MatchCreated(7, "手動"));

        assertThat(service.room(7)).hasValueSatisfying(room -> assertThat(room.state().name()).isEqualTo("手動"));
        assertThat(sender.calls).contains("join #mp_7");
    }

    @Test
    void eventsAreIsolatedPerRoom() {
        service.onMatchEvent(new MatchCreated(1, "a"));
        service.onMatchEvent(new PlayerJoined(1, "A", 1));
        service.onMatchEvent(new PlayerJoined(2, "B", 1));

        assertThat(service.room(1).orElseThrow().state().slots()).isEqualTo(Map.of(1, new Slot("A", null, null)));
        MatchState second = service.room(2).orElseThrow().state();
        assertThat(second.name()).isNull();
        assertThat(second.slots()).isEqualTo(Map.of(1, new Slot("B", null, null)));
        assertThat(sender.calls).doesNotContain("join #mp_2");
    }

    @Test
    void attachJoinsAndTracksRoom() {
        MatchRoom room = service.attach(42);

        assertThat(sender.calls).contains("join #mp_42");
        assertThat(service.openRooms()).containsExactly(room);
        assertThat(service.attach(42)).isSameAs(room);
    }

    @Test
    void openRoomsAreSortedById() {
        service.attach(30);
        service.attach(10);
        service.attach(20);

        assertThat(service.openRooms()).extracting(MatchRoom::matchId).containsExactly(10L, 20L, 30L);
    }

    @Test
    void matchClosedRemovesFromOpenRoomsAndParts() {
        service.onMatchEvent(new MatchCreated(1, "a"));

        service.onMatchEvent(new MatchClosed(1));

        assertThat(service.openRooms()).isEmpty();
        assertThat(service.room(1).orElseThrow().state().closed()).isTrue();
        assertThat(sender.calls).contains("part #mp_1");
    }
}
