package idv.alexlin7.tw.bancho;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 業務邏輯的 listener（之後的 BanchoBot 事件解析、裁判流程從這裡接）。
 */
@Slf4j
@Service
public class OsuChatHandlerService implements IrcMessageListener {

    @Override
    public void onMessage(IrcMessage message) {
        log.debug("[osu! IRC] {} @ {}: {}", message.nick(), message.param(0), message.trailing());
    }
}
