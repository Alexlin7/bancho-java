# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 專案目標

以 osu! Bancho IRC（`cho.ppy.sh:6667`）為基礎的機器人：先實作 IRC 連線行為，最終用來**開多人房（`!mp make` 等 BanchoBot 指令）並擔任比賽裁判**。後續會在 IRC 核心之上擴充 Discord 功能等模組，目標是演變成 **monorepo**（IRC 核心與 Discord 等整合應拆成獨立模組，避免讓 IRC 客戶端依賴特定前端）。

## 指令

技術棧：Java 25、Spring Boot 4.1.1、Maven（使用 wrapper）、Lombok。Windows 環境用 `mvnw.cmd`（Git Bash 下用 `./mvnw`）。

```bash
./mvnw compile                                   # 編譯
./mvnw test                                      # 跑全部測試
./mvnw -pl bancho-irc test -Dtest=OsuIrcClientTest                      # 跑單一模組的單一測試類別
./mvnw -pl bancho-irc test -Dtest=OsuIrcClientTest#answersServerPing    # 跑單一測試方法
./mvnw -pl bancho-shell -am test -Dtest=MatchCommandsTest -Dsurefire.failIfNoSpecifiedTests=false   # 連同依賴模組一起編譯
./mvnw package                                   # 打包；可執行 jar 在 bancho-shell/target/
java -jar bancho-shell/target/bancho-shell-*.jar                    # 啟動（需設定 osu.irc.* 憑證；終端機輸入要在真正的終端機裡）
./mvnw -pl bancho-shell -am compile spring-boot:run                 # 開發時啟動（帶 --enable-native-access=ALL-UNNAMED，工作目錄為專案根目錄）
java -jar bancho-shell/target/bancho-shell-*.jar --spring.profiles.active=otel   # 同時把 log、metrics 送到 localhost:4318
```

### Maven 模組

根目錄 `pom.xml`（`cc.alexlin7.bancho:bancho-parent`，packaging `pom`）繼承 `spring-boot-starter-parent`，dependency 與 plugin 版本都由 Boot 管理，升級 Boot 只要改 parent 版本。發布的函式庫也沿用它：parent 沒有宣告 `<dependencies>`，不會增加使用者的依賴，`bancho-irc` 的 runtime 依賴仍只有 `slf4j-api`；日後若要讓發布的 pom 不帶 parent 鏈，把 `flatten-maven-plugin` 改成 `ossrh` 模式，不要拔掉 starter-parent 自己維護 plugin 版本。集中管理內部模組與 Boot 不管理的版本（Spring Shell、OTel appender），所有模組共用 Lombok 與 annotation processor 設定。子模組不會繼承 `<name>`，每個模組都要自己寫。

| 模組 | 內容 |
|---|---|
| `bancho-irc` | `irc` 套件，純 Java，compile 依賴只有 `slf4j-api`（不可加入 Spring 或 log 後端）；另外產生 test-jar（`FakeIrcServer`、`RecordingIrcSender`），其他模組以 `<type>test-jar</type>` 依賴。不能拆成獨立模組：它依賴 irc，而 irc 自己的測試也要用它，會形成循環 |
| `bancho-irc-spring` | `irc.spring` 套件：`osu.irc.*` 綁定與 auto-configuration；依賴 `spring-boot-autoconfigure` 而不是 starter |
| `bancho-match` | `match` 套件與 `banchobot/` 樣本，純 Java，compile 依賴只有 `bancho-irc`（不可加入 Spring） |
| `bancho-match-spring` | `match.spring` 套件：`bancho.match.*` 綁定、範本檢查與 auto-configuration，把房間事件轉成 Spring 事件 |
| `bancho-observability` | `observability` 套件、`logback-spring.xml`、`application-otel.properties`；`@SpringBootTest` 由 test 的 `ObservabilityTestApplication` 啟動 |
| `bancho-shell` | 唯一可執行的模組：`BanchoApplication`、`shell` 套件、`application.properties` |
| `bancho-discord` | 尚未實作 |

`spring-boot-maven-plugin` 只能宣告在可執行的模組（函式庫被 repackage 後就無法被依賴）；根 pom 設 `spring-boot.run.skip=true`，可執行模組改成 `false`，`-am` 時其他模組才不會被 `spring-boot:run`。package 名稱維持 `cc.alexlin7.bancho.<feature>`，`BanchoApplication` 的元件掃描會涵蓋其他模組的 bean；同一個 package 不要分散在多個模組。

