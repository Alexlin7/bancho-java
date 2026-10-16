# 多人房管理（BanchoBot 事件、開房 API、Spring Shell）設計

- 日期：2026-10-16
- 狀態：待使用者審閱

## 1. 目標與範圍

### 目標

在現有的 IRC client 之上，提供「用程式開 osu! 多人房並下裁判指令」的能力，作為之後比賽自動化與 Discord 前端的基礎。

### 成功標準

1. 程式可以呼叫 `createMatch("房名")` 取得房號，並用 `MatchRoom` 執行 invite、map、mods、start、close 等指令。
2. 第 4 節列出的所有 BanchoBot 真實樣本都能正確解析成事件，並有測試覆蓋。
3. 每間房間的狀態（slot 上的玩家、目前的圖與 mod、設定、是否已關閉）會隨事件更新，任何 thread 都能讀到一致的快照。
4. 使用者可以在 Spring Shell 中開房、對房間下指令，參數有 Tab 補全，也能套用設定檔中的房間範本。
5. log 可以透過 OTLP 送到本機 collector；沒有啟用時不會嘗試連線。

### 分兩個階段

| 階段 | 內容 | 完成後可以做到 |
|---|---|---|
| 一：核心 | package by feature 重構、BanchoBot 解析器與事件、`MatchService` / `MatchRoom` / 房間狀態、console 的 `/make` 與 `/rooms`、OTel log | 在現有 console 用 `/make` 開房、自動進入房間頻道、查看房間狀態 |
| 二：Spring Shell | IRC 啟動改用 `SmartLifecycle`、以 Spring Shell 取代 console、Tab 補全、房間設定範本 | 完整的互動式 shell |

階段二的第一步是驗證 Spring Shell 4.0.3 能在 Spring Boot 4.1.1 上執行（見第 9 節），驗證失敗時階段一不受影響。

### 不在這次範圍

- 比賽流程自動化（全員 ready 自動開始、比分記錄、BO 賽制）——之後另寫 spec。
- ready、比賽開始/結束、分數等事件——目前沒有真實樣本，等蒐集到再加入解析器。
- Discord 前端、Maven 多模組拆分。

## 2. Package 結構

```
idv.alexlin7.tw.bancho
├── BanchoApplication
├── irc/        IRC 核心：OsuIrcClient、IrcMessage、IrcText、IrcSender、IrcMessageListener、
│               AuthenticationFailedException、OsuIrcProperties、OsuIrcConfiguration（只搬家）
├── match/      多人房
│   ├── event/  MatchEvent（sealed）與各事件 record
│   ├── BanchoBotParser、BanchoBotListener、MatchService、MatchRoom、MatchState
│   └── preset/ 房間設定範本（階段二）
├── observability/  OpenTelemetry log appender 的安裝
├── console/    階段一保留並新增 /make、/rooms；階段二刪除
└── shell/      Spring Shell 指令與補全（階段二）
```

規則：

- 依賴方向只能是 `shell / console → match → irc`。`irc` 不認識房間，`match` 不認識前端，前端彼此不依賴。之後的 `discord/` 與 `shell/` 平行。
- 只在 feature 內部使用的類別設為 package-private（例如 `BanchoBotParser`、`BanchoBotListener`、`IrcText`）。對外公開的只有 `MatchService`、`MatchRoom`、`MatchState`、事件型別，以及 `irc` 的 `IrcSender`、`IrcMessageListener`、`IrcMessage`。
- 刪除 `OsuChatHandlerService`，由 `BanchoBotListener` 取代。
- 測試的 package 跟著主程式。`FakeIrcServer` 移到 `irc` 測試 package，並改為 public，讓 `match` 的整合測試使用。
- 已知瑕疵：`OsuIrcProperties` 帶 Spring 的 `@ConfigurationProperties`，`irc` 並非完全不依賴 Spring。這只是 annotation，拆模組時再處理。

## 3. 事件傳遞

