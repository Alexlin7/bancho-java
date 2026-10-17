package cc.alexlin7.bancho.irc;

/**
 * 連線狀態改變時收到通知。在 connection thread（{@code start}、{@code login}、{@code close} 時為呼叫端 thread）上呼叫，
 * 拋出的例外只記 log。
 */
@FunctionalInterface
public interface IrcConnectionListener {

    void onStateChanged(IrcConnectionState state);
}
