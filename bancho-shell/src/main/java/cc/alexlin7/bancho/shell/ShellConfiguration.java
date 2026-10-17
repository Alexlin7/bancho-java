package cc.alexlin7.bancho.shell;

import java.nio.file.Path;
import java.time.Clock;

import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cc.alexlin7.bancho.irc.OsuIrcClient;

@Configuration
class ShellConfiguration {

    /** 取代 Spring Shell 的 {@code @ConditionalOnMissingBean} history：帶房間密碼的指令不寫進歷史檔。 */
    @Bean
    History history() {
        return new PasswordSafeHistory();
    }

    /**
     * 有 JLine 的 LineReader 時用 printAbove，訊息會印在輸入行上方；沒有時（例如測試）直接印到 stdout。
     */
    @Bean
    @SuppressWarnings("java:S106")
    ShellOutput shellOutput(ObjectProvider<LineReader> lineReader) {
        return text -> {
            LineReader reader = lineReader.getIfAvailable();
            if (reader != null) {
                reader.printAbove(text);
            } else {
                System.out.println(text);
            }
        };
    }

    /**
     * 互動模式用 JLine 遮蔽輸入；非互動模式（測試、以參數執行單一指令）沒有可以提示的終端機，直接回錯誤。
     */
    @Bean
    SecretPrompt secretPrompt(ObjectProvider<LineReader> lineReader,
                              @Value("${spring.shell.interactive.enabled:true}") boolean interactive) {
        if (!interactive) {
            return prompt -> {
                throw new IllegalStateException("非互動模式無法輸入密碼");
            };
        }
        return prompt -> JLinePrompt.secret(lineReader.getObject()).read(prompt);
    }

    /** 同 {@link #secretPrompt}：非互動模式沒有可以提示的終端機。 */
    @Bean
    TextPrompt textPrompt(ObjectProvider<LineReader> lineReader,
                          @Value("${spring.shell.interactive.enabled:true}") boolean interactive) {
        if (!interactive) {
            return prompt -> {
                throw new IllegalStateException("非互動模式無法提示輸入，請在互動 shell 中使用");
            };
        }
        return prompt -> JLinePrompt.text(lineReader.getObject()).read(prompt);
    }

    /**
     * 把 BanchoBot 與 Bancho 系統訊息寫進 log 資料夾的 {@code samples-<日期>.irc}，供測試者回傳補樣本。
     * 預設開啟；{@code bancho.samples.enabled=false} 關閉（測試就是這樣設定）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "bancho.samples", name = "enabled", havingValue = "true", matchIfMissing = true)
    SampleRecorder sampleRecorder(OsuIrcClient client,
                                  @Value("${logging.file.name:logs/bancho.log}") String logFile) {
        Path directory = Path.of(logFile).toAbsolutePath().getParent();
        SampleRecorder recorder = new SampleRecorder(directory, Clock.systemDefaultZone());
        client.addTrafficListener(recorder);
        return recorder;
    }
}
