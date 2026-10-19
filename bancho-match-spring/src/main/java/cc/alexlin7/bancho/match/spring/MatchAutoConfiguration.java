package cc.alexlin7.bancho.match.spring;

import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import cc.alexlin7.bancho.irc.IrcSender;
import cc.alexlin7.bancho.irc.OsuIrcClient;
import cc.alexlin7.bancho.irc.spring.OsuIrcAutoConfiguration;
import cc.alexlin7.bancho.match.BanchoBotListener;
import cc.alexlin7.bancho.match.BanchoRedaction;
import cc.alexlin7.bancho.match.MatchRoom;
import cc.alexlin7.bancho.match.MatchService;
import cc.alexlin7.bancho.match.event.MatchEvent;

/**
 * 註冊 {@link MatchService} 與 {@link BanchoBotListener}，並把解析出的 {@link MatchEvent} 以 Spring 事件發布，
 * 應用程式以 {@code @EventListener} 接收。事件在 IRC dispatcher thread 上同步發布，listener 依訊息到達順序執行。
 */
@AutoConfiguration(after = OsuIrcAutoConfiguration.class)
@ConditionalOnBean(IrcSender.class)
@EnableConfigurationProperties({MatchProperties.class, MatchPresetProperties.class})
public class MatchAutoConfiguration {

    /** 在前端能開房前檢查全部範本的設定範圍、模式、mod、裁判與邀請目標。 */
    @Bean
    InitializingBean matchPresetValidation(MatchPresetProperties presets) {
        return () -> presets.presets().forEach(MatchRoom::validatePreset);
    }

    /**
     * 遮蔽 log 與原始行觀察者中的 {@code !mp password}（不論從哪個指令送出）。在 bean 初始化時設定，
     * 早於 lifecycle 連線；IrcSender 不是 OsuIrcClient 時（例如自訂 sender）不處理。
     */
    @Bean
    InitializingBean matchOutboundRedaction(ObjectProvider<OsuIrcClient> client) {
        return () -> client.ifAvailable(c -> c.setOutboundRedactor(BanchoRedaction::redactOutbound));
    }

    /** MatchService 不是 IrcMessageListener，直接注入 IrcSender 不會形成循環依賴。 */
    @Bean
    @ConditionalOnMissingBean
    MatchService matchService(IrcSender sender, MatchProperties properties) {
        return new MatchService(sender, properties.createTimeout());
    }

    /**
     * MatchService 排在 Spring 事件之前，{@code @EventListener} 查到的才是更新後的房間。
     * 以 ObjectProvider 延後取得 MatchService：直接注入會形成 client → listener → MatchService → client 的循環。
     */
    @Bean
    BanchoBotListener banchoBotListener(ObjectProvider<MatchService> matchService, ApplicationEventPublisher publisher) {
        return new BanchoBotListener(List.of(
                event -> matchService.getObject().onMatchEvent(event),
                publisher::publishEvent));
    }
}
