package cc.alexlin7.bancho.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

class LoggingPropertiesTest {

    /** 測試 classpath 的同名檔會遮蔽 main，因此直接載入正式設定並解析 placeholder。 */
    private static MockEnvironment mainProperties(Map<String, Object> env) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
            properties.load(reader);
        }
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("main", properties));
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        return environment;
    }

    @Test
    void shellHistoryHasItsOwnFile() throws IOException {
        assertThat(mainProperties(Map.of()).getProperty("spring.shell.history.name")).isEqualTo("bancho-history");
    }

    @Test
    void consoleShowsOnlyWarnings() throws IOException {
        assertThat(mainProperties(Map.of()).getProperty("logging.threshold.console")).isEqualTo("WARN");
    }

    @Test
    void logFileDefaultsToLogsDirectory() throws IOException {
        assertThat(mainProperties(Map.of()).getProperty("logging.file.name")).isEqualTo("logs/bancho.log");
    }

    @Test
    void logFileCanBeSetByEnvironmentVariable() throws IOException {
        assertThat(mainProperties(Map.of("BANCHO_LOG_FILE", "D:/bancho/bancho.log")).getProperty("logging.file.name"))
                .isEqualTo("D:/bancho/bancho.log");
    }
}
