package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import cc.alexlin7.bancho.irc.IrcConnectionState;

class LoginGuardTest {

    private static String check(IrcConnectionState state) {
        return new LoginGuard(() -> state).check();
    }

    @Test
    void onlyRegisteredPasses() {
        assertThat(check(IrcConnectionState.REGISTERED)).isNull();
        assertThat(check(IrcConnectionState.IDLE)).isEqualTo("錯誤：請先 login");
        assertThat(check(IrcConnectionState.AUTH_FAILED)).isEqualTo("錯誤：請先 login");
        assertThat(check(IrcConnectionState.CONNECTING)).isEqualTo("錯誤：正在連線 Bancho，請等登入完成");
        assertThat(check(IrcConnectionState.CLOSED)).isEqualTo("錯誤：連線已關閉");
    }
}