### 發布到 Maven Central

只發布 `bancho-parent`、`bancho-irc`（含 test-jar）、`bancho-irc-spring`；match 的 API（`MatchEvent` 的欄位等）還會隨新樣本調整，暫不發布。其他模組設 `bancho.publish.skip=true`（同時略過 sources、javadoc、簽章與 `skipPublishing`）。namespace `cc.alexlin7` 以 `alexlin7.cc` 的 DNS TXT 驗證。授權 MIT（根目錄 `LICENSE`）。

發布一律從 main 進行，Central 上的版本必須對應一個 tag（已發布的版本永久存在，不能刪除或覆蓋）：

```bash
# 把根 pom 的 <revision> 改成 X.Y.Z（Central 不收 -SNAPSHOT）
git commit -am "build: 版本 X.Y.Z" && git tag -a vX.Y.Z -m "bancho X.Y.Z"
./mvnw -P release -DskipTests -Dgpg.skip -Dbancho.central.skip-upload=true deploy          # 選擇性：只產生 target/central-publishing/central-bundle.zip 檢查內容
./mvnw -P release deploy -DskipTests "-Dgpg.executable=C:/Program Files/GnuPG/bin/gpg.exe" # 簽章並上傳，停在 VALIDATED
# 到 https://central.sonatype.com/publishing/deployments 按 Publish（有問題就 Drop，版本號可重用），再 git push origin vX.Y.Z
# 把 <revision> 改成 X.Y.(Z+1)-SNAPSHOT 並 commit
```

所有模組共用一個版本：根 pom 的 `<revision>` 屬性，子模組的 `<parent><version>` 寫 `${revision}`，自己不寫版本。`flatten-maven-plugin`（`resolveCiFriendliesOnly`）在 install／deploy 時把 pom 中的 `${revision}` 換成實際版本（產生 `.flattened-pom.xml`，已 ignore），不能移除，否則發布的 pom 會留下使用者解析不了的 `${revision}`。發布時改檔案並 commit，不要用 `-Drevision=X.Y.Z` 從命令列傳入：tag 指向的 commit 裡的版本會和 Central 對不上。只有函式庫會發布，應用程式模組（shell、discord）的版本只影響 jar 檔名，不需要為了它們發布新的函式庫版本。

`deploy` 預設不自動 Publish（沒有設 `autoPublish`）。GPG 金鑰（ed25519，Central 接受）在 Gpg4win 的 `%APPDATA%\gnupg`；Git Bash 自帶的 gpg 讀的是另一個空的 `~/.gnupg`，所以要以 `-Dgpg.executable` 指定 Gpg4win 的 gpg。passphrase 由 Gpg4win 的 pinentry 視窗輸入。

`release` profile 用 `central-publishing-maven-plugin`（0.12.0 修正了 Maven 3.10.0 被拒的問題；不支援 Maven 4；它與 `maven-gpg-plugin` 不受 Boot 管理，版本寫在根 pom），憑證是 `~/.m2/settings.xml` 中 id 為 `central` 的 server（Central Portal 產生的 user token）。skip 類設定以屬性串接（`maven.javadoc.skip`、`gpg.skip` 等），不要寫死在 plugin configuration，否則命令列的 `-D` 無法覆寫。javadoc 以 `doclint=all,-missing` 執行，格式錯誤（例如 record 類別上的 `@throws`）會讓發布失敗。

OpenTelemetry：預設不匯出任何資料；`otel` profile（`application-otel.properties`）把 log 與 metrics 用 HTTP 送到本機 collector 的 4318。log 透過 `logback-spring.xml` 的 `OpenTelemetryAppender` 送出，由 `observability.OpenTelemetryAppenderInitializer` 接上 SDK。`opentelemetry-logback-appender-1.0` 的版本必須對應 Boot 管理的 OTel API 版本（Boot 4.1.1 → API 1.62.0 → appender 2.28.1-alpha），不可單獨升級。metrics 匯出的預設關閉寫在前端模組的 `application.properties`（`application.properties` 不能放在函式庫模組，會和前端的同名檔互相遮蔽）；各模組測試的 `application.properties` 會遮蔽 main 的同名檔，也要關閉。