`BanchoBotListener` 實作 `IrcMessageListener`，在 dispatcher thread 上解析訊息，再以 Spring 的 `ApplicationEventPublisher` 發出 `MatchEvent`。下游用 `@EventListener` 接收。

- Spring 事件預設在發布者的 thread 上同步執行，因此所有 `@EventListener` 都在 dispatcher thread 上依到達順序執行，維持 IRC 層「依序處理」的保證。
- 發布者不持有下游的參考，不會產生循環依賴；新增 Discord 前端只要再寫一個 `@EventListener`。
- 下游的 `@EventListener` 必須很快返回。需要做耗時工作（例如呼叫 Discord API）的 listener，要自行轉交到其他 thread。

## 4. BanchoBot 解析器與事件

### 來源規則

- 只處理 `nick()` 為 `BanchoBot` 的 PRIVMSG。房間內玩家打出相同文字不會被當成事件。
- 私訊：只辨識開房回覆，房號從網址取得。
- 頻道：只處理 `#mp_<數字>` 頻道，房號從頻道名稱取得。

### 事件與真實樣本

所有規則都來自使用者 2026-10-16 實測的 BanchoBot 原文：

| 事件 | 欄位 | 樣本 |
|---|---|---|
| `MatchCreated` | matchId, name | 私訊 `Created the tournament match https://osu.ppy.sh/mp/122021273 測試房` |
| `MatchClosed` | matchId | `Closed the match` |
| `PlayerInvited` | matchId, username | `Invited Alexlin86 to the room` |
| `PlayerJoined` | matchId, username, slot | `Alexlin86 joined in slot 1.`（結尾有句點） |
| `PlayerMoved` | matchId, username, slot | `Alexlin86 moved to slot 2`（結尾沒有句點） |
| `PlayerLeft` | matchId, username | `Alexlin86 left the game.` |
| `SettingsChanged` | matchId, size, teamMode, winCondition | `Changed match settings to 2 slots, HeadToHead, ScoreV2` |
| `BeatmapChanged` | matchId, beatmapId, title | `Changed beatmap to https://osu.ppy.sh/b/1502772 Asriel - Kegare Naki Yume`，以及 `!mp settings` 的 `Beatmap: https://osu.ppy.sh/b/1502772 Asriel - Kegare Naki Yume` |
| `ModsChanged` | matchId, mods, freeMod | `Enabled Hidden, HardRock, disabled FreeMod` |
| `SlotStatus` | matchId, slot, status, userId, username | `!mp settings` 的 `Slot 1  Not Ready https://osu.ppy.sh/u/4841753 Alexlin86       ` |

```java
public sealed interface MatchEvent permits MatchCreated, MatchClosed, PlayerInvited, PlayerJoined,
        PlayerMoved, PlayerLeft, SettingsChanged, BeatmapChanged, ModsChanged, SlotStatus {
    long matchId();
}
```

### 解析決策

- team mode、win condition、mod 名稱、slot 狀態一律存 `String`。目前只看過少數幾個值，做成 enum 需要猜測其他值。
- `!mp settings` 的多行回覆逐行獨立解析，不組合。`Room name: ...`、`Team mode: ...`、`Players: ...` 三行不產生事件。
- 使用者名稱可能含空白：regex 以名稱後面的固定文字（例如 ` joined in slot `）作為名稱的結束，並先去除行尾空白。
- 每條 regex 都必須比對整行。
- 未符合任何規則的 BanchoBot 訊息不產生事件，以 INFO 記錄（見第 7 節），作為蒐集新樣本的管道。

`BanchoBotParser` 是不依賴 Spring 的純函式：`Optional<MatchEvent> parse(IrcMessage)`，內部為依序比對的「regex → 事件」規則清單。

## 5. `MatchService`、`MatchRoom`、`MatchState`

### `MatchService`

```java
CompletableFuture<MatchRoom> createMatch(String name);
MatchRoom attach(long matchId);
Optional<MatchRoom> room(long matchId);
List<MatchRoom> openRooms();
```

