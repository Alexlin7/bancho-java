package cc.alexlin7.bancho.irc;

/**
 * 收到 PRIVMSG 時的觀察者（Observer）。所有實作此介面的 Spring bean 會自動註冊到 {@link OsuIrcClient}，
 * 由同一條 dispatcher thread 依到達順序逐一呼叫；某個 listener 拋出例外不影響其他 listener。
 * 需要回話時請注入 {@code ObjectProvider<IrcSender>}，直接注入 client 會形成循環依賴。
 */
@FunctionalInterface
public interface IrcMessageListener {

    void onMessage(IrcMessage message);
}