JVM 直接寫到 stderr 的 `WARNING:` 不經過 logback，會插進輸入行：JLine 以 FFM 操作 Windows 主控台（JEP 472），已明確授權——jar 的 manifest 有 `Enable-Native-Access: ALL-UNNAMED`（實測對 fat jar 內層 jar 有效），`spring-boot:run` 由 plugin 的 `jvmArguments` 帶入。protobuf 的 `sun.misc.Unsafe::arrayBaseOffset` 警告（JEP 498）來自 OTLP metrics 的依賴（`micrometer-registry-otlp` → `opentelemetry-proto` → `protobuf-java`），無法由我們修正，目前不處理；shell 前端日後若不再依賴 `bancho-observability`，警告會一起消失。日後 JDK 預設改為 deny 時，仍帶 OTel 的模組要升級 protobuf 或加 `--sun-misc-unsafe-memory-access=allow`。不要用 `--illegal-native-access=allow` 這類全域開關。

互動 shell 的終端機只顯示 WARN 以上，完整 INFO log 寫到 `logs/bancho.log`（相對於啟動工作目錄），以 `BANCHO_LOG_FILE` 可改成其他相對或絕對路徑。沿用 Boot 的滾動檔案設定：`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE` 預設 `10MB`，`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY` 預設 `7` 天；`otel` profile 仍同時匯出紀錄。Spring Shell 的指令歷史存在 `<工作目錄>/bancho-history`（`spring.shell.history.name`；預設的 `${spring.application.name}.log` 容易和 log 混淆），測試關閉歷史。日後若改成不開 shell 的執行方式，也要把 `logging.threshold.console` 改回 INFO。

執行前需提供 IRC 憑證（從 https://osu.ppy.sh/home/account/edit#legacy-api 取得 IRC 密碼，不是帳號密碼），建議用環境變數而非寫進 `application.properties`：

```
OSU_IRC_USERNAME=...  OSU_IRC_PASSWORD=...
```

`osu.irc.host` / `osu.irc.port` 預設 `cho.ppy.sh` / `6667`；`username`、`password` 必填，空白或完全沒設定時建立 client 就失敗（訊息為 `osu.irc.username 不得空白`）；例外是 `osu.irc.deferred-login=true` 且帳密都空白：client 照常建立但不連線（狀態 `IDLE`，lifecycle 記 INFO「未設定帳密，等待 login」），由應用程式稍後呼叫 `OsuIrcClient.login`。`OsuIrcProperties` 保留原本的五參數建構子（`deferredLogin=false`）。`osu.irc.timing.*`（`connect-timeout`、`read-timeout`、`send-interval`、`reconnect-min-delay`、`reconnect-max-delay`）預設值見 `OsuIrcSettings.Timing`。`send-interval` 預設 625ms：一般帳號上限 10 則 / 5 秒，保留 20% 餘裕；bot 帳號（300 則 / 60 秒）同樣保留餘裕可調到 240ms。`osu.irc.auto-start=false` 可不自動連線，`bancho-shell/src/test/resources/application.properties` 就是這樣設定，所以測試不會連到真的 Bancho。

專案根目錄的 `.mcp.json` 設定了 IntelliJ IDEA MCP server（`idea`），可用來 build、跑測試、查 symbol。

## 架構

依功能分 package，依賴方向只能是 `shell → match → irc`；Spring 整合放在各自的 `*.spring` 模組（`match.spring → match`、`match.spring → irc.spring → irc`），只由前端引入（`irc` 不認識房間，`match` 不認識前端也不認識 Spring）。只在 feature 內使用的類別設為 package-private。

### `irc`：IRC 連線核心

- **`OsuIrcClient`**：不依賴任何框架的 IRC client（設定由 **`OsuIrcSettings`** 傳入，預設值與帳密空白檢查都在這個 record；`toString` 不輸出密碼），由三條 virtual thread 組成：
  - **connection**：`PASS` → `NICK` → `USER` 登入，回應 `PING`；收到 `001` 才算登入完成，並重新 `JOIN` 記錄中的頻道。超過 `read-timeout` 沒收到資料就主動送 PING，再次逾時即判定半開連線並重連。重連採指數 backoff；收到 `464` 或 "Bad authentication" 時**停止重連**。
  - **dispatcher**：PRIVMSG 依到達順序**逐一**通知每個 `IrcMessageListener`；某個 listener 拋出例外只記 log，不影響其他 listener 和後續訊息。
  - **sender**：出站佇列，登入完成後才送出，每則間隔 `send-interval`；斷線期間的訊息會保留到重連後再送。
