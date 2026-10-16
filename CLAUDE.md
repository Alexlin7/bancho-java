# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 專案目標

以 osu! Bancho IRC（`cho.ppy.sh:6667`）為基礎的機器人：先實作 IRC 連線行為，最終用來**開多人房（`!mp make` 等 BanchoBot 指令）並擔任比賽裁判**。後續會在 IRC 核心之上擴充 Discord 功能等模組，目標是演變成 **monorepo**（IRC 核心與 Discord 等整合應拆成獨立模組，避免讓 IRC 客戶端依賴特定前端）。

## 指令

技術棧：Java 25、Spring Boot 4.1.1、Maven（使用 wrapper）、Lombok。Windows 環境用 `mvnw.cmd`（Git Bash 下用 `./mvnw`）。

```bash
./mvnw compile                                   # 編譯
./mvnw test                                      # 跑全部測試
./mvnw test -Dtest=OsuIrcClientTest              # 跑單一測試類別
./mvnw test -Dtest=OsuIrcClientTest#answersServerPing   # 跑單一測試方法
./mvnw package                                   # 打包 jar
java -jar target/bancho-0.0.1-SNAPSHOT.jar       # 啟動（需設定 osu.irc.* 憑證；終端機輸入要在真正的終端機裡）
java -jar target/bancho-0.0.1-SNAPSHOT.jar --spring.profiles.active=otel   # 同時把 log、metrics 送到 localhost:4318
```

OpenTelemetry：預設不匯出任何資料；`otel` profile（`application-otel.properties`）把 log 與 metrics 用 HTTP 送到本機 collector 的 4318。log 透過 `logback-spring.xml` 的 `OpenTelemetryAppender` 送出，由 `observability.OpenTelemetryAppenderInitializer` 接上 SDK。`opentelemetry-logback-appender-1.0` 的版本必須對應 Boot 管理的 OTel API 版本（Boot 4.1.1 → API 1.62.0 → appender 2.28.1-alpha），不可單獨升級。測試的 `application.properties` 會遮蔽 main 的同名檔，兩邊都要關閉 metrics 匯出。

互動 shell 的終端機只顯示 WARN 以上，完整 INFO log 寫到 `logs/bancho.log`（相對於啟動工作目錄），以 `BANCHO_LOG_FILE` 可改成其他相對或絕對路徑。沿用 Boot 的滾動檔案設定：`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE` 預設 `10MB`，`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY` 預設 `7` 天；`otel` profile 仍同時匯出紀錄。日後若改成不開 shell 的執行方式，也要把 `logging.threshold.console` 改回 INFO。

執行前需提供 IRC 憑證（從 https://osu.ppy.sh/home/account/edit#legacy-api 取得 IRC 密碼，不是帳號密碼），建議用環境變數而非寫進 `application.properties`：

```
OSU_IRC_USERNAME=...  OSU_IRC_PASSWORD=...
```

`osu.irc.host` / `osu.irc.port` 預設 `cho.ppy.sh` / `6667`；`username`、`password` 有 `@NotBlank`，缺少時啟動直接失敗。`osu.irc.timing.*`（`connect-timeout`、`read-timeout`、`send-interval`、`reconnect-min-delay`、`reconnect-max-delay`）預設值見 `OsuIrcProperties.Timing`。`send-interval` 預設 500ms 是一般帳號的限制（10 則 / 5 秒）；bot 帳號（300 則 / 60 秒）可調到 200ms。`osu.irc.auto-start=false` 可不自動連線，`src/test/resources/application.properties` 就是這樣設定，所以測試不會連到真的 Bancho。

專案根目錄的 `.mcp.json` 設定了 IntelliJ IDEA MCP server（`idea`），可用來 build、跑測試、查 symbol。

## 架構

依功能分 package，依賴方向只能是 `shell → match → irc`（`irc` 不認識房間，`match` 不認識前端）。只在 feature 內使用的類別設為 package-private。

### `irc`：IRC 連線核心

- **`OsuIrcClient`**：不依賴 Spring 的 IRC client（刻意保持框架無關，未來可抽成獨立模組），由三條 virtual thread 組成：
  - **connection**：`PASS` → `NICK` → `USER` 登入，回應 `PING`；收到 `001` 才算登入完成，並重新 `JOIN` 記錄中的頻道。超過 `read-timeout` 沒收到資料就主動送 PING，再次逾時即判定半開連線並重連。重連採指數 backoff；收到 `464` 或 "Bad authentication" 時**停止重連**。
  - **dispatcher**：PRIVMSG 依到達順序**逐一**通知每個 `IrcMessageListener`；某個 listener 拋出例外只記 log，不影響其他 listener 和後續訊息。
  - **sender**：出站佇列，登入完成後才送出，每則間隔 `send-interval`；斷線期間的訊息會保留到重連後再送。
