package idv.alexlin7.tw.bancho;

import java.io.InputStream;
import java.io.OutputStream;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.core.autoconfigure.TerminalCustomizer;

/**
 * 測試用：以空的 runner 取代 Spring Shell 的 {@code springShellApplicationRunner}
 * （Spring Shell 對這個名稱是 {@code @ConditionalOnMissingBean}）。
 * 放在 src/test 的根 package，所以每個 {@code @SpringBootTest} 都會掃描到。
 * 否則互動模式會讀 stdin，非互動模式沒有參數時會拋 CommandNotFoundException。
 * 另外把 JLine 換成不接系統主控台的 dumb terminal：測試 JVM 沒有真正的主控台，偵測系統 terminal 要花約 40 秒。
 */
@Configuration(proxyBeanMethods = false)
class TestShellRunnerConfiguration {

    @Bean
    ApplicationRunner springShellApplicationRunner() {
        return args -> {
            // 測試中不執行 shell
        };
    }

    @Bean
    TerminalCustomizer dumbTestTerminal() {
        return builder -> builder.system(false).dumb(true)
                .streams(InputStream.nullInputStream(), OutputStream.nullOutputStream());
    }
}