- **`IrcMessage`**：IRC 行解析器（prefix / command / params，trailing 是最後一個 param）。判斷指令一律用 `command()`，**不要用字串 contains**。
- **`IrcText`**：出站訊息會把 CR/LF/NUL 換成空白，並依 UTF-8 截到 510 bytes。target 含換行時直接拋出 `IllegalArgumentException`。
- **`IrcMessageListener`（Observer）**：收訊介面。所有實作此介面的 Spring bean 會由 `OsuIrcAutoConfiguration` 透過 `ObjectProvider.orderedStream()` 自動註冊進 client（順序依 `@Order`）。不用 Spring 時以建構子傳入，或在 client 建好後以 `addListener` 加入（需要拿 client 當 `IrcSender` 回話的 listener 只能用後者）。
- **`IrcSender`**：送訊介面（`sendMessage` / `join` / `part`）。三參數的 `sendMessage(target, message, onSent)` 在實際送出後執行 `onSent`；預設實作在兩參數版本回傳後立即執行，只適用同步送出的實作，非同步送出的實作必須覆寫（`OsuIrcClient` 在 sender thread 寫出後才執行，例外只記 log、不重送）。
- **`IrcTrafficListener`** / **`setOutboundRedactor`**：`OsuIrcClient.addTrafficListener` 觀察原始進出站行（收到的行含行尾空白；出站只通知經佇列的行，登入的 PASS/NICK/USER 不會出現）。出站行寫進 DEBUG log、截斷 WARN 或交給觀察者前會先套用 redactor（`!mp password` 的遮蔽規則由 match 提供），實際送出的內容不變。
- **連線狀態與延後登入**：`IrcConnectionState`（`IDLE`、`CONNECTING`、`REGISTERED`、`AUTH_FAILED`、`CLOSED`）以 `addConnectionListener` 通知。`OsuIrcSettings` 的帳密同時為 null 代表延後登入，之後以 `login(username, password)` 提供；認證失敗後可再 `login`。不是 listener 的 bean 可以直接注入；listener 要回話時則注入 `ObjectProvider<IrcSender>`，因為直接注入會和 client 的 `@Bean` 方法形成循環依賴，Boot 預設禁止。
- **`irc.spring.OsuIrcAutoConfiguration`** / **`OsuIrcLifecycle`**（`bancho-irc-spring`）：以 `AutoConfiguration.imports` 註冊（應用程式不必掃描 `cc.alexlin7.bancho`），`OsuIrcProperties` 以 `toSettings()` 轉成 `OsuIrcSettings` 後註冊 client bean。使用者自訂 `OsuIrcClient` 時，properties 的綁定跟預設 client 一起退出（`DefaultClientConfiguration` 上的 `@ConditionalOnMissingBean`），lifecycle 則照樣套用。IDE 補全用的 metadata 由 `spring-boot-configuration-processor`（根 pom 的 `annotationProcessorPaths`，不要再加進 `<dependencies>`）產生；不是 `OsuIrcProperties` 欄位的設定（例如 `osu.irc.auto-start`）要寫在 `META-INF/additional-spring-configuration-metadata.json`。`DefaultClientConfiguration` **不能加 `@Configuration`**：應用程式的元件掃描涵蓋本套件時會把它當一般設定提早處理，條件就看不到使用者的 bean；只能經由 `@Import` 載入。`OsuIrcProperties` **不用 `@Validated` / `@NotBlank`**：classpath 上只要有 Validation API，Boot 就會建立 validator，使用端只帶 API 不帶實作時會丟 `NoProviderFoundException`；帳密檢查只交給 `OsuIrcSettings`。這個情境同一個 JVM 無法模擬，由 surefire 的 `without-validator-provider` execution 移除 Hibernate Validator 後執行 `OsuIrcWithoutValidatorProviderTest`。以 `SmartLifecycle` 在 context refresh 完成時連線、關閉時送 QUIT。不能改回 `ApplicationReadyEvent`：Spring Shell 以 `ApplicationRunner` 佔住 main thread，ready 事件要到 `quit` 之後才會發生。

