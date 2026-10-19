# osu! Discord Bot：帳號、房間生命週期與權限設計

日期：2026-10-15
狀態：對話中的設計已確認；本文件待檢閱。
範圍：需求說明書第 2～5 項補充議題。技術選型與實作計畫尚未討論。

> 本文件是 `bancho-irc` 實作前（2026-10-15）的 Discord 版規劃，2026-10-19 收錄為參考文件。以下內文維持當時原樣，不反映後來的實作與樣本；現況以下一節「與目前實作的對照」為準。

## 與目前實作的對照（2026-10-19）

### 已實作（shell 前端，狀態只在記憶體）

| 本文件 | 目前實作 |
| --- | --- |
| 已開房間、建房中、結果未知共用 4 個名額，轉為房間時不重複計數 | `MatchService.MAX_ROOMS`；`attach` 與被動登記的房間也算 |
| 15 秒從 IRC 實際送出起算，不含排隊時間 | `IrcSender.sendMessage(target, message, onSent)`，`onSent` 在 sender thread 寫出後才執行 |
| 逾時進入結果未知、保留名額，不自動重送 | 相同；晚到的同名回覆照常接上，但不切換 shell 的目前房間 |
| 確認失敗才釋放名額 | 沒有已知的拒絕訊號，改由使用者 `drop` 手動釋放；drop 過的房名本次執行不能再用 |
| 關房以 `Closed the match` 為準，PART 不能當證據 | `MatchClosed` 才釋放名額並 part，從不以 PART 判定關房 |
| 半死連線視同斷線 | `read-timeout` 內沒收到資料就主動 PING，再次逾時即重連 |
| 限速保留 20% 餘裕 | `send-interval` 預設 625ms（個人帳號每 5 秒 10 則的 80%） |
| 密碼不出現在日誌 | `!mp password` 在 log、樣本檔、指令歷史都會遮蔽或不寫入 |

### 刻意偏離

- **方案 A 的「結果未知時暫停後續建房」**：shell 只拒絕同一個房名，其他房名照常建房，等於提前採用方案 B 以房名配對。4 筆實測樣本（含中文、日文房名）都確認建房回覆帶房名。詳見 [shell 測試版設計](2026-10-17-shell-tester-release-design.md) 3.2 節；Discord 版是否沿用尚未決定。
- **發訊白名單**：不放進 IRC 核心，也不套用到 shell（本機使用者自行負責私訊），留給 Discord 前端。

### 尚未實作

OAuth 綁定、Discord 權限表、持久化與重啟復原、稽核與人工復原程序、關房的「關閉中」狀態、重連後核對房況（目前只自動重新 JOIN）、referee 部分失敗補救、禁言偵測、服務狀態頁。

### 回覆格式的證據等級更新

樣本在 `bancho-match/src/test/resources/banchobot/`，內文中標為「來源為第三方原始碼，實作前須實測」的格式，現況如下：

| 格式 | 現況 |
| --- | --- |
| `Created the tournament match https://osu.ppy.sh/mp/<id> <房名>` | **已實測**（4 筆，含中文、日文房名），由 `BanchoBotParser` 解析 |
| `Closed the match` | **已實測**（2 筆），解析為 `MatchClosed` |
| `User not found: <name>` | **已實測**（3 筆，`#mp_` 頻道內）；樣本沒有記錄送出的指令，推測是 `!mp addref` 的回覆；parser 尚未解析 |
| `You have too many referees! Remove some to add new ones.` | **新發現**，本文件未列；同一段樣本，推測同樣來自 `!mp addref`；parser 尚未解析 |
| `Added <name> to the match referees`、`Removed <name> from the match referees` | 仍未實測 |
| `No such channel` | 仍未實測 |
| 建房被拒、禁言系統訊息 | 仍無任何樣本；樣本檔會收集 Bancho 的 NOTICE 與數字回覆 |

## 目的與邊界

使用者透過 Discord 綁定 osu! 帳號，並由唯一承載帳號經 IRC 管理 osu!stable 錦標賽房。使用者不提供 osu!/IRC 密碼。第一版以保守處理未知結果為原則，避免重複建房、超額占用或越權操作。

本文件補充原需求，不改動 lazer、計分、圖池與賽程管理不在本期的界線。每帳號 4 間錦標賽房、每房 referee 上限 8 位為官方 !mp 限制（見參考），其他外部限制仍須在實作前核對。

