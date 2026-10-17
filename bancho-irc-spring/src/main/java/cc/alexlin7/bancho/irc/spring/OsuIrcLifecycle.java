package cc.alexlin7.bancho.irc.spring;

import org.springframework.context.SmartLifecycle;

import lombok.extern.slf4j.Slf4j;

import cc.alexlin7.bancho.irc.OsuIrcClient;

/**
 * 在 context refresh 完成時連線、關閉時送出 QUIT。
 * 沒有帳密時（{@code osu.irc.deferred-login}）不連線，由應用程式稍後呼叫 {@code login}。
 * 不用 ApplicationReadyEvent：Spring Shell 以 ApplicationRunner 佔住 main thread，ready 事件要等 shell 結束才會發生。
 */
@Slf4j
class OsuIrcLifecycle implements SmartLifecycle {

    private final OsuIrcClient client;
    private volatile boolean running;

    OsuIrcLifecycle(OsuIrcClient client) {
        this.client = client;
    }

    @Override
    public void start() {
        if (client.hasCredentials()) {
            client.start();
        } else {
            log.info("[osu! IRC] 未設定帳密，等待 login");
        }
        running = true;
    }

    @Override
    public void stop() {
        client.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