### `match`：多人房

- **`BanchoBotParser`**：純函式，把 BanchoBot 的 PRIVMSG 解析成 `Result`（`Parsed` / `Ignored` / `Unrecognized` / `NotFromBanchoBot`）。只信任 nick 為 `BanchoBot` 的訊息；私訊只辨識開房回覆，頻道訊息只處理 `#mp_<id>`。每條 regex 必須比對整行。
- **`event.MatchEvent`**：sealed interface，事件都是巢狀 record。team mode、mod 名稱等維持 BanchoBot 原文字串，不轉 enum（沒有完整樣本）。
- **`BanchoBotListener`**：`IrcMessageListener`，解析後依清單順序通知每個 **`event.MatchEventListener`**（例外只記 log，不影響其他 listener）；`MatchService` 必須排第一個，其他 listener 查到的才是更新後的房間。看不懂的 BanchoBot 訊息記 INFO（帶 `match.id`、`banchobot.text`），是蒐集新樣本的管道。
- **`MatchService`**：`createMatch(name)` 回傳 `MatchCreation`（`room` 在收到同名回覆時完成，逾時後晚到也會完成；`unknown` 在轉為結果未知時完成），以三參數 `sendMessage` 送 `!mp make`，**實際送出後**才開始計時（預設 15 秒，`bancho.match.create-timeout`），逾時轉為結果未知、繼續佔用名額。名額上限 `MAX_ROOMS`＝4，未關閉的房間（含 `attach`、被動登記）與建房中、結果未知的請求共用。同名請求在建房中或結果未知時拒絕，其他房名照常可開；`drop(name)` 釋放結果未知的請求，該房名在本次執行中不能再用（舊回覆可能晚到，不能配給新請求）。計時器只作用在同一個仍在建房中的請求。實作 `MatchEventListener`，接收事件後登記並更新房間。收到 `MatchCreated` 一律登記並 join，收到其他未登記房號的事件只登記不 join，`MatchClosed` 時 part。
- **`MatchRoom`**：封裝 `!mp` 指令（`mods` 會擋下 `NFHD` 這類連寫，BanchoBot 看不懂會清空所有 mod；可接受的長名稱與 shell 的 mod 補全共用 **`ModNames.KNOWN`**）；**`MatchState`** 是不可變 record（`gameMode` 為 BanchoBot 回覆的原文，例如 `CatchTheBeat`），存在 `AtomicReference`，事件只在 dispatcher thread 上套用，任何 thread 讀到的都是完整快照。

**`match.spring.MatchAutoConfiguration`**（`bancho-match-spring`）：在有 `IrcSender` bean 時註冊 `MatchService`（使用者自訂時退出）與 `BanchoBotListener`，listener 清單是 `MatchService` → `ApplicationEventPublisher`，所以應用程式以 `@EventListener` 接收 `MatchEvent`，不必實作 `MatchEventListener`。`MatchService` 以 `ObjectProvider` 延後取得：直接注入會形成 client → `BanchoBotListener` → `MatchService` → client 的循環。`MatchProperties`、`MatchPresetProperties` 都在這個套件。另外在有 `OsuIrcClient` 時以 `setOutboundRedactor(BanchoRedaction::redactOutbound)` 遮蔽 `!mp password <x>`（不分大小寫），DEBUG log、截斷 WARN 與樣本檔都不會留下房間密碼。

Spring 事件預設同步在發布者的 thread（IRC dispatcher）上執行，所以所有 `@EventListener` 依訊息到達順序處理；耗時的 listener 要自行轉交其他 thread。

### `match/preset`：房間設定範本

`MatchPreset`（`bancho.match.presets.<名稱>.*`）：`team-mode`、`score-mode`、`size`（三者必須一起設定）、`password`、`mods`、`beatmap`、`mode`（需搭配 `beatmap`）、`referees`、`invite`，都可省略。`mode` 接受 0–3 或模式名稱／別名；`mode` 與 `mods`（含連寫檢查）都由 `MatchRoom.validatePreset` 檢查，`MatchAutoConfiguration` 在啟動時對全部範本執行，避免開房後才失敗。`MatchRoom.apply(MatchPreset)` 依 set → password → mods → map → addref → invite 送出，未填的跳過。

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

