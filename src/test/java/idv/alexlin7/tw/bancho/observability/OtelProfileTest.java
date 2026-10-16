package idv.alexlin7.tw.bancho.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;

@SpringBootTest
@ActiveProfiles("otel")
class OtelProfileTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void createsLogAndMetricExporters() {
        assertThat(context.getBeansOfType(LogRecordExporter.class)).isNotEmpty();
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).isNotEmpty();
    }

    @Test
    void usesTestServiceName() {
        assertThat(context.getBean(Environment.class).getProperty("spring.application.name")).isEqualTo("bancho-test");
    }
}