本系統為使用者主導模式：建房者經 !mp addref 成為 referee，自行管理房間；bot 擔任裁判的配對／天梯模式不在本期。此前提為第 4 節權限表的依據。

## 1. OAuth：一次驗證，不長期保存 token

- 使用 identify scope，取得授權使用者身分。
- 保存 Discord user ID、osu! user ID、綁定時間與顯示名稱快取。名稱不作為權限依據。
- Discord 與 osu! 帳號一對一綁定；不得直接覆蓋其他使用者既有綁定。
- OAuth state 必須短效、一次性，並對應發起 /link 的 Discord 使用者。
- token 僅供本次驗證，不寫入資料庫或日誌；不保存 refresh token。
- /unlink 刪除本地綁定，不宣稱等同撤回 osu! 網站上的 OAuth 授權。
- 使用者在 osu! 撤回授權，不會自動解除本地綁定。
- 有未關房間、referee 關係或排隊請求時，阻擋解除／更換綁定，提示先處理關係。

## 2. 建房：第一版採方案 A

每筆建房有本地請求 ID，保存發起人、實際送出時間、狀態及取得的 match ID。此 ID 不代表 Bancho 能回傳它。

狀態至少區分：排隊、建房中、結果未知、成功、確認失敗。建房前先持久化請求並預留名額。

1. 建房一次只處理一筆，所有指令仍經統一限速佇列。
2. 15 秒計時從 IRC 實際送出開始，不包含佇列等待。
3. 逾時進入結果未知，保留名額並暫停後續建房；既有房間可繼續管理。
4. 可明確配對的延遲成功回覆，接續加入頻道與新增 referee。
5. 只有確認失敗才能釋放預留名額。時間經過、重連或重啟都不構成失敗證據。
6. 無法確認時通知維運者，不自動重送建房。

已開啟房間、未完成建房與結果未知請求共用四個名額，同一請求轉為房間時不可重複計數。重啟後不得自動重播可能已送出的建房指令。

### 未來方案 B

保留獨立請求狀態、名額預留、集中回覆配對與復原流程，供未來隔離未知請求後繼續建房。

啟用 B 前必須驗證可靠的跨系統配對方式；本地請求 ID、回覆順序或等待超過 15 秒都不能單獨解決配對。第一版不實作 B。

第三方原始碼記載的 BanchoBot 建房成功回覆格式為 `Created the tournament match https://osu.ppy.sh/mp/<id> <房名>`（來源為第三方原始碼，實作前須實測）。該格式包含房名，因此以房名嵌入請求 ID（如 `房名 #r123`）在格式上可行，列為 B 的首要配對候選；實際回覆及配對可靠性仍需實測。

依所引用的 bancho.js commit，createLobby() 僅以 regex 擷取 match ID，不比對房名，逾時後也不移除 listener；延遲回覆可能被下一筆請求誤配。此為第三方原始碼行為紀錄，實作前須核對與實測。方案 A「逾時後暫停後續建房」可避免此類下一筆請求誤配。

## IRC 傳輸限制

- 限速：個人帳號每 5 秒 10 則，bot 帳號每 60 秒 300 則；實際送出保留餘裕，暫訂 20%。
- 發訊白名單：只允許私訊 BanchoBot 與 #mp_<id> 頻道，由傳輸層強制執行，拒絕其他目標。
- 依 osu! bot 帳號規範，不發公開頻道訊息、不發未經請求的私訊（見參考）。

## 3. 關房與斷線復原

- 關房先標為關閉中，停止新的房間操作，再送出 !mp close。
- 確認關閉後才釋放名額並推進排隊；逾時或斷線則保留名額，標為關閉結果待確認。
- 關房確認以房間頻道內 BanchoBot 的 `Closed the match` 訊息為準（來源為第三方原始碼，實作前須實測）。
- bot 自己被移出 #mp_<id>（PART）不能單獨作為關房證據。依所引用的 bancho.js commit，onClose 在斷線時會對所有頻道觸發 PART，而 closeLobby() 以 PART 判定成功，可能將斷線誤判為關房完成；此為第三方原始碼行為紀錄，實作前須核對與實測。
- 重複 /mp close 回覆目前進度，不累積關房工作。
- 閒置自動關房走同一流程。
- IRC 斷線時暫停送出指令與新建房，保存待處理狀態。
- 重連後重新加入已知房間頻道，核對房況與 referee；不能僅因 JOIN 失敗就判定關房。
- 確認仍開啟則恢復管理；原本正在關房者接續關房。確認已關閉才釋放名額。
- 無法確認時保留名額、暫停新建房並通知維運者。
- 斷線期間不累計閒置時間；恢復可靠玩家狀態後重新計時。

