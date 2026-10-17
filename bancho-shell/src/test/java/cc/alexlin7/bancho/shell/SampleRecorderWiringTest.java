package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 樣本檔預設開啟，寫在 log 檔的資料夾；測試的 application.properties 把它關掉，這裡再開回來驗證接線。
 */
@SpringBootTest(properties = {"bancho.samples.enabled=true", "logging.file.name=target/sample-wiring/bancho.log"})
class SampleRecorderWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void recorderWritesNextToLogFile() {
        SampleRecorder recorder = context.getBean(SampleRecorder.class);

        assertThat(recorder.directory()).isEqualTo(Path.of("target/sample-wiring").toAbsolutePath());
    }
}
