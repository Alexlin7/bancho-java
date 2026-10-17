package cc.alexlin7.bancho.match.event;

/**
 * 收到 {@link MatchEvent} 時的觀察者（Observer），由 {@code BanchoBotListener} 在 IRC dispatcher thread 上依訊息到達順序呼叫。
 * Spring 使用端不必實作此介面，以 {@code @EventListener} 接收 {@link MatchEvent} 即可。
 */
@FunctionalInterface
public interface MatchEventListener {

    void onMatchEvent(MatchEvent event);
}
