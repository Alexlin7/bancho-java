package cc.alexlin7.bancho.shell;

import org.jline.reader.LineReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ShellConfiguration {

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
        return prompt -> new JLineSecretPrompt(lineReader.getObject()).read(prompt);
    }
}