## 人工復原的證據規則

維運者確認狀態、釋放名額時，需具備下列證據之一，並將證據、判斷與處理結果記錄於稽核：

- 建房結果未知 → 確認成功：BanchoBot 私訊紀錄中有 `Created the tournament match ... <房名>`（來源為第三方原始碼，實作前須實測），且房名能對應到該請求。
- 建房結果未知 → 確認失敗：BanchoBot 明確的拒絕回覆（格式未知，見第 7 節）。沒有此證據時，只能在確認承載帳號名下的房間數後，由維運者判斷並留下理由，不得僅以逾時判定。
- 關房結果未知 → 確認關閉：取得 `Closed the match` 訊息（來源為第三方原始碼，實作前須實測），或 JOIN #mp_<id> 收到 `No such channel`（來源為第三方原始碼，實作前須實測）並搭配第二個獨立訊號，例如官方比賽頁面顯示已結束（能否作為關閉證據待驗證）。僅 JOIN 失敗不足以判定。

## 非斷線型故障

- 半死連線：N 秒內未收到 Bancho 任何訊息或 PONG，視同斷線，走第 3 節流程。
- 禁言：偵測 Bancho 禁言系統訊息後，立即暫停所有送出、標記故障並通知維運者；不自動重試。

## 4. Discord 權限

Bot 房主是 Discord 建房者；遊戲內 host 的移轉不移轉 Bot 房主權限。

| 操作 | Bot 房主 | 該房 referee | 其他已綁定者 |
| --- | --- | --- | --- |
| 設定、換圖、mods、size | 可 | 可 | 不可 |
| 邀請、開始、中止、計時 | 可 | 可 | 不可 |
| 查看／變更密碼 | 可 | 可 | 不可 |
| 新增／移除 referee | 可 | 不可 | 不可 |
| 關房 | 可 | 可（referee 可關房為相對原需求 FR-09 的變更，待確認；Bancho 允許 referee 執行 !mp close，見官方 wiki，因此為純產品決策，Discord 端開放與否都不與 Bancho 行為衝突） | 不可 |
| 查看清單與排隊狀況 | 可 | 可 | 可，受私人房資訊限制 |

- Discord 管理員不自動取得管房權限。
- 維運者有明確授予的復原／強制關房權限，操作必須稽核。
- 透過 Bot 新增 referee 時，對方必須已綁定。
- 移除 referee 期間先停用其 Discord 管房權限，確認 Bancho 移除成功後才完成操作。
- 直接從遊戲內執行的操作也可能改變房況；本地狀態不能被當作永久正確。

## referee 部分失敗補救（提案，待確認）

- 建房成功但 addref 失敗時，保留房間，不自動關房。承載帳號是房間建立者，Discord 指令仍可代為管房；失敗只影響遊戲內直接操作，關房反而會浪費一次建房與一個名額。
- 流程：房間標記為「referee 待補」→ 有限次數重試 addref（次數待定）→ 仍失敗則通知建房者，由其決定是否關房。
- addref 成功以房間頻道內 `Added <name> to the match referees` 為準（來源為第三方原始碼，實作前須實測）；失敗訊號為 `User not found: <name>`（來源為第三方原始碼，實作前須實測）。
- 不得以訊息送出成功視為 addref 成功。依所引用的 bancho.js commit，addRef() 在送出後即 resolve，未等待確認；此為第三方原始碼行為紀錄，實作前須核對與實測。
- removeref 成功以 `Removed <name> from the match referees` 為準（來源為第三方原始碼，實作前須實測），並套用第 4 節「移除期間先停用權限」的規則。

## 5. 密碼與私人房資訊

- 建房結果、查看／變更密碼使用 Discord 僅本人可見回覆。
- 公開事件與 /mp list 不含密碼。
- 無權限者只看見私人房名額占用，不看見房名、連結或房主。
- 必須保存的房間密碼加密保存；稽核、錯誤及指令日誌遮蔽密碼。
- 遊戲內改密碼可能使快取失效；無法確認時提示重新設定，不將舊值宣稱為現行密碼。

## 6. 職責與驗證情境

職責分為帳號驗證、房間狀態與名額、IRC 傳輸與回覆配對、權限檢查及 Discord 回報；此處不指定函式庫或部署技術。

