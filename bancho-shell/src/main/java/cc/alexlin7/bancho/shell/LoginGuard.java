package cc.alexlin7.bancho.shell;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import cc.alexlin7.bancho.irc.IrcConnectionState;
import cc.alexlin7.bancho.irc.OsuIrcClient;

/**
 * 會送 IRC 的指令只在登入完成（收到 001）後放行。連線中也擋下：帳密若隨後被拒，留在佇列裡的指令會在重新登入後意外送出。
 */
@Component
class LoginGuard {

    private final Supplier<IrcConnectionState> state;

    @Autowired
    LoginGuard(OsuIrcClient client) {
        this(client::state);
    }

    LoginGuard(Supplier<IrcConnectionState> state) {
        this.state = state;
    }

    /**
     * @return 已登入時為 null，否則為要回給使用者的錯誤訊息
     */
    String check() {
        return switch (state.get()) {
            case REGISTERED -> null;
            case IDLE, AUTH_FAILED -> "錯誤：請先 login";
            case CONNECTING -> "錯誤：正在連線 Bancho，請等登入完成";
            case CLOSED -> "錯誤：連線已關閉";
        };
    }
}
