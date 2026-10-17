package idv.alexlin7.tw.bancho.shell;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 決定 shell 提示中按 Ctrl+C 的反應：輸入到一半只清掉該行；空行第一次提示，
 * 在 {@code window} 內再按一次才離開，避免裁判誤按就讓 bot 斷線。
 * 只在 shell 的讀取 thread 上使用。
 */
final class CtrlCGuard {

    enum Action {
        /** 清掉輸入到一半的那一行。 */
        CLEAR_LINE,
        /** 提示再按一次會離開。 */
        WARN,
        /** 離開 shell。 */
        EXIT
    }

    private final Clock clock;
    private final Duration window;
    private Instant armedAt;

    CtrlCGuard(Clock clock, Duration window) {
        this.clock = clock;
        this.window = window;
    }

    Action onInterrupt(String partialLine) {
        if (partialLine != null && !partialLine.isBlank()) {
            reset();
            return Action.CLEAR_LINE;
        }
        Instant now = clock.instant();
        if (armedAt != null && !now.isAfter(armedAt.plus(window))) {
            reset();
            return Action.EXIT;
        }
        armedAt = now;
        return Action.WARN;
    }

    /** 送出一行指令後重新計算。 */
    void reset() {
        armedAt = null;
    }
}