- `createMatch`：在 `pending` 登記房名與未完成的 future → 對 `BanchoBot` 送 `!mp make <name>` → 收到同名的 `MatchCreated` 時，建立並登記 `MatchRoom`、`join #mp_<id>`、完成 future。
- 同一房名已在 `pending` 中時，立即拋 `IllegalStateException`，不送出指令。房名為空白或含換行時拋 `IllegalArgumentException`。
- 逾時：預設 15 秒（`bancho.match.create-timeout`）。逾時後 future 以 `TimeoutException` 失敗並移出 `pending`。之後才到的 `MatchCreated` 仍會登記房間，但不再完成該 future。
- 收到任何房號尚未登記的事件時，自動登記該房間（名稱未知時為 `null`）。因此手動 `!mp make` 或手動 `join` 的房間也會被追蹤。`attach(id)` 等於 `join #mp_<id>` 再登記房間。
- 收到 `MatchClosed`：把狀態標記為已關閉、從 `openRooms()` 移除、`part #mp_<id>`。
- 每次收到 `MatchCreated` 都會登記房間並 `join #mp_<id>`，不論是否有對應的 pending 請求（例如使用者手動送 `!mp make`，或請求已逾時）。有對應的 pending 請求時才完成其 future。

### `MatchRoom`

```java
long matchId();
String channel();                       // "#mp_<id>"
MatchState state();
void invite(String username);
void set(int teamMode, int scoreMode, int size);
void map(long beatmapId);
void mods(String... mods);
void start();
void start(Duration delay);
void timer(Duration duration);
void abort();
void close();
void password(String password);         // null 或空白代表移除密碼
void addRef(String... usernames);
void settings();
void say(String text);                  // 在房間頻道說一般的話
```

- 每個方法組出對應的 `!mp` 指令，透過 `IrcSender` 送到 `channel()`。
- 參數檢查：teamMode 與 scoreMode 為 0–3、size 為 1–16、mod 名稱不得含空白、使用者名稱不得為空白，違反時拋 `IllegalArgumentException`。
- 房間已關閉時，任何指令都拋 `IllegalStateException`。
- 使用者名稱中的空白照原樣送出。BanchoBot 是否接受含空白的名稱尚未實測，屬於第 9 節的待驗證事項。

### `MatchState`

不可變 record：房名、是否已關閉、size、teamMode、winCondition、beatmap（id 與標題）、mods、freeMod、`Map<Integer, Slot> slots`。`Slot` 為 `(username, userId, status)`，`userId` 與 `status` 只有 `SlotStatus` 事件會帶，否則為 `null`。

事件套用規則：

| 事件 | 對狀態的影響 |
|---|---|
| `PlayerJoined`、`PlayerMoved` | 先把同名玩家從其他 slot 移除，再放進新 slot |
| `PlayerLeft` | 移除該玩家 |
| `SlotStatus` | 覆蓋該 slot |
| `SettingsChanged` | 更新 size、teamMode、winCondition |
| `BeatmapChanged` | 更新 beatmap |
| `ModsChanged` | 更新 mods 與 freeMod |
| `MatchClosed` | closed = true |
| `PlayerInvited`、`MatchCreated` | 不改變 slot |

執行緒安全：事件只在 dispatcher thread 上套用，每次產生新的 `MatchState` 存入 `volatile` 欄位。其他 thread 讀到的一定是完整快照，不需要加鎖。

## 6. 前端

### 階段一：console

- `/make <房名>`：呼叫 `createMatch`，成功時印出 `房間已建立：#mp_<id> <房名>` 並把目前對象切到該房間；失敗時印出原因。
- `/rooms`：列出開著的房間與其 slot、圖、mod。
- `currentTarget` 改為 `volatile`，因為 `/make` 的結果由 dispatcher thread 回呼。

### 階段二：Spring Shell