- **`IrcMessage`**：IRC 行解析器（prefix / command / params，trailing 是最後一個 param）。判斷指令一律用 `command()`，**不要用字串 contains**。
- **`IrcText`**：出站訊息會把 CR/LF/NUL 換成空白，並依 UTF-8 截到 510 bytes。target 含換行時直接拋出 `IllegalArgumentException`。
- **`IrcMessageListener`（Observer）**：收訊介面。所有實作此介面的 Spring bean 會由 `OsuIrcConfiguration` 透過 `ObjectProvider.orderedStream()` 自動註冊進 client（順序依 `@Order`）。
- **`IrcSender`**：送訊介面（`sendMessage` / `join` / `part`）。不是 listener 的 bean 可以直接注入；listener 要回話時則注入 `ObjectProvider<IrcSender>`，因為直接注入會和 client 的 `@Bean` 方法形成循環依賴，Boot 預設禁止。
- **`OsuIrcConfiguration`** / **`OsuIrcLifecycle`**：註冊 client bean，以 `SmartLifecycle` 在 context refresh 完成時連線、關閉時送 QUIT。不能改回 `ApplicationReadyEvent`：Spring Shell 以 `ApplicationRunner` 佔住 main thread，ready 事件要到 `quit` 之後才會發生。

### `match`：多人房

- **`BanchoBotParser`**：純函式，把 BanchoBot 的 PRIVMSG 解析成 `Result`（`Parsed` / `Ignored` / `Unrecognized` / `NotFromBanchoBot`）。只信任 nick 為 `BanchoBot` 的訊息；私訊只辨識開房回覆，頻道訊息只處理 `#mp_<id>`。每條 regex 必須比對整行。
- **`event.MatchEvent`**：sealed interface，事件都是巢狀 record。team mode、mod 名稱等維持 BanchoBot 原文字串，不轉 enum（沒有完整樣本）。
- **`BanchoBotListener`**：`IrcMessageListener`，解析後用 `ApplicationEventPublisher` 發佈事件；看不懂的 BanchoBot 訊息記 INFO（帶 `match.id`、`banchobot.text`），是蒐集新樣本的管道。
- **`MatchService`**：`createMatch(name)` 送 `!mp make` 並以房名對應 BanchoBot 的回覆（同名請求在等待中時拒絕，逾時預設 15 秒，`bancho.match.create-timeout`）；以 `@EventListener` 接收事件、登記並更新房間。收到 `MatchCreated` 一律登記並 join，收到其他未登記房號的事件只登記不 join，`MatchClosed` 時 part。
- **`MatchRoom`**：封裝 `!mp` 指令；**`MatchState`** 是不可變 record，存在 `volatile` 欄位，事件只在 dispatcher thread 上套用，任何 thread 讀到的都是完整快照。

Spring 事件預設同步在發布者的 thread（IRC dispatcher）上執行，所以所有 `@EventListener` 依訊息到達順序處理；耗時的 listener 要自行轉交其他 thread。

### `match/preset`：房間設定範本

`MatchPreset`（`bancho.match.presets.<名稱>.*`）：`team-mode`、`score-mode`、`size`（三者必須一起設定）、`password`、`mods`、`beatmap`、`mode`（需搭配 `beatmap`）、`referees`、`invite`，都可省略。`mode` 接受 0–3 或模式名稱／別名，啟動時由 `MatchConfiguration` 檢查。`MatchRoom.apply(MatchPreset)` 依 set → password → mods → map → addref → invite 送出，未填的跳過。

```yaml
bancho:
  match:
    presets:
      practice:
        team-mode: 0
        score-mode: 3
        size: 2
        mods: [HD, HR]
        invite: [Alexlin86]
```

### `shell`：Spring Shell 前端

