package idv.alexlin7.tw.bancho.shell;

import org.jline.reader.LineReader;
import org.springframework.beans.factory.ObjectProvider;
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
}
