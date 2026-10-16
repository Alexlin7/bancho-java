package idv.alexlin7.tw.bancho.irc;

import org.springframework.context.SmartLifecycle;

/**
 * 在 context refresh 完成時連線、關閉時送出 QUIT。
 * 不用 ApplicationReadyEvent：Spring Shell 以 ApplicationRunner 佔住 main thread，ready 事件要等 shell 結束才會發生。
 */
class OsuIrcLifecycle implements SmartLifecycle {

    private final OsuIrcClient client;
    private volatile boolean running;

    OsuIrcLifecycle(OsuIrcClient client) {
        this.client = client;
    }

    @Override
    public void start() {
        client.start();
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
