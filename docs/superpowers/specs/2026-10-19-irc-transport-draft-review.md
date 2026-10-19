# IRC 傳輸層草稿與 `bancho-irc` 的差異

- 日期：2026-10-19
- 對象：實作前的「osu! Bot：Java IRC 傳輸層概略設計」（2026-10-16，以下簡稱「草稿」）。草稿內容已大多被 `bancho-irc` 取代，不收錄全文，只記錄差異與留待日後的項目。
- 相關文件：[帳號、房間生命週期與權限設計](2026-10-15-osu-bot-lifecycle-permissions-design.md)（草稿的上層文件）

## 1. 已落實

| 草稿 | 目前實作 |
| --- | --- |
| 以 IRC 結構解析，不用 `contains("PRIVMSG")` | `IrcMessage.command()` |
| 收訊依序分派，handler 例外隔離並記錄 | dispatcher thread 逐一通知 `IrcMessageListener`，例外只記 log |
| 每輪連線獨立持有 socket／reader／writer，不沿用舊 writer | 每輪一個 `Connection` 物件；沒有另外的世代編號，以物件區隔 |
| 重複啟動不產生多條連線 | `started` 旗標由 `lifecycleLock` 保護 |
| 明確認證失敗不無限重試 | `464` 或 "Bad authentication" 進入 `AUTH_FAILED`，停止重連 |
| 網路故障退避重連 | 指數 backoff（`reconnect-min-delay`～`reconnect-max-delay`） |
| connect timeout、主動 PING、半死連線視同斷線 | `connect-timeout`；超過 `read-timeout` 沒資料就 PING，再逾時即重連 |
| 完成認證後才開放應用指令 | sender 等到收到 `001` 才送；shell 另有 `LoginGuard` |
| QUIT 盡力送出，不阻擋關機 | `close()` 送 `QUIT`，失敗只記 debug |
| 拒絕 CR／LF 注入，長度與 UTF-8 邊界 | `IrcText`：內容的 CR/LF/NUL 換成空白並截到 510 bytes；target 含換行直接拋例外 |
| 限速保留 20% 餘裕，重連不重置配額 | 固定間隔 `send-interval`（預設 625ms），重連後的 JOIN 也走同一個佇列 |
| 15 秒從實際送出起算 | `sendMessage(target, message, onSent)` |
| 缺少帳密時啟動失敗 | `OsuIrcSettings` 檢查；例外是 `osu.irc.deferred-login` 且帳密都空白 |
| 密碼不出現在 `toString()` 與 log | `OsuIrcSettings`、`Credentials` 的 `toString` 不輸出密碼；`setOutboundRedactor` 遮蔽 `!mp password` |
| 本地假 IRC server 驗證 | `FakeIrcServer` |

連線狀態的對應：草稿的「停止」對應 `IDLE`／`CLOSED`；「連線中、認證中、重連等待」併成 `CONNECTING`；「就緒」是 `REGISTERED`；「故障停用」目前只有認證失敗（`AUTH_FAILED`），禁言尚未偵測。

## 2. 刻意不同

### 2.1 斷線期間的訊息保留到重連後送出

草稿：斷線時停止取出應用指令，未送出的工作回報上層，不在重連後自動重播。

實作：出站佇列（`outbound`）裡的訊息保留，重連登入後照原順序送出；寫入失敗的那一行以 `addFirst` 放回最前面。

理由：佇列裡的訊息**從未寫出**，重連後送出不會造成重複。草稿擔心的是「可能已送出」的指令被重送，這在實作中只會發生在寫入失敗的那一行，見下一點。

### 2.2 寫入失敗後重送是安全的

草稿：開始寫入後失敗可能已有資料抵達，應回報「傳送結果未知」，不可直接重試。

結論（2026-10-19 釐清）：一行 IRC 訊息最多 512 bytes，`BufferedWriter.flush()` 只會呼叫一次 socket write。這次 write 失敗時，作業系統沒有收下資料，所以「拋出 `IOException` 但資料已抵達 Bancho」實務上不會發生，重送不會讓 `!mp make` 開出兩間房。

真正需要處理的是相反的情況：連線已經半死，write 卻成功（資料只進了本機 socket 緩衝區）。這時 `onSent` 照常執行、開始計時，15 秒內等不到回覆就轉成「結果未知」，已由 `MatchService` 涵蓋。

### 2.3 發訊白名單不放進核心

草稿：只允許私訊 BanchoBot 與已登記的 `#mp_<id>`。

實作：`bancho-irc` 不限制目標。白名單屬於 Discord 前端的規則；shell 只有本機使用者能操作，私訊由使用者自己負責。決定記在 [shell 測試版設計](2026-10-17-shell-tester-release-design.md) 第 2 節。

### 2.4 重連後自動重新 JOIN

草稿：重連完成只代表傳輸恢復，JOIN 房間由受控介面執行，上層核對房況後才恢復操作。

實作：client 記錄已加入的頻道，收到 `001` 後自動重新 JOIN，並排在斷線期間累積的訊息之前。房況核對尚未實作（見第 3 節）。

### 2.5 預設位址

草稿：官方主要位址為 `irc.ppy.sh`，`cho.ppy.sh` 為替代。

實作：`osu.irc.host` 預設 `cho.ppy.sh`，可設定。

## 3. 留待 Discord 前端或日後處理

| 項目 | 說明 |
| --- | --- |
| 出站佇列容量上限 | `outbound` 是沒有上限的 `LinkedBlockingDeque`。目前只有本機 shell 一個人下指令；多人透過 Discord 操作時，要加上限並在滿載時明確拒絕 |
| 重連後核對房況 | match 層沒有監聽連線狀態。重連後應對已知房間查詢房況（例如 `!mp settings`），確認前不恢復房間操作 |
| 禁言偵測 | 沒有樣本。偵測到後要停止所有送出並通知維運者，不自動重試；樣本檔已會收集 Bancho 的 NOTICE 與數字回覆 |
| 送出結果的三種狀態 | 目前只有「已寫出」（`onSent`）與「還在佇列」。若 Discord 版需要回報「確定未送出」（例如關機時佇列裡的指令），再擴充 `IrcSender` |
| 可觀測性 | 草稿要求的佇列深度、送訊錯誤、重連次數、最近收訊時間都還沒有指標；shell 已移除 OTel，留給依賴 `bancho-observability` 的前端 |
