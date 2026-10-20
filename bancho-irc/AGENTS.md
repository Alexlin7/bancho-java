# bancho-irc

`irc` 套件：不依賴任何框架的 IRC 連線核心。compile 依賴只有 `slf4j-api`，不可加入 Spring 或 log 後端。

## 類別

- **`OsuIrcClient`**：設定由 **`OsuIrcSettings`** 傳入（預設值與帳密空白檢查都在這個 record；`toString` 不輸出密碼），由三條 virtual thread 組成：
  - **connection**：`PASS` → `NICK` → `USER` 登入，回應 `PING`；收到 `001` 才算登入完成，並重新 `JOIN` 記錄中的頻道。超過 `read-timeout` 沒收到資料就主動送 PING，再次逾時即判定半開連線並重連。重連採指數 backoff；收到 `464` 或 "Bad authentication" 時**停止重連**。
  - **dispatcher**：PRIVMSG 依到達順序**逐一**通知每個 `IrcMessageListener`；某個 listener 拋出例外只記 log，不影響其他 listener 和後續訊息。
  - **sender**：出站佇列，登入完成後才送出，每則間隔 `send-interval`；斷線期間的訊息會保留到重連後再送。
- **`IrcMessage`**：IRC 行解析器（prefix / command / params，trailing 是最後一個 param）。判斷指令一律用 `command()`，**不要用字串 contains**。
- **`IrcText`**：出站訊息會把 CR/LF/NUL 換成空白，並依 UTF-8 截到 510 bytes。target 含換行時直接拋出 `IllegalArgumentException`。
- **`IrcMessageListener`（Observer）**：收訊介面。Spring 環境由 `OsuIrcAutoConfiguration` 透過 `ObjectProvider.orderedStream()` 自動註冊（順序依 `@Order`）。不用 Spring 時以建構子傳入，或在 client 建好後以 `addListener` 加入（需要拿 client 當 `IrcSender` 回話的 listener 只能用後者）。
- **`IrcSender`**：送訊介面（`sendMessage` / `join` / `part`）。三參數的 `sendMessage(target, message, onSent)` 在實際送出後執行 `onSent`；預設實作在兩參數版本回傳後立即執行，只適用同步送出的實作，非同步送出的實作必須覆寫（`OsuIrcClient` 在 sender thread 寫出後才執行，例外只記 log、不重送）。
- **`IrcTrafficListener`** / **`setOutboundRedactor`**：`OsuIrcClient.addTrafficListener` 觀察原始進出站行（收到的行含行尾空白；出站只通知經佇列的行，登入的 PASS/NICK/USER 不會出現）。出站行寫進 DEBUG log、截斷 WARN 或交給觀察者前會先套用 redactor（`!mp password` 的遮蔽規則由 match 提供），實際送出的內容不變。
- **連線狀態與延後登入**：`IrcConnectionState`（`IDLE`、`CONNECTING`、`REGISTERED`、`AUTH_FAILED`、`CLOSED`）以 `addConnectionListener` 通知；斷線後在等待重連之前就回到 `CONNECTING`，等待期間不會仍是 `REGISTERED`。`OsuIrcSettings` 的帳密同時為 null 代表延後登入，之後以 `login(username, password)` 提供；認證失敗後可再 `login`。

## 測試

- test-jar 提供 `FakeIrcServer`（本機假 IRC server）與 `RecordingIrcSender`，其他模組也會用；改它們的 API 要一併檢查其他模組的測試。
- 等待連線或訊息用 `FakeIrcServer.awaitLine` / `awaitConnection`，不要 `Thread.sleep`。

## 尚未驗證

Bancho 是否一定回 464、是否回應 client 主動送的 PING、超過 512 bytes 時的行為。官方文件都沒有寫，目前程式是保守處理。
