# bancho-observability

`observability` 套件、`logback-spring.xml`、`application-otel.properties`：OpenTelemetry log appender 的安裝。目前沒有前端依賴它，留給日後需要匯出的前端（例如 Discord）。

## OpenTelemetry

- 預設不匯出任何資料；`otel` profile（`application-otel.properties`）把 log 與 metrics 用 HTTP 送到本機 collector 的 4318。
- log 透過 `logback-spring.xml` 的 `OpenTelemetryAppender` 送出，由 `OpenTelemetryAppenderInitializer` 接上 SDK。
- `opentelemetry-logback-appender-1.0` 的版本必須對應 Boot 管理的 OTel API 版本（Boot 4.1.1 → API 1.62.0 → appender 2.28.1-alpha），不可單獨升級。
- 依賴這個模組的前端要自己在 `application.properties` 關閉 metrics 匯出（`management.otlp.metrics.export.enabled=false`）；各模組測試的 `application.properties` 會遮蔽 main 的同名檔，同樣要關閉。

## protobuf 警告

OTLP metrics 的依賴（`micrometer-registry-otlp` → `opentelemetry-proto` → `protobuf-java`）會讓 JVM 在 stderr 印出 `sun.misc.Unsafe::arrayBaseOffset` 警告（JEP 498），不經過 logback，會插進互動輸入行。這是 shell 不依賴本模組的原因之一。日後依賴它的前端若遇到 JDK 預設改為 deny，要升級 protobuf 或加 `--sun-misc-unsafe-memory-access=allow`；不要用 `--illegal-native-access=allow` 這類全域開關。

## 測試

- `@SpringBootTest` 由 test 的 `ObservabilityTestApplication` 啟動。
- OTel service name 是 `bancho-test`，與前端的 `spring.application.name` 區隔。
