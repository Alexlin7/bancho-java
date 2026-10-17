package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import cc.alexlin7.bancho.irc.RecordingIrcSender;
import cc.alexlin7.bancho.match.MatchState.Slot;
import cc.alexlin7.bancho.match.PendingMatch.Status;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchClosed;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchCreated;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;

class MatchServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration TIMEOUT = Duration.ofMillis(200);

    private final RecordingIrcSender sender = new RecordingIrcSender();
    private final MatchService service = new MatchService(sender, TIMEOUT);

    /** 只記錄 onSent、不執行，用來模擬還在出站佇列裡（例如斷線中）的訊息。 */
    static class HeldSender extends RecordingIrcSender {

        final List<Runnable> held = new CopyOnWriteArrayList<>();

        @Override
        public void sendMessage(String target, String message, Runnable onSent) {
            sendMessage(target, message);
            held.add(onSent);
        }
    }

    /** 等到確定超過逾時（以 Awaitility 的輪詢延遲取代 Thread.sleep）。 */
    private static void waitPast(Duration duration) {
        await().pollDelay(duration.multipliedBy(3)).atMost(duration.multipliedBy(3).plus(WAIT)).until(() -> true);
    }

    @Test
    void createMatchSendsMakeCommandAndCompletesOnCreated() {
        MatchCreation creation = service.createMatch("測試房");

        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make 測試房");
        assertThat(creation.room()).isNotDone();

        service.onMatchEvent(new MatchCreated(122021273, "測試房"));

        MatchRoom room = creation.room().join();
        assertThat(room.matchId()).isEqualTo(122021273);
        assertThat(sender.calls).contains("join #mp_122021273");
        assertThat(service.openRooms()).containsExactly(room);
        assertThat(service.usedSlots()).isEqualTo(1);
    }

    @Test
    void createMatchMatchesRoomNameWithSpaces() {
        MatchCreation creation = service.createMatch("第一場 練習");

        service.onMatchEvent(new MatchCreated(5, "第一場 練習"));

        assertThat(creation.room().join().matchId()).isEqualTo(5);
    }

    @Test
    void rejectsDuplicatePendingName() {
        service.createMatch("r");

        assertThatIllegalStateException().isThrownBy(() -> service.createMatch("r"))
                .withMessage("已有同名的開房請求在等待中：r");
        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make r");
    }

    @Test
    void rejectsBlankOrMultilineName() {
        assertThatThrownBy(() -> service.createMatch(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createMatch("a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void timeoutTurnsUnknownAndKeepsSlot() {
        MatchCreation creation = service.createMatch("r");

        assertThat(creation.unknown()).succeedsWithin(WAIT);
        assertThat(creation.room()).isNotDone();
        assertThat(service.pendingMatches()).containsExactly(new PendingMatch("r", Status.UNKNOWN));
        assertThat(service.usedSlots()).isEqualTo(1);
    }

    @Test
    void unsentRequestNeverTimesOut() {
        HeldSender held = new HeldSender();
        MatchService service = new MatchService(held, TIMEOUT);
        MatchCreation creation = service.createMatch("r");

        waitPast(TIMEOUT);

        assertThat(creation.unknown()).isNotDone();
        assertThat(service.pendingMatches()).containsExactly(new PendingMatch("r", Status.CREATING));
        assertThat(service.drop("r")).isFalse();

        held.held.forEach(Runnable::run);
        assertThat(creation.unknown()).succeedsWithin(WAIT);
    }

    @Test
    void replyBeforeOnSentStaysSuccessful() {
        HeldSender held = new HeldSender();
        MatchService service = new MatchService(held, TIMEOUT);
        MatchCreation creation = service.createMatch("r");

        service.onMatchEvent(new MatchCreated(3, "r"));
        held.held.forEach(Runnable::run);
        waitPast(TIMEOUT);

        assertThat(creation.room()).isCompleted();
        assertThat(creation.unknown()).isNotDone();
        assertThat(service.pendingMatches()).isEmpty();
        assertThat(service.usedSlots()).isEqualTo(1);
    }

    @Test
    void staleTimerDoesNotAffectNewSameNameRequest() {
        HeldSender held = new HeldSender();
        MatchService service = new MatchService(held, TIMEOUT);
        service.createMatch("r");
        held.held.removeFirst().run();
        service.onMatchEvent(new MatchCreated(3, "r"));
        service.onMatchEvent(new MatchClosed(3));

        MatchCreation second = service.createMatch("r");
        waitPast(TIMEOUT);

        assertThat(second.unknown()).isNotDone();
        assertThat(service.pendingMatches()).containsExactly(new PendingMatch("r", Status.CREATING));
    }

    @Test
    void unknownBlocksSameNameOnly() {
        service.createMatch("r").unknown().join();

        assertThatIllegalStateException().isThrownBy(() -> service.createMatch("r"))
                .withMessage("同名的開房請求結果未知：r；確認遊戲內沒有這間房後用 drop 釋放");
        assertThatCode(() -> service.createMatch("s")).doesNotThrowAnyException();
        assertThat(sender.calls).containsExactly("msg BanchoBot !mp make r", "msg BanchoBot !mp make s");
    }

    @Test
    void lateReplyCompletesUnknownRequest() {
        MatchCreation creation = service.createMatch("r");
        creation.unknown().join();

        service.onMatchEvent(new MatchCreated(7, "r"));

        assertThat(creation.room().join().matchId()).isEqualTo(7);
        assertThat(service.pendingMatches()).isEmpty();
        assertThat(service.usedSlots()).isEqualTo(1);
        assertThat(sender.calls).contains("join #mp_7");
    }

    @Test
    void fifthRoomRejectedLocally() {
        service.attach(1);
        service.createMatch("a");
        service.onMatchEvent(new MatchCreated(2, "a"));
        service.createMatch("b");
        service.onMatchEvent(new MatchCreated(3, "b"));
        service.createMatch("c");
        assertThat(service.usedSlots()).isEqualTo(MatchService.MAX_ROOMS);
        int before = sender.calls.size();

        assertThatIllegalStateException().isThrownBy(() -> service.createMatch("d")).withMessage("名額已滿（4/4）");
        assertThat(sender.calls).hasSize(before);
    }

    @Test
    void closedRoomReleasesSlot() {
        service.attach(1);
        service.attach(2);
        service.attach(3);
        service.attach(4);

        service.onMatchEvent(new MatchClosed(1));

        assertThat(service.usedSlots()).isEqualTo(3);
        assertThatCode(() -> service.createMatch("r")).doesNotThrowAnyException();
    }

    @Test
    void dropReleasesSlotButNotTheName() {
        MatchCreation creation = service.createMatch("r");
        creation.unknown().join();

        assertThat(service.drop(" r ")).isTrue();

        assertThat(creation.room()).isCancelled();
        assertThat(service.usedSlots()).isZero();
        assertThat(service.pendingMatches()).isEmpty();
        assertThatIllegalStateException().isThrownBy(() -> service.createMatch("r"))
                .withMessage("此房名已 drop，本次執行不能再用，請換一個房名：r");
    }

    @Test
    void dropIgnoresCreatingRequest() {
        HeldSender held = new HeldSender();
        MatchService service = new MatchService(held, TIMEOUT);
        service.createMatch("r");

        assertThat(service.drop("r")).isFalse();
        assertThat(service.drop("nope")).isFalse();
    }

    @Test
    void replyAfterDropRegistersRoomOnce() {
        service.createMatch("r").unknown().join();
        service.drop("r");

        service.onMatchEvent(new MatchCreated(9, "r"));

        assertThat(service.openRooms()).extracting(MatchRoom::matchId).containsExactly(9L);
        assertThat(service.usedSlots()).isEqualTo(1);
        assertThat(sender.calls).contains("join #mp_9");
    }

    @Test
    void passivelyTrackedRoomCountsTowardSlots() {
        service.onMatchEvent(new PlayerJoined(8, "A", 1));

        assertThat(service.usedSlots()).isEqualTo(1);
    }

    @Test
    void replyDuringTimeoutTransitionIsClassifiedAsLate() throws Exception {
        CountDownLatch inGap = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 計時器把請求改成結果未知之後、完成 unknown() 之前會記這筆 log；在這裡把計時器 thread 停住，重現回覆剛好抵達的時機
        AppenderBase<ILoggingEvent> gate = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().contains("開房結果未知")) {
                    inGap.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(MatchService.class);
        gate.start();
        logger.addAppender(gate);
        try {
            MatchCreation creation = service.createMatch("r");
            AtomicReference<Boolean> unknownDoneWhenRoomCompleted = new AtomicReference<>();
            creation.room().whenComplete((room, error) -> unknownDoneWhenRoomCompleted.set(creation.unknown().isDone()));
            assertThat(inGap.await(2, TimeUnit.SECONDS)).isTrue();

            service.onMatchEvent(new MatchCreated(7, "r"));

            // 已經判定為結果未知的請求，前端看到成功時也必須看到 unknown 已完成，才會當成晚到的回覆處理
            assertThat(unknownDoneWhenRoomCompleted.get()).isTrue();
        } finally {
            release.countDown();
            logger.detachAppender(gate);
        }
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
