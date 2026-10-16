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
./mvnw spring-boot:run                           # 啟動（需設定 osu.irc.* 憑證）
./mvnw package                                   # 打包 jar
```

執行前需提供 IRC 憑證（從 https://osu.ppy.sh/home/account/edit#legacy-api 取得 IRC 密碼，不是帳號密碼），建議用環境變數而非寫進 `application.properties`：

```
OSU_IRC_USERNAME=...  OSU_IRC_PASSWORD=...
```

`osu.irc.host` / `osu.irc.port` 預設 `cho.ppy.sh` / `6667`；`username`、`password` 有 `@NotBlank`，缺少時啟動直接失敗。`osu.irc.timing.*`（`connect-timeout`、`read-timeout`、`send-interval`、`reconnect-min-delay`、`reconnect-max-delay`）預設值見 `OsuIrcProperties.Timing`。`send-interval` 預設 500ms 是一般帳號的限制（10 則 / 5 秒）；bot 帳號（300 則 / 60 秒）可調到 200ms。`osu.irc.auto-start=false` 可不自動連線，`src/test/resources/application.properties` 就是這樣設定，所以測試不會連到真的 Bancho。

專案根目錄的 `.mcp.json` 設定了 IntelliJ IDEA MCP server（`idea`），可用來 build、跑測試、查 symbol。

## 架構

套件根目錄：`idv.alexlin7.tw.bancho`。

- **`OsuIrcClient`**：不依賴 Spring 的 IRC client（刻意保持框架無關，未來可抽成獨立模組），由三條 virtual thread 組成：
  - **connection**：`PASS` → `NICK` → `USER` 登入，回應 `PING`；收到 `001` 才算登入完成，並重新 `JOIN` 記錄中的頻道。超過 `read-timeout` 沒收到資料就主動送 PING，再次逾時即判定半開連線並重連。重連採指數 backoff；收到 `464` 或 "Bad authentication" 時**停止重連**。
  - **dispatcher**：PRIVMSG 依到達順序**逐一**通知每個 `IrcMessageListener`；某個 listener 拋出例外只記 log，不影響其他 listener 和後續訊息。
  - **sender**：出站佇列，登入完成後才送出，每則間隔 `send-interval`；斷線期間的訊息會保留到重連後再送。
- **`IrcMessage`**：IRC 行解析器（prefix / command / params，trailing 是最後一個 param）。判斷指令一律用 `command()`，**不要用字串 contains**。
- **`IrcText`**：出站訊息會把 CR/LF/NUL 換成空白，並依 UTF-8 截到 510 bytes。target 含換行時直接拋出 `IllegalArgumentException`。
- **`IrcMessageListener`（Observer）**：收訊介面。所有實作此介面的 Spring bean 會由 `OsuIrcConfiguration` 透過 `ObjectProvider.orderedStream()` 自動註冊進 client（順序依 `@Order`）。新增前端（例如 Discord）時只要再寫一個實作即可。
- **`IrcSender`**：送訊介面（`sendMessage` / `join` / `part`）。不是 listener 的 bean（例如終端機的 input loop）可以直接注入；listener 要回話時則注入 `ObjectProvider<IrcSender>`，因為直接注入會和 client 的 `@Bean` 方法形成循環依賴，Boot 預設禁止。
- **`OsuIrcConfiguration`**：註冊 client bean，並在 `ApplicationReadyEvent` 之後才 `start()`。
- **`OsuChatHandlerService`**：業務邏輯的 listener（之後接 BanchoBot 事件解析與裁判流程）。
- **`console` 套件**：終端機前端。`ConsoleMessageListener` 把收到的訊息印到 stdout；`ConsoleInputLoop` 讀 stdin，交給 `ConsoleCommandInterpreter` 解析成 `/target`、`/msg`、`/join`、`/part`、`/help`，不以 `/` 開頭的文字送給目前對象。設 `bancho.console.enabled=false` 可關閉。stdout 是它的正式輸出，所以 `consoleOut` bean 對 S106 加了 suppress。

訊息流向：Bancho → connection thread（解析）→ inbound queue → dispatcher → 各 `IrcMessageListener` → `IrcSender` → outbound queue → sender thread → Bancho。

`application.properties` 中的 `spring.main.keep-alive=true` 不能拿掉：所有工作都在 virtual thread（daemon）上跑，沒有這個設定，JVM 啟動完就會直接結束。BanchoBot 在 `#mp_<id>` 的事件也走 PRIVMSG 這條路徑。

## 慣例

- 一律用 Lombok `@Slf4j` 記 log，不要用 `System.out` / `System.err`。log 訊息加上 `[osu! IRC]` 前綴。
- 會被 SonarQube 掃描：`InterruptedException` 要重設中斷旗標，資源用 try-with-resources 關閉，測試避免 `Thread.sleep`（改用 `FakeIrcServer.awaitLine` / `awaitConnection` 這類有時限的等待）。
- 連線行為的測試使用 `src/test/java/.../FakeIrcServer`（本機假 IRC server），不要連到真的 Bancho。
- 程式註解與 log 訊息使用繁體中文。
- 尚未驗證：Bancho 是否一定回 464、是否回應 client 主動送的 PING、超過 512 bytes 時的行為。官方文件都沒有寫，目前程式是保守處理。
