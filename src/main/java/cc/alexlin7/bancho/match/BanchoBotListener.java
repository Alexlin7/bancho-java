package cc.alexlin7.bancho.match;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import cc.alexlin7.bancho.irc.IrcMessage;
import cc.alexlin7.bancho.irc.IrcMessageListener;
import cc.alexlin7.bancho.match.BanchoBotParser.Result;

import lombok.extern.slf4j.Slf4j;

/**
 * 把 BanchoBot 的訊息解析成 MatchEvent，以 Spring 事件發佈。
 * 在 IRC dispatcher thread 上執行，所以 {@code @EventListener} 會依訊息到達順序同步收到事件。
 */
@Slf4j
@Component
class BanchoBotListener implements IrcMessageListener {

    private final ApplicationEventPublisher publisher;

    BanchoBotListener(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onMessage(IrcMessage message) {
        switch (BanchoBotParser.parse(message)) {
            case Result.Parsed parsed -> {
                log.atInfo()
                        .addKeyValue("match.id", parsed.event().matchId())
                        .addKeyValue("match.event", parsed.event().getClass().getSimpleName())
                        .log("[match] {}", parsed.event());
                publisher.publishEvent(parsed.event());
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
}