依據 Spring Shell 4 文件與 migration guide：指令使用 `@Command` 與 `@Option`；補全以指令為單位，透過 `@Command(completionProvider = "...")` 指定 `CompletionProvider` bean；`JLineShellRunner` 提供歷史紀錄與 Tab 補全；測試使用 `spring.shell.interactive.enabled=false` 或 `@ShellTest` 搭配 `ShellTestClient`。

**IRC 啟動時機：** 若 Spring Shell 透過 runner 進入互動迴圈，`ApplicationReadyEvent` 會在 shell 結束前都不發生，IRC 就不會連線。因此階段二先把 IRC 的自動啟動改為 `SmartLifecycle`（在 runner 之前啟動），保留 `osu.irc.auto-start` 開關。

指令（房間相關的指令預設作用在目前房間，可用 `--room <房號>` 指定其他房間）：

```
make <房名> [--preset <範本>]
attach <房號>
use <房號>
rooms
invite <玩家>    map <beatmapId>    mods <mod...>    set <teamMode> <scoreMode> <size>
start [--delay <秒>]    timer [<秒>]    abort    close    settings    password [<密碼>]    addref <玩家...>
say <文字>
msg <對象> <文字>
```

補全來源：

| 參數 | 候選值 |
|---|---|
| `--room`、`use` 的房號 | 開著的房號，描述為房名 |
| `invite`、`addref` 的玩家 | `KnownUsers`：本次執行中出現在任何房間 slot、私訊、範本邀請名單的名稱 |
| `mods` | mod 縮寫，實作時依 osu! wiki 的 Game modifier 頁面整理 |
| `--preset` | 設定檔中的範本名稱 |

`KnownUsers` 屬於 `shell` 套件，同時監聽 `MatchEvent` 與 IRC 訊息。

收到的 IRC 訊息改由 shell 的 listener 印出，使用 JLine 的 `LineReader.printAbove()`，避免打亂正在輸入的那一行。若 Spring Shell 4 無法取得 `LineReader`，退回直接輸出。完成後刪除 `console` 套件。

### 房間設定範本（`match/preset`）

```yaml
bancho:
  match:
    presets:
      practice:
        team-mode: 0
        score-mode: 3
        size: 2
        password: abc       # 選填
        mods: [HD, HR]      # 選填
        beatmap: 1502772    # 選填
        referees: []        # 選填
        invite: [Alexlin86] # 選填
```

`MatchRoom.apply(MatchPreset)` 依 `set → password → mods → map → addref → invite` 的順序送出，未填的欄位跳過。指令經過出站佇列，自動遵守速率限制。

## 7. Log 與 OpenTelemetry

依據 Spring Boot 4.1.1 官方文件與 Maven Central：

- 以 `spring-boot-starter-opentelemetry` 取代 `spring-boot-starter-micrometer-metrics` 與 `micrometer-registry-otlp`。
- 新增 `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`。Boot 不管理此版本，需自行指定（目前最新為 `2.32.0-alpha`，帶 alpha 標記）。
- 新增 `logback-spring.xml`：沿用 Boot 預設的 console 輸出，加上 `OpenTelemetryAppender`。
- `observability` 套件新增一個 bean，在啟動時呼叫 `OpenTelemetryAppender.install(openTelemetry)`。

匯出設定：統一走 HTTP（collector 的 4318）。理由是 Micrometer 的 OTLP metrics 只支援 HTTP（設定中沒有 transport 選項），全用 HTTP 只需要維護一種協定。

- 預設不匯出 log 與 metrics。
- 新增 `otel` profile（`application-otel.properties`）：
  ```properties
  management.opentelemetry.logging.export.otlp.endpoint=http://localhost:4318/v1/logs
  management.otlp.metrics.export.enabled=true
  management.otlp.metrics.export.url=http://localhost:4318/v1/metrics
  ```
  以 `--spring.profiles.active=otel` 啟用。

log 規範（使用 SLF4J 2 key-value API，OTel appender 會轉成 attribute）：

```java
log.atInfo().addKeyValue("match.id", id).addKeyValue("match.event", "PlayerJoined").log("[match] ...");
```

