package cc.alexlin7.bancho.irc;

/**
 * 原始 IRC 行的觀察者（例如記錄樣本）。拋出的例外只記 log，不影響連線。
 */
public interface IrcTrafficListener {

    /**
     * 在 connection thread 上，每收到一行（含 PING、數字回覆）在處理前呼叫；保留行尾空白。
     */
    default void received(String line) {
    }

    /**
     * 在 sender thread 上，出站佇列的一行成功寫出後呼叫，內容已套用 {@link OsuIrcClient#setOutboundRedactor}。
     * 登入的 PASS/NICK/USER、PONG 與 keepalive PING 不經出站佇列，不會通知。
     */
    default void sent(String line) {
    }
}
