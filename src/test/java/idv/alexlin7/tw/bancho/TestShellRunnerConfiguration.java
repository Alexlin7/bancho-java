package idv.alexlin7.tw.bancho;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 測試用：以空的 runner 取代 Spring Shell 的 {@code springShellApplicationRunner}
 * （Spring Shell 對這個名稱是 {@code @ConditionalOnMissingBean}）。
 * 放在 src/test 的根 package，所以每個 {@code @SpringBootTest} 都會掃描到。
 * 否則互動模式會讀 stdin，非互動模式沒有參數時會拋 CommandNotFoundException。
 */
@Configuration(proxyBeanMethods = false)
class TestShellRunnerConfiguration {

    @Bean
    ApplicationRunner springShellApplicationRunner() {
        return args -> {
            // 測試中不執行 shell
        };
    }
}
