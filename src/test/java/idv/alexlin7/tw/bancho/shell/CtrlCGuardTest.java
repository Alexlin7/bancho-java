package idv.alexlin7.tw.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import idv.alexlin7.tw.bancho.shell.CtrlCGuard.Action;

class CtrlCGuardTest {

    /** 測試用可前進的時鐘。 */
    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-17T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final CtrlCGuard guard = new CtrlCGuard(clock, Duration.ofSeconds(2));

    @Test
    void partialLineClearsWithoutArming() {
        assertThat(guard.onInterrupt("abc")).isEqualTo(Action.CLEAR_LINE);
        assertThat(guard.onInterrupt("")).isEqualTo(Action.WARN);
    }

    @Test
    void firstEmptyInterruptWarns() {
        assertThat(guard.onInterrupt("")).isEqualTo(Action.WARN);
    }

    @Test
    void secondWithinWindowExits() {
        guard.onInterrupt("");
        clock.advance(Duration.ofMillis(1900));
        assertThat(guard.onInterrupt("")).isEqualTo(Action.EXIT);
    }

    @Test
    void secondExactlyAtWindowExits() {
        guard.onInterrupt("");
        clock.advance(Duration.ofSeconds(2));
        assertThat(guard.onInterrupt("")).isEqualTo(Action.EXIT);
    }

    @Test
    void afterWindowWarnsAgain() {
        guard.onInterrupt("");
        clock.advance(Duration.ofMillis(2100));
        assertThat(guard.onInterrupt("")).isEqualTo(Action.WARN);
    }

    @Test
    void resetDisarms() {
        guard.onInterrupt("");
        guard.reset();
        assertThat(guard.onInterrupt("")).isEqualTo(Action.WARN);
    }
}