實作時至少驗證：
- OAuth state 過期、重用、不同 Discord 使用者及重複綁定。
- 建房逾時後延遲成功、明確失敗、斷線及重啟，不重複建房或誤釋放名額。
- 關房逾時、重複關房與重連，確認前不推進排隊。
- 四房容量含未知請求，狀態轉換不重複計數。
- 各角色越權、解除綁定阻擋與 referee 移除中的權限。
- 密碼不出現在公開回覆或日誌；斷線不觸發錯誤閒置關房。
- 半死連線：N 秒內無 Bancho 訊息或 PONG 時，進入第 3 節斷線復原流程。
- 禁言：收到 Bancho 禁言系統訊息後立即停止所有送出、標記故障並通知維運者，且不自動重試。

## 7. 實作前需驗證的外部行為

以下是驗證工作，不代表已有可靠能力。依證據來源分為三類：

### 7.1 有第三方原始碼佐證，仍需實測格式

- 建房成功回覆（含房名）：`Created the tournament match https://osu.ppy.sh/mp/<id> <房名>`（來源為第三方原始碼，實作前須實測）。
- 關房回覆：`Closed the match`（來源為第三方原始碼，實作前須實測）。
- referee 新增／移除回覆：`Added <name> to the match referees`、`Removed <name> from the match referees`（兩者來源為第三方原始碼，實作前須實測）。
- 找不到使用者：`User not found: <name>`（來源為第三方原始碼，實作前須實測）。
- JOIN 時不存在頻道：`No such channel`（來源為第三方原始碼，實作前須實測）。
- 驗證上述訊息的來源、頻道與請求配對方式；以房名嵌入請求 ID（如 `房名 #r123`）列為方案 B 的首要配對候選。B 的唯一識別與房況核對驗證，不阻擋採人工復原的 A。

### 7.2 無任何佐證，必須實測

- 建房被拒（如超過 4 房上限）的回覆格式。
- 禁言系統訊息。

「確認失敗」狀態目前沒有已知訊號可以觸發；在取得被拒回覆格式之前，建房失敗只能進入「結果未知」。人工處理須遵守「人工復原的證據規則」，不得僅以逾時判定失敗。

### 7.3 官方比賽頁面或 API 的關閉判定能力與延遲

- 官方比賽頁面能否確認房間已關閉，以及資料延遲；須區分比賽結束與房間關閉。
- osu! API v2 match 端點能否確認房間存在與關閉、資料延遲多久，作為重連核對的候選訊號。

其餘既有驗證範圍仍保留：重連後的玩家狀態確認、!mp listrefs 回覆及遊戲內異動同步、具證據的人工復原程序，以及 referee 新增／移除部分失敗的詳細補救流程。部分失敗補救仍為提案，尚未定案。

## 服務狀態頁（NFR-09）

| 元件 | 健康判定條件 |
| --- | --- |
| IRC 連線 | heartbeat：N 秒內有 Bancho 訊息／PONG，且未禁言。 |
| 建房功能 | heartbeat：最近一次建房成功，或期間內無請求；進入「結果未知」時停止送出建房指令。 |
| Discord Bot | heartbeat：gateway 連線正常。 |
| OAuth 綁定網站 | 外部 HTTP 檢查。 |

- 由 bot 自行判斷健康後主動送 heartbeat，因為 process 存活不代表能開房；OAuth 綁定網站另由外部 HTTP 檢查。
- 監控端與 bot 部署於不同主機；狀態頁附 https://status.ppy.sh 作為外部依賴。
- 工具選擇與門檻 N 列為待決事項，不在本文件定案。

## 參考

- 原始需求：C:/Users/2400819/Downloads/osu! Discord 開房 Bot 需求說明書.md
- OAuth：https://osu.ppy.sh/docs/index.html#authentication
- Bot account wiki：https://osu.ppy.sh/wiki/en/Bot_account
- 錦標賽指令：https://osu.ppy.sh/wiki/en/osu!_tournament_client/osu!tourney/Tournament_management_commands

- wyReferee（commit 07092d2）：https://github.com/wesley-221/wyReferee
- bancho.js（commit 041fde2）：https://github.com/ThePooN/bancho.js
  - 相關位置：lib/BanchoClient.js（createLobby、onClose）、lib/Multiplayer/BanchoLobby.js（addRef、closeLobby）、lib/Multiplayer/BanchoLobbyRegexes.js（referee 相關回覆）、lib/IrcCommands/ChannelNotFoundCommand.js

本文件不含技術選型、依賴安裝、產品程式碼或實作計畫。