- **`MatchCommands`**（`make <房名…> [--preset]`、`attach`、`use`、`rooms`（含建房中、結果未知的請求與 `名額 n/4`）、`drop <房名…>`（釋放結果未知的請求，房名本次執行不能再用）、`invite <名稱…>`、`map <beatmapId> [模式]`、`mods`、`set`、`start [秒]`、`timer [秒]`、`abort`、`close`、`settings`、`password [--clear]`、`addref`）與 **`ChatCommands`**（`say`、`msg`）。`msg` 後面的字全部是對象（osu! 名稱可以有空白），訊息一律以 **`TextPrompt`** 另外提示輸入；只打 `msg` 時連對象也提示。對象與訊息寫在同一行時無從判斷界線，`msg Minato Yukina 你好` 曾經把訊息送給 `Minato`，不要改回單行。`make` 逾時時印出「開房結果未知」並提示 `drop`，晚到的成功回覆標註「晚到的回覆」並照常切換房間、套用範本；被 drop 的請求不印任何訊息。房間指令預設作用在 **`ShellSession`** 的目前房間，`--room <房號>` 可指定其他房間；由 `RoomResolver` 解析房間，參數錯誤一律轉成 `錯誤：...` 文字，不讓例外冒到 shell。Spring Shell 會悄悄忽略打錯的選項與多餘的參數，所以每個指令先經過 **`InputGuard`**（注入 `CommandContext` 檢查）拒絕它們；以 `-` 開頭的聊天文字要用雙引號包起來。
- `mp <指令…> [--room <房號>]` 可轉送未包裝的 `!mp` 子指令；開房請用 `make`，`mp password` 一律拒絕（密碼會留在指令歷史）。`mpCompletion` 只補第一個位置參數的常用子指令與 `--room` 的房號。
- `password` 先確定房間，再以 **`SecretPrompt`** 提示輸入（JLine 遮蔽、以 `*` 回顯，不寫入歷史）。兩種提示都由 **`JLinePrompt`** 實作：讀取期間關閉歷史展開（`!mp` 才不會被改寫）、引號與跳脫、Tab 補全與寫入指令歷史，讀完還原；空白輸入或 Ctrl+C 取消，`--clear` 移除密碼，密碼寫在指令裡會被拒絕。非互動模式的 `SecretPrompt`、`TextPrompt` 直接丟 `IllegalStateException`。
- 數字位置參數（`set`、`map`、`attach`、`use`）收 `String` 自行解析：交給 Spring Shell 轉換時，少給或非數字只會得到看不懂的英文例外。少給或格式錯誤一律回 `錯誤：用法：…`。
- `-` 開頭名稱可用雙引號（`addref "-SH-" --room 42`），或在目前房間使用 `addref -- -SH-`。`--` 只能緊接指令，之後不能再加選項（`--room`、`--room=…` 都會被拒絕）；要指定房間請用雙引號。單引號不會被移除。JLine 對已輸入開引號的候選值會重複引號，因此使用者名稱補全暫不自動加引號；跳脫請手動輸入。
- **`ShellOutput`**：有 JLine `LineReader` 時用 `printAbove`，訊息印在輸入行上方。**`ShellMessagePrinter`** 把收到的 IRC 訊息印出來。
- **`ShellCompletions`**：`CompletionProvider` bean（`roomCompletion`、`roomOptionCompletion`、`userCompletion`、`modsCompletion`、`makeCompletion`、`mapCompletion`），名稱即 `@Command(completionProvider = ...)` 的值。Spring Shell 會自己補選項名稱，provider 只給值。**`KnownUsers`** 收集房間事件、私訊與範本名單中的使用者名稱。
- **`ShellLineEditing`**（BeanPostProcessor）：JLine 補全改為不分大小寫；parser 只把雙引號當引號（預設單引號也算，`say I'm ready` 會卡在等待補引號）。並把 `JLineInputProvider` 換成 **`InterruptAwareInputProvider`**：Spring Shell 預設按一次 Ctrl+C 就結束 shell，改成輸入到一半只清掉該行、空行按一次提示、2 秒內再按一次才離開（判斷在 `CtrlCGuard`）；Ctrl+D 仍直接離開。`mvn spring-boot:run` 時 Maven 自己也會收到 Ctrl+C 並結束子程序，實際使用請用 `java -jar`。
- 內建 `help`、`quit`。Tab 補全要在真正的終端機（Windows Terminal、IntelliJ 的 Terminal 分頁），IntelliJ 的 Run 視窗沒有補全。

