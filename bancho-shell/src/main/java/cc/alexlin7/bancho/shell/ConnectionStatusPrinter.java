package cc.alexlin7.bancho.shell;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import cc.alexlin7.bancho.irc.IrcConnectionState;
import cc.alexlin7.bancho.irc.OsuIrcClient;

/**
 * 把登入結果印到 shell：終端機只顯示 WARN 以上的 log，不能只靠 log 告訴使用者登入成功或失敗。
 */
@Component
class ConnectionStatusPrinter {

    private final OsuIrcClient client;
    private final ShellOutput out;

    ConnectionStatusPrinter(OsuIrcClient client, ShellOutput out) {
        this.client = client;
        this.out = out;
        client.addConnectionListener(this::onStateChanged);
    }

    /** 早於 shell runner，提示會出現在第一個輸入行之前。 */
    @EventListener(ApplicationStartedEvent.class)
    void onApplicationStarted() {
        if (!client.hasCredentials()) {
            out.println("尚未登入，請先執行 login");
        }
    }

    private void onStateChanged(IrcConnectionState state) {
        if (state == IrcConnectionState.REGISTERED) {
            out.println("已登入 Bancho");
        } else if (state == IrcConnectionState.AUTH_FAILED) {
            out.println("登入失敗：帳號或 IRC 密碼錯誤，請重新 login");
        }
    }
}
