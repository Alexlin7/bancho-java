package idv.alexlin7.tw.bancho.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import ch.qos.logback.classic.LoggerContext;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@SpringBootTest
class ObservabilityConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void exportsNothingWithoutOtelProfile() {
        assertThat(context.getBeansOfType(LogRecordExporter.class)).isEmpty();
        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).isEmpty();
    }

    @Test
    void installsOpenTelemetryAppenderOnRootLogger() {
        var root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);

        assertThat(root.getAppender("OTEL")).isInstanceOf(OpenTelemetryAppender.class);
    }
}
