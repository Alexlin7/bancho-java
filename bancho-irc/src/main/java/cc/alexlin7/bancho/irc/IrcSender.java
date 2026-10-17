package cc.alexlin7.bancho.irc;

/**
 * 對外送訊的介面。業務層（例如 handler）依賴這個介面而不是 {@link OsuIrcClient}，
 * 需要時以 {@code ObjectProvider<IrcSender>} 注入，可避免與 client 形成循環依賴。
 */
public interface IrcSender {

    /**
     * 送出訊息；排入出站佇列，依速率限制送出，斷線期間會保留到重新連線後。
     */
    void sendMessage(String target, String message);

    /**
     * 送出訊息，實際送出後執行 {@code onSent}。
     * <p>
     * 預設實作在兩參數的 {@link #sendMessage(String, String)} 回傳後立即執行 {@code onSent}，
     * 只有同步送出的實作才符合「實際送出後通知」；以佇列或其他方式非同步送出的實作必須覆寫這個方法。
     */
    default void sendMessage(String target, String message, Runnable onSent) {
        sendMessage(target, message);
        onSent.run();
    }

    /**
     * 加入頻道；重新連線後會自動再加入。
     */
    void join(String channel);

    /**
     * 離開頻道，之後重新連線時不再加入。
     */
    void part(String channel);
}