### `observability`

OpenTelemetry log appender 的安裝（見上方「指令」）。

訊息流向：Bancho → connection thread（解析）→ inbound queue → dispatcher → 各 `IrcMessageListener`（`BanchoBotListener` → `MatchService` → Spring 事件 → `KnownUsers`；`ShellMessagePrinter`）→ `IrcSender` → outbound queue → sender thread → Bancho。

`application.properties` 中的 `spring.main.keep-alive=false`：互動 shell 佔住 main thread，設成 `true` 時 `quit` 後 JVM 不會結束。日後若有不開 shell 的執行方式（例如只跑 Discord），要改回 `true`，否則所有工作都在 daemon virtual thread 上，JVM 啟動完就會結束。`spring.shell.context.close=true` 會讓 Boot 在 context 已關閉後發佈 ready 事件而 exit 1，不要用。

Spring Shell 的非互動模式（`SPRING_SHELL_INTERACTIVE_ENABLED=false` 加上指令參數）**只用於診斷**（例如打包後以 `rooms` 驗證能啟動）：指令執行完 JVM 就結束，出站訊息要等登入完成才送，所以 `make`、`msg`、`attach` 等送 IRC 的指令都送不出去；房間只存在記憶體，`--room` 也找不到。這是刻意不支援，不開 shell 的執行方式日後由 Discord 等前端負責。

設計文件與實作計畫在 `docs/superpowers/specs/`、`docs/superpowers/plans/`。

## 慣例

- 一律用 Lombok `@Slf4j` 記 log，不要用 `System.out` / `System.err`。log 前綴依 feature：`[osu! IRC]`、`[match]`、`[shell]`。需要可查詢的欄位時用 SLF4J key-value API（`log.atInfo().addKeyValue(...)`），OTel 會轉成 attribute。
- BanchoBot 的真實樣本放在 `bancho-match/src/test/resources/banchobot/`（`.irc` 檔的行尾空白是原文的一部分）。新增解析規則前先加樣本與測試，不要憑記憶寫格式。`MatchReplayIntegrationTest` 會把樣本整段回放進系統。
- 會被 SonarQube 掃描：`InterruptedException` 要重設中斷旗標，資源用 try-with-resources 關閉，測試避免 `Thread.sleep`（改用 `FakeIrcServer.awaitLine` / `awaitConnection` 這類有時限的等待）。
- 測試不得啟動互動 shell：test 的 `application.properties` 設 `spring.shell.interactive.enabled=false`，`bancho-shell/src/test/.../TestShellRunnerConfiguration` 以空的 `springShellApplicationRunner` 取代 shell runner，並把 JLine 換成 dumb terminal（測試 JVM 沒有主控台，偵測系統 terminal 要約 40 秒）。它放在根 package，所以 shell 模組的每個 `@SpringBootTest` 都會掃描到。要經由真正的 parser / registry 測指令時，用 `new ShellTestClient(commandParser, commandRegistry)`。
- 使用 `ApplicationContextRunner` 載入 `OsuIrcAutoConfiguration` 時，除非就是要測連線，否則要設 `osu.irc.auto-start=false`，否則 `OsuIrcLifecycle` 會在 refresh 時連到真的 cho.ppy.sh。
- 測試的 OTel service name 是 `bancho-test`，與正式的 `bancho` 區隔。
- 連線行為的測試使用 `irc.FakeIrcServer`（本機假 IRC server），不要連到真的 Bancho；記錄送訊內容用 `irc.RecordingIrcSender`。
- 程式註解與 log 訊息使用繁體中文。
- 尚未驗證：Bancho 是否一定回 464、是否回應 client 主動送的 PING、超過 512 bytes 時的行為。官方文件都沒有寫，目前程式是保守處理。