- **`MatchCommands`**（`make <房名…> [--preset]`、`attach`、`use`、`rooms`、`invite <名稱…>`、`map <beatmapId> [模式]`、`mods`、`set`、`start [秒]`、`timer [秒]`、`abort`、`close`、`settings`、`password [密碼]`、`addref`）與 **`ChatCommands`**（`say`、`msg`）。房間指令預設作用在 **`ShellSession`** 的目前房間，`--room <房號>` 可指定其他房間；由 `RoomResolver` 解析房間，參數錯誤一律轉成 `錯誤：...` 文字，不讓例外冒到 shell。Spring Shell 會悄悄忽略打錯的選項與多餘的參數，所以每個指令先經過 **`InputGuard`**（注入 `CommandContext` 檢查）拒絕它們；以 `-` 開頭的聊天文字要用雙引號包起來。
- `mp <指令…> [--room <房號>]` 可轉送未包裝的 `!mp` 子指令；開房請用 `make`，`mp password` 的回覆遮蔽密碼。`mpCompletion` 只補第一個位置參數的常用子指令與 `--room` 的房號。
- `-` 開頭名稱可用雙引號（`addref "-SH-" --room 42`），或在目前房間使用 `addref -- -SH-`。`--` 只能緊接指令，之後不能再加選項（`--room`、`--room=…` 都會被拒絕）；要指定房間請用雙引號。單引號不會被移除。JLine 對已輸入開引號的候選值會重複引號，因此使用者名稱補全暫不自動加引號；跳脫請手動輸入。
- **`ShellOutput`**：有 JLine `LineReader` 時用 `printAbove`，訊息印在輸入行上方。**`ShellMessagePrinter`** 把收到的 IRC 訊息印出來。
- **`ShellCompletions`**：`CompletionProvider` bean（`roomCompletion`、`roomOptionCompletion`、`userCompletion`、`modsCompletion`、`makeCompletion`、`mapCompletion`），名稱即 `@Command(completionProvider = ...)` 的值。Spring Shell 會自己補選項名稱，provider 只給值。**`KnownUsers`** 收集房間事件、私訊與範本名單中的使用者名稱。
- **`ShellLineEditing`**（BeanPostProcessor）：JLine 補全改為不分大小寫；parser 只把雙引號當引號（預設單引號也算，`say I'm ready` 會卡在等待補引號）。
- 內建 `help`、`quit`。Tab 補全要在真正的終端機（Windows Terminal、IntelliJ 的 Terminal 分頁），IntelliJ 的 Run 視窗沒有補全。

### `observability`

OpenTelemetry log appender 的安裝（見上方「指令」）。

訊息流向：Bancho → connection thread（解析）→ inbound queue → dispatcher → 各 `IrcMessageListener`（`BanchoBotListener` → Spring 事件 → `MatchService`、`KnownUsers`；`ShellMessagePrinter`）→ `IrcSender` → outbound queue → sender thread → Bancho。

`application.properties` 中的 `spring.main.keep-alive=false`：互動 shell 佔住 main thread，設成 `true` 時 `quit` 後 JVM 不會結束。日後若有不開 shell 的執行方式（例如只跑 Discord），要改回 `true`，否則所有工作都在 daemon virtual thread 上，JVM 啟動完就會結束。`spring.shell.context.close=true` 會讓 Boot 在 context 已關閉後發佈 ready 事件而 exit 1，不要用。

設計文件與實作計畫在 `docs/superpowers/specs/`、`docs/superpowers/plans/`。

## 慣例

- 一律用 Lombok `@Slf4j` 記 log，不要用 `System.out` / `System.err`。log 前綴依 feature：`[osu! IRC]`、`[match]`、`[shell]`。需要可查詢的欄位時用 SLF4J key-value API（`log.atInfo().addKeyValue(...)`），OTel 會轉成 attribute。
- BanchoBot 的真實樣本放在 `src/test/resources/banchobot/`（`.irc` 檔的行尾空白是原文的一部分）。新增解析規則前先加樣本與測試，不要憑記憶寫格式。`MatchReplayIntegrationTest` 會把樣本整段回放進系統。
- 會被 SonarQube 掃描：`InterruptedException` 要重設中斷旗標，資源用 try-with-resources 關閉，測試避免 `Thread.sleep`（改用 `FakeIrcServer.awaitLine` / `awaitConnection` 這類有時限的等待）。
- 測試不得啟動互動 shell：test 的 `application.properties` 設 `spring.shell.interactive.enabled=false`，`src/test/.../TestShellRunnerConfiguration` 以空的 `springShellApplicationRunner` 取代 shell runner，並把 JLine 換成 dumb terminal（測試 JVM 沒有主控台，偵測系統 terminal 要約 40 秒）。它放在根 package，所以每個 `@SpringBootTest` 都會掃描到。要經由真正的 parser / registry 測指令時，用 `new ShellTestClient(commandParser, commandRegistry)`。
- 使用 `ApplicationContextRunner` 載入 `OsuIrcConfiguration` 時，除非就是要測連線，否則要設 `osu.irc.auto-start=false`，否則 `OsuIrcLifecycle` 會在 refresh 時連到真的 cho.ppy.sh。
- 測試的 OTel service name 是 `bancho-test`，與正式的 `bancho` 區隔。
- 連線行為的測試使用 `irc.FakeIrcServer`（本機假 IRC server），不要連到真的 Bancho；記錄送訊內容用 `irc.RecordingIrcSender`。
- 程式註解與 log 訊息使用繁體中文。
- 尚未驗證：Bancho 是否一定回 464、是否回應 client 主動送的 PING、超過 512 bytes 時的行為。官方文件都沒有寫，目前程式是保守處理。