- 房間事件：INFO，帶 `match.id`、`match.event`。
- 未辨識的 BanchoBot 訊息：INFO，帶 `match.id`（私訊為空）與 `banchobot.text`。
- IRC 原始訊息只在 DEBUG 記錄；密碼不得出現在任何 log。
- log 前綴依 feature 區分：`[osu! IRC]`、`[match]`、`[shell]`。

## 8. 錯誤處理

| 情境 | 處理 |
|---|---|
| `createMatch` 逾時 | future 以 `TimeoutException` 失敗，移出 `pending`；前端顯示「開房逾時」 |
| 同名請求已在等待 | `IllegalStateException`，不送指令 |
| 對已關閉的房間下指令 | `IllegalStateException`，不送指令 |
| 參數不合法 | `IllegalArgumentException`；前端顯示訊息後繼續等待輸入 |
| 未辨識的 BanchoBot 訊息 | INFO log，不產生事件 |
| 斷線重連 | IRC 層自動重新 join 所有房間頻道；狀態保留斷線前的快照，斷線期間的事件會遺漏，必要時以 `settings` 重新同步 |
| 某個 `@EventListener` 拋例外 | 由 IRC dispatcher 記錄 ERROR，不影響後續訊息 |

## 9. 待驗證事項

| 項目 | 驗證時機 | 驗證失敗時 |
|---|---|---|
| Spring Shell 4.0.3 能在 Boot 4.1.1 上啟動與執行指令 | ✅ 已驗證（2026-10-16） | 可以；需另加 `spring-shell-jline` |
| Spring Shell 的互動迴圈是否阻擋 `ApplicationReadyEvent` | ✅ 已驗證 | 會（shell 是 `ApplicationRunner`），已改用 `SmartLifecycle` |
| 能否取得 JLine `LineReader` 以使用 `printAbove()` | ✅ 已驗證 | 可以（bean `lineReader`） |
| Spring Shell 的 `quit` 會關閉 context，IRC 正常送出 QUIT | ✅ 已驗證 | 需 `spring.main.keep-alive=false`；`spring.shell.context.close=true` 會 exit 1，不採用 |
| 未設定 `otel` profile 時，OTel starter 不會嘗試匯出 log、metrics、traces | ✅ 已驗證（階段一） | 不會 |
| BanchoBot 對 `!mp invite` 含空白的名稱是否有效 | 使用者實測 | 改為把空白換成底線 |
| 超過 4 間房時 BanchoBot 的回覆 | 使用者實測 | 目前以逾時處理 |

## 10. 測試策略

| 對象 | 內容 | 方式 |
|---|---|---|
| `BanchoBotParser` | 每則真實樣本對到正確事件；非 BanchoBot 的相同文字不被解析；未知訊息回傳 empty | 參數化單元測試，測資為 IRC 原文 |
| `MatchRoom` | 指令字串、參數檢查、已關閉時拒絕、事件套用後的狀態 | 單元測試，搭配記錄呼叫的假 `IrcSender` |
| `MatchService` | 房名對應、逾時、同名拒絕、自動登記未知房間、關房後移除並 part | 單元測試，手動餵入事件 |
| 回放測試 | 把使用者兩次實測的完整紀錄存成 IRC 原文檔，經由 `FakeIrcServer` 依序送入，驗證最終房間狀態 | Spring context 整合測試 |
| 房間範本 | 指令順序、未填欄位跳過、設定綁定 | 單元測試與 `ApplicationContextRunner` |
| Spring Shell | 指令呼叫正確的 `MatchRoom` 方法；`CompletionProvider` 的候選值 | `@ShellTest` 與單元測試 |
| OTel | 未啟用 `otel` profile 時 context 正常啟動、沒有 exporter 嘗試連線 | `ApplicationContextRunner` |

真實樣本存放於 `src/test/resources/banchobot/`，之後蒐集到的新樣本直接加入同一處。
