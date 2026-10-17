package idv.alexlin7.tw.bancho.shell;

import java.io.PrintWriter;

import org.jline.reader.UserInterruptException;
import org.springframework.shell.jline.JLineInputProvider;

/**
 * Spring Shell 的讀取迴圈遇到 {@link UserInterruptException} 就會結束 shell（按一次 Ctrl+C 就離開）。
 * 這裡攔下來交給 {@link CtrlCGuard}：回傳空字串時 shell 會略過，回傳 null 時 shell 正常離開。
 */
final class InterruptAwareInputProvider extends JLineInputProvider {

    static final String HINT = "（再按一次 Ctrl+C 離開，或輸入 quit）";

    private final JLineInputProvider delegate;
    private final CtrlCGuard guard;

    InterruptAwareInputProvider(JLineInputProvider delegate, CtrlCGuard guard) {
        super(delegate.getLineReader());
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public String readInput() {
        try {
            String line = delegate.readInput();
            guard.reset();
            return line;
        } catch (UserInterruptException e) {
            return switch (guard.onInterrupt(e.getPartialLine())) {
                case CLEAR_LINE -> "";
                case WARN -> {
                    PrintWriter writer = getLineReader().getTerminal().writer();
                    writer.println(HINT);
                    writer.flush();
                    yield "";
                }
                case EXIT -> null;
            };
        }
    }
}
