package cc.alexlin7.bancho.match;

import java.util.List;

import cc.alexlin7.bancho.irc.IrcMessage;
import cc.alexlin7.bancho.irc.IrcMessageListener;
import cc.alexlin7.bancho.match.BanchoBotParser.Result;
import cc.alexlin7.bancho.match.event.MatchEvent;
import cc.alexlin7.bancho.match.event.MatchEventListener;

import lombok.extern.slf4j.Slf4j;

/**
 * 把 BanchoBot 的訊息解析成 MatchEvent，依清單順序逐一通知每個 {@link MatchEventListener}。
 * 在 IRC dispatcher thread 上執行，listener 依訊息到達順序同步收到事件；某個 listener 拋出例外只記 log，不影響其他 listener。
 * {@link MatchService} 要放在第一個，其他 listener 收到事件時查到的才是更新後的房間。
 */
@Slf4j
public class BanchoBotListener implements IrcMessageListener {

    private final List<MatchEventListener> listeners;

    public BanchoBotListener(List<MatchEventListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public void onMessage(IrcMessage message) {
        switch (BanchoBotParser.parse(message)) {
            case Result.Parsed parsed -> {
                log.atInfo()
                        .addKeyValue("match.id", parsed.event().matchId())
                        .addKeyValue("match.event", parsed.event().getClass().getSimpleName())
                        .log("[match] {}", parsed.event());
                notifyListeners(parsed.event());
            }
            case Result.Unrecognized unrecognized -> log.atInfo()
                    .addKeyValue("match.id", unrecognized.matchId())
                    .addKeyValue("banchobot.text", unrecognized.text())
                    .log("[match] 未辨識的 BanchoBot 訊息：{}", unrecognized.text());
            case Result.Ignored _, Result.NotFromBanchoBot _ -> {
                // 刻意不處理
            }
        }
    }

    private void notifyListeners(MatchEvent event) {
        for (MatchEventListener listener : listeners) {
            try {
                listener.onMatchEvent(event);
            } catch (RuntimeException e) {
                log.error("[match] {} 處理事件時發生例外：{}", listener.getClass().getSimpleName(), event, e);
            }
        }
    }
}
