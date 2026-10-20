# bancho-irc-spring

`irc.spring` 套件：`osu.irc.*` 設定綁定與 auto-configuration。依賴 `spring-boot-autoconfigure` 而不是 starter。

## 設定（`osu.irc.*`）

- `host` / `port` 預設 `cho.ppy.sh` / `6667`。
- `username`、`password` 必填，空白或完全沒設定時建立 client 就失敗（訊息為 `osu.irc.username 不得空白`）。例外是 `deferred-login=true` 且帳密都空白：client 照常建立但不連線（狀態 `IDLE`，lifecycle 記 INFO「未設定帳密，等待 login」），由應用程式稍後呼叫 `OsuIrcClient.login`。`OsuIrcProperties` 保留原本的五參數建構子（`deferredLogin=false`）。
- `timing.*`（`connect-timeout`、`read-timeout`、`send-interval`、`reconnect-min-delay`、`reconnect-max-delay`）預設值見 `OsuIrcSettings.Timing`。`send-interval` 預設 625ms：一般帳號上限 10 則 / 5 秒，保留 20% 餘裕；bot 帳號（300 則 / 60 秒）同樣保留餘裕可調到 240ms。
- `auto-start=false` 不自動連線。

## 類別與地雷

- **`OsuIrcAutoConfiguration`** 以 `AutoConfiguration.imports` 註冊（應用程式不必掃描 `cc.alexlin7.bancho`），`OsuIrcProperties` 以 `toSettings()` 轉成 `OsuIrcSettings` 後註冊 client bean，並把所有 `IrcMessageListener` bean 註冊進 client。使用者自訂 `OsuIrcClient` 時，properties 的綁定跟預設 client 一起退出（`DefaultClientConfiguration` 上的 `@ConditionalOnMissingBean`），lifecycle 則照樣套用。
- `DefaultClientConfiguration` **不能加 `@Configuration`**：應用程式的元件掃描涵蓋本套件時會把它當一般設定提早處理，條件就看不到使用者的 bean；只能經由 `@Import` 載入。
- `OsuIrcProperties` **不用 `@Validated` / `@NotBlank`**：classpath 上只要有 Validation API，Boot 就會建立 validator，使用端只帶 API 不帶實作時會丟 `NoProviderFoundException`；帳密檢查只交給 `OsuIrcSettings`。這個情境同一個 JVM 無法模擬，由 surefire 的 `without-validator-provider` execution 移除 Hibernate Validator 後執行 `OsuIrcWithoutValidatorProviderTest`。
- IDE 補全用的 metadata 由 `spring-boot-configuration-processor`（根 pom 的 `annotationProcessorPaths`，不要再加進 `<dependencies>`）產生；不是 `OsuIrcProperties` 欄位的設定（例如 `osu.irc.auto-start`）要寫在 `META-INF/additional-spring-configuration-metadata.json`。
- **`OsuIrcLifecycle`** 以 `SmartLifecycle` 在 context refresh 完成時連線、關閉時送 QUIT。不能改回 `ApplicationReadyEvent`：Spring Shell 以 `ApplicationRunner` 佔住 main thread，ready 事件要到 `quit` 之後才會發生。

## 測試

使用 `ApplicationContextRunner` 載入 `OsuIrcAutoConfiguration` 時，除非就是要測連線，否則要設 `osu.irc.auto-start=false`，否則 `OsuIrcLifecycle` 會在 refresh 時連到真的 cho.ppy.sh。
