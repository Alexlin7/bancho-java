package idv.alexlin7.tw.bancho;

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
     * 加入頻道；重新連線後會自動再加入。
     */
    void join(String channel);

    /**
     * 離開頻道，之後重新連線時不再加入。
     */
    void part(String channel);
}
