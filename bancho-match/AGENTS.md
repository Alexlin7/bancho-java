# bancho-match

`match` 套件：多人房的解析、狀態與 `!mp` 指令。純 Java，compile 依賴只有 `bancho-irc` 與 `slf4j-api`，不可加入 Spring。

## 類別

- **`BanchoBotParser`**：純函式，把 BanchoBot 的 PRIVMSG 解析成 `Result`（`Parsed` / `Ignored` / `Unrecognized` / `NotFromBanchoBot`）。只信任 nick 為 `BanchoBot` 的訊息；私訊只辨識開房回覆，頻道訊息只處理 `#mp_<id>`。每條 regex 必須比對整行。
- **`event.MatchEvent`**：sealed interface，事件都是巢狀 record。team mode、mod 名稱等維持 BanchoBot 原文字串，不轉 enum（沒有完整樣本）；玩家隊伍使用 `Team`（`BLUE`／`RED`，`parse` 不分大小寫且只接受 blue／red），供前端分組。`PlayerJoined` 的 `team` 可為 null，保留三參數建構子；`SettingsChanged` 的 null 欄位表示此次未更新。
- **`BanchoBotListener`**：`IrcMessageListener`，解析後依清單順序通知每個 **`event.MatchEventListener`**（例外只記 log，不影響其他 listener）；`MatchService` 必須排第一個，其他 listener 查到的才是更新後的房間。看不懂的 BanchoBot 訊息記 INFO（帶 `match.id`、`banchobot.text`），是蒐集新樣本的管道。
- **`MatchService`**：
  - `createMatch(name)` 回傳 `MatchCreation`（`room` 在收到同名回覆時完成，逾時後晚到也會完成；`unknown` 在轉為結果未知時完成），以三參數 `sendMessage` 送 `!mp make`，**實際送出後**才開始計時（預設 15 秒，`bancho.match.create-timeout`），逾時轉為結果未知、繼續佔用名額。計時器只作用在同一個仍在建房中的請求。
  - 名額上限 `MAX_ROOMS`＝4，未關閉的房間（含 `attach`、被動登記）與建房中、結果未知的請求共用。同名請求在建房中或結果未知時拒絕，其他房名照常可開。
  - `drop(name)` 釋放結果未知的請求，該房名在本次執行中不能再用（舊回覆可能晚到，不能配給新請求）。
  - 實作 `MatchEventListener`：收到 `MatchCreated` 一律登記並 join，收到其他未登記房號的事件只登記不 join，`MatchClosed` 時 part。
- **`MatchRoom`**：封裝 `!mp` 指令。`invite`、`host`、`team`、`addRef`、`set` 回傳實際入列的指令，前端直接拿來顯示，不重複解析。`team(player, team)` 使用 `PlayerTarget` 解析一位玩家，顏色以小寫放在目標之後；入列不代表 BanchoBot 已確認換隊。`mods` 會擋下 `NFHD` 這類連寫（BanchoBot 看不懂會清空所有 mod）；可接受的長名稱與 shell 的 mod 補全共用 **`ModNames.KNOWN`**。
  - `addRef` 一次可給多位，每位各送一條 `!mp addref`，依輸入順序回傳 `List<String>`；先檢查全部目標，任一不合法時一條都不送。範本 `referees` 也逐位發送，仍排在 map 之後、invite 之前。
  - 官方文件列有多目標語法；設計計畫記載 10-20 實測「名稱＋#ID」「多個 #ID」寫在同一行都回 `User not found: <整串>`，因此統一逐位發送。原始 10-20/21 樣本尚未提供，樣本匯入與裁判 session 回放仍 pending，此處不新增解析規則。
- **`MatchState`**：不可變 record（`gameMode` 為 BanchoBot 回覆的原文，例如 `CatchTheBeat`），存在 `AtomicReference`，事件只在 dispatcher thread 上套用，任何 thread 讀到的都是完整快照。
  - `Slot.team`、`host` 未知時為 null；`Slot` 保留三參數建構子（team = null）。換 slot 保留同名玩家的 userId、status 與 team；`SlotStatus` 更新詳細欄位時依同名玩家保留 team，不能繼承目的 slot 前一位不同玩家的 team。
  - `PlayerTeamChanged` 更新已知玩家隊伍，未知玩家忽略；`HostChanged` 記錄房主，房主離開清空，其他狀態更新保留 host。
  - 部分設定沿用未提供欄位；已知非隊伍模式清空所有 team，改成隊伍模式不猜隊伍，未知模式也不清除已知隊伍。`TeamMode.hasTeams()` 只對 `TEAM_VS`／`TAG_TEAM_VS` 回 true。
  - 上述隊伍、host 與部分設定能力以合成事件測試；對應新 parser 規則待真實樣本，尚未接上真實 IRC。10-19／10-21 隊伍與 host session 回放仍 pending；`!mp settings` 的隊伍模式 slot 行也無樣本，不能推測隊伍。
- **`PlayerTarget`**：invite、host、team、addref 與範本 referees／invite 共用的玩家目標解析，每次只解析一位玩家。接受完整 Unicode 名稱（可含空白，不做全半形正規化，純數字仍是名稱）、`#ID`、osu! 個人頁網址（`/users/ID` 或舊 `/u/ID`，可省略 `https://`、可帶 `/osu` 等模式路徑），網址轉成 `#ID`。名稱型網址提示改用數字 ID 或名稱；拒絕無效 ID、非 osu.ppy.sh 網址、控制字元、名稱中夾帶 `#`，以及以 `#ID` 或網址開頭又帶其他目標（「一次只能指定一位玩家」）。`#` 檢查在網址解析之後，個人頁網址的 fragment 不受影響。
- **`BanchoMode`**：`GameMode`（`map` 的遊玩模式）、`TeamMode`、`ScoreMode` 三個 enum 共用的編號／別名解析（不分大小寫），別名定義在各 enum。送給 BanchoBot 的一律是數字。
- **`BanchoRedaction`**：`!mp password <x>` 的遮蔽規則（不分大小寫），由 `match.spring` 設為 client 的 outbound redactor。

## `preset`：房間設定範本

`MatchPreset` 欄位都可省略：

- `teamMode`、`scoreMode`、`size` 三者必須一起設定；`teamMode`／`scoreMode` 接受數字或 `BanchoMode` 別名（建構子傳字串）。
- `password`、`mods`、`beatmap`、`mode`（需搭配 `beatmap`，接受 0–3 或模式別名）、`referees`、`invite`（以 `PlayerTarget` 解析）。

`MatchRoom.validatePreset` 與 `apply` 共用同一份指令組裝（`presetCommands`），檢查設定範圍、模式、mods（含連寫）、referees 與 invite，錯誤附範本與欄位名稱。`apply` 依 set → password → mods → map → addref → invite 送出，未填的跳過。

## BanchoBot 樣本

- 真實樣本放在 `src/test/resources/banchobot/`（`.irc` 檔的行尾空白是原文的一部分）。
- 測試者回傳的 `samples-<日期>.irc` 可直接匯入，匯入前把其中的使用者 nick 改成 `test_user`。
- 新增解析規則前先加樣本與測試，不要憑記憶寫格式。`MatchReplayIntegrationTest` 會把樣本整段回放進系統。
