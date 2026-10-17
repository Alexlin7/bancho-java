package cc.alexlin7.bancho.shell;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import cc.alexlin7.bancho.irc.IrcConnectionState;

/**
 * 測試用：{@code @SpringBootTest} 不連線（{@code osu.irc.auto-start=false}），client 一直是 IDLE，
 * 真正的 {@link LoginGuard} 會擋下所有送訊指令。這裡讓經由 parser 測指令的測試視為已登入；
 * 登入流程本身由 {@code LoginCommandsTest}、{@code LoginGuardTest} 測試。
 */
@Configuration(proxyBeanMethods = false)
class TestLoginGuardConfiguration {

    @Bean
    @Primary
    LoginGuard registeredLoginGuard() {
        return new LoginGuard(() -> IrcConnectionState.REGISTERED);
    }
}
