# 建房名額、結果未知與 shell 測試版發布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 補上建房名額與「結果未知」，並把 Spring Shell 包成朋友解壓縮就能用、會留下 BanchoBot 樣本檔的測試版，由 `shell-v*` tag 觸發 GitHub Actions 發版。

**Architecture:** `bancho-irc` 新增延後登入（`login`）、連線狀態通知與原始進出站行觀察，都是新增 API、不破壞既有用法；`bancho-match` 的 `MatchService` 管名額與建房請求狀態；`bancho-shell` 加 `login`／`drop` 指令、未登入時擋下送訊指令、把篩選過的原始行寫進樣本檔；打包用 jpackage 腳本，workflow 在 Windows runner 上執行。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring Shell、JUnit 5／AssertJ／Awaitility、jpackage、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-10-17-shell-tester-release-design.md`

## Global Constraints

- `bancho-irc` 不得依賴 Spring；變更只新增 API，「建構時給齊帳密、`start()` 連線」的既有用法與錯誤訊息（`username 不得空白`）維持不變。
- 名額上限 4；已開的房間（含 `attach`、被動登記）、建房中、結果未知的請求共用。
- 密碼不得出現在 log、樣本檔、指令歷史；`PASS` 行不得交給任何觀察者。
- 樣本檔只收：BanchoBot 的 PRIVMSG／NOTICE、伺服器 NOTICE、數字回覆、我們送給 BanchoBot 或 `#mp_` 的 PRIVMSG（`!mp password <x>` 遮蔽為 `!mp password ***`）。行尾空白保留。
- 未登入時送 IRC 的指令一律回 `錯誤：請先 login`。
- 程式註解、log、使用者訊息用繁體中文；log 用 `@Slf4j` 並帶 feature 前綴（`[osu! IRC]`、`[match]`、`[shell]`）。
- 測試不 `Thread.sleep`，用 `FakeIrcServer.awaitLine`／Awaitility；測試不連真的 Bancho、不寫 `logs/`。
- 每個 task 若改到 CLAUDE.md 描述的行為，同一個 commit 更新 CLAUDE.md。
- commit 訊息沿用 `feat(irc):`、`feat(match):`、`feat(shell):`、`build:` 前綴，結尾附 Co-Authored-By／Claude-Session 兩行。

## Review Focus

- 認證失敗後再次 `login`：舊的 connection thread 還在收尾時不可產生兩條連線 → Task 2 測試 `loginAgainAfterAuthFailureOpensExactlyOneNewConnection`。
- `drop` 之後晚到的同名回覆：shell 的 `make` callback 收到 `CancellationException` 不可印出錯誤堆疊或「開房失敗」→ Task 6 測試 `droppedRequestDoesNotPrintFailure`。
- 用 `msg BanchoBot` 手打 `!mp password secret`（繞過 `password` 指令）：樣本檔仍要遮蔽 → Task 8 測試 `masksPasswordSentThroughMsg`。
- log 資料夾不存在（第一次啟動）：樣本檔要自己建立目錄 → Task 8 測試 `createsMissingDirectory`。
- 輸入帳號時按 Ctrl+C／空白：取消且不送任何東西，之後仍可 `login` → Task 7 測試 `cancelledPromptDoesNotLogin`。

---

### Task 1: 送訊間隔預設 625ms

**Files:**
- Modify: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/OsuIrcSettings.java`（`Timing` 的預設值與 javadoc）
- Modify: `bancho-irc/src/test/java/cc/alexlin7/bancho/irc/OsuIrcSettingsTest.java:18`
- Modify: `CLAUDE.md`（`send-interval` 段落）

**Interfaces:** 無。

- [ ] **Step 1: 改測試**：`assertThat(settings.timing().sendInterval()).isEqualTo(Duration.ofMillis(625));`
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-irc test -Dtest=OsuIrcSettingsTest` → FAIL（expected 625 but was 500）
- [ ] **Step 3: 實作**：`sendInterval` 預設改 `Duration.ofMillis(625)`；javadoc 改為「一般帳號上限 10 則 / 5 秒，保留 20% 餘裕為 625ms；bot 帳號 300 則 / 60 秒，建議 240ms」。CLAUDE.md 的「`send-interval` 預設 500ms…可調到 200ms」改成 625ms／240ms 並註明保留 20% 餘裕。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-irc test`** → 全部 PASS（若有其他測試依賴 500ms 預設值一併修正）
- [ ] **Step 5: Commit** `feat(irc): 送訊間隔預設改為 625ms，保留 20% 餘裕`

---

### Task 2: `bancho-irc` 延後登入與連線狀態

**Files:**
- Create: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/IrcConnectionState.java`
- Create: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/IrcConnectionListener.java`
- Modify: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/OsuIrcSettings.java`
- Modify: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/OsuIrcClient.java`
- Test: `bancho-irc/src/test/java/cc/alexlin7/bancho/irc/OsuIrcSettingsTest.java`、`OsuIrcClientTest.java`

**Interfaces:**
- Produces:
  - `public enum IrcConnectionState { IDLE, CONNECTING, REGISTERED, AUTH_FAILED, CLOSED }`：`IDLE` 尚未提供帳密；`CONNECTING` 連線中或等待重連；`REGISTERED` 收到 001；`AUTH_FAILED` 464／Bad authentication 後停止重連；`CLOSED` 已 `close()`。
  - `@FunctionalInterface public interface IrcConnectionListener { void onStateChanged(IrcConnectionState state); }`：在 connection thread（`close()` 時為呼叫端 thread）上呼叫，例外只記 log。
  - `OsuIrcSettings`：`username` 與 `password` **同時為 null** 時合法（延後登入）；其他情況照舊檢查。新增 `public boolean hasCredentials()`。
  - `OsuIrcClient`：
    - `public IrcConnectionState state()`
    - `public void addConnectionListener(IrcConnectionListener listener)`
    - `public void login(String username, String password)`：帳密空白丟 `IllegalArgumentException`（`username 不得空白`／`password 不得空白`）；`state()` 不是 `IDLE` 或 `AUTH_FAILED` 時丟 `IllegalStateException("已登入或正在連線")`；否則記下帳密並開始連線（尚未 `start()` 過就等同 `start()`，認證失敗後則重開 connection thread）。
    - `start()`：沒有帳密時丟 `IllegalStateException("尚未提供帳密，請先呼叫 login")`。
    - nick 取自目前的帳密（`login` 給的優先於 settings）。

- [ ] **Step 1: 寫失敗的測試**

`OsuIrcSettingsTest`：
```java
@Test void allowsDeferredCredentials() {
    OsuIrcSettings settings = new OsuIrcSettings(null, 0, null, null, null);
    assertThat(settings.hasCredentials()).isFalse();
}
@Test void rejectsOnlyOneMissingCredential() {
    assertThatThrownBy(() -> new OsuIrcSettings(null, 0, "someone", null, null))
            .hasMessage("password 不得空白");
    assertThatThrownBy(() -> new OsuIrcSettings(null, 0, "", "", null))
            .hasMessage("username 不得空白");   // 空字串仍是錯誤，只有 null 代表延後
}
```

`OsuIrcClientTest`（以 `new OsuIrcSettings("127.0.0.1", server.port(), null, null, timing(...))` 建立 client）：
```java
@Test void startWithoutCredentialsFails()          // start() 丟 IllegalStateException，state() == IDLE，server 沒有連線
@Test void loginConnectsWithGivenCredentials()     // login("test user","secret") → awaitLine("PASS secret")、awaitLine("NICK test_user")；server.welcome() 後 state() == REGISTERED
@Test void notifiesStateChanges()                  // listener 依序收到 CONNECTING、REGISTERED；server 回 ":cho.ppy.sh 464 test_user :Bad authentication token." 後收到 AUTH_FAILED
@Test void loginRejectedWhileConnected()           // REGISTERED 後再 login 丟 IllegalStateException("已登入或正在連線")
@Test void loginAgainAfterAuthFailureOpensExactlyOneNewConnection()
        // 464 → AUTH_FAILED → login("test user","right") → awaitLine("PASS right")；await 直到 server.connectionCount() == 2，再等一個 SHORT 確認仍是 2
@Test void loginRejectsBlankCredentials()          // login("", "x") → IllegalArgumentException("username 不得空白")，state 仍是 IDLE
```

- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-irc test -Dtest=OsuIrcSettingsTest+OsuIrcClientTest` → 編譯失敗（找不到 `IrcConnectionState`、`login`）
- [ ] **Step 3: 實作**

`OsuIrcSettings` 的 compact constructor：`username == null && password == null` 時跳過檢查。帳密以 `AtomicReference`／volatile 欄位存在 client，`register()` 讀它；`login` 與 `start` 共用 `lifecycleLock`。認證失敗時 connection thread 結束前把狀態設為 `AUTH_FAILED`；`login` 重開時只啟動新的 connection thread（dispatcher、sender 照舊），並把 `reconnectDelay` 重設為最小值。狀態變更集中在一個 `setState(IrcConnectionState)` 方法，負責通知 listener 並 catch `RuntimeException`。`close()` 設為 `CLOSED`。

- [ ] **Step 4: 跑 `./mvnw -pl bancho-irc test`** → 全部 PASS（既有測試不需修改）
- [ ] **Step 5: Commit** `feat(irc): 加入延後登入 login 與連線狀態通知`

---

### Task 3: `bancho-irc` 原始進出站行觀察

**Files:**
- Create: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/IrcTrafficListener.java`
- Modify: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/OsuIrcClient.java`
- Test: `bancho-irc/src/test/java/cc/alexlin7/bancho/irc/OsuIrcClientTest.java`

**Interfaces:**
- Produces:
  ```java
  /** 原始 IRC 行的觀察者（例如記錄樣本）。例外只記 log，不影響連線。 */
  public interface IrcTrafficListener {
      /** connection thread 上，每收到一行（含 PING、數字回覆）在處理前呼叫；保留行尾空白。 */
      default void received(String line) {}
      /** sender thread 上，出站佇列的一行成功寫出後呼叫。登入的 PASS/NICK/USER、PONG、keepalive PING 不經佇列，不會通知。 */
      default void sent(String line) {}
  }
  ```
  `OsuIrcClient.addTrafficListener(IrcTrafficListener listener)`。

- [ ] **Step 1: 寫失敗的測試**
```java
@Test void reportsRawInboundLinesWithTrailingSpaces()   // server.send(":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Slot 1  Not Ready https://osu.ppy.sh/u/1 A   ") → received 收到完全相同的字串（含行尾空白）
@Test void reportsSentLinesButNeverRegistration()        // login 後 sendMessage("BanchoBot","!mp make r") → sent 收到 "PRIVMSG BanchoBot :!mp make r"；收集到的 sent 全部不以 PASS/NICK/USER 開頭
@Test void trafficListenerExceptionDoesNotBreakConnection() // listener 每次都丟 RuntimeException，訊息仍送達 IrcMessageListener，連線維持 REGISTERED
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-irc test -Dtest=OsuIrcClientTest` → 編譯失敗
- [ ] **Step 3: 實作**：listener 存在 `CopyOnWriteArrayList`；`readLoop` 在 `IrcMessage.parse` 前呼叫 `received`；`trySend` 寫出成功後呼叫 `sent`。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-irc test`** → PASS
- [ ] **Step 5: Commit** `feat(irc): 加入 IrcTrafficListener 觀察原始進出站行`

---

### Task 4: `bancho-irc-spring` 延後登入設定

**Files:**
- Modify: `bancho-irc-spring/src/main/java/cc/alexlin7/bancho/irc/spring/OsuIrcProperties.java`
- Modify: `bancho-irc-spring/src/main/java/cc/alexlin7/bancho/irc/spring/OsuIrcLifecycle.java`
- Test: `bancho-irc-spring/src/test/java/cc/alexlin7/bancho/irc/spring/OsuIrcAutoConfigurationTest.java`
- Modify: `CLAUDE.md`（`osu.irc.*` 段落）

**Interfaces:**
- Consumes: Task 2 的 `OsuIrcSettings.hasCredentials()`、`OsuIrcClient.state()`。
- Produces: `OsuIrcProperties` 新增 record 元件 `boolean deferredLogin`（`osu.irc.deferred-login`，預設 false，javadoc 會進 metadata）。為 true 且帳密**都**空白時，`toSettings()` 以 null 帳密建立延後登入的 settings；其他情況行為不變（缺帳密仍 fail fast）。`OsuIrcLifecycle.start()` 在 `!settings.hasCredentials()` 時不呼叫 `client.start()`，記一筆 INFO `[osu! IRC] 未設定帳密，等待 login`。為此 lifecycle 需要知道 client 有沒有帳密：在 `OsuIrcClient` 加 `public boolean hasCredentials()`（帳密來自 settings 或 login）。

- [ ] **Step 1: 寫失敗的測試**
```java
@Test void deferredLoginStartsWithoutCredentials()
    // withPropertyValues("osu.irc.username=", "osu.irc.password=", "osu.irc.deferred-login=true", "osu.irc.auto-start=true", host/port 指向 FakeIrcServer)
    // context 沒有失敗；client.state() == IDLE；server.awaitConnection(SHORT) 為 false
@Test void deferredLoginStillUsesCredentialsWhenGiven()
    // deferred-login=true 加上帳密 → lifecycle 照常連線（awaitLine("PASS secret")）
```
既有的 `failsFastWhenCredentialsAreMissing` 不改，證明預設行為不變。
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-irc-spring -am test -Dtest=OsuIrcAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL
- [ ] **Step 3: 實作**（含 `OsuIrcClient.hasCredentials()`；CLAUDE.md 說明 `deferred-login` 與「帳密空白時建立 client 就失敗」只在未開啟時成立）
- [ ] **Step 4: 跑 `./mvnw -pl bancho-irc-spring -am test`** → PASS（含 `without-validator-provider` execution）
- [ ] **Step 5: Commit** `feat(irc): 加入 osu.irc.deferred-login，未設帳密時等待 login`

---

### Task 5: `MatchService` 名額、結果未知與 drop

**Files:**
- Create: `bancho-match/src/main/java/cc/alexlin7/bancho/match/MatchCreation.java`
- Create: `bancho-match/src/main/java/cc/alexlin7/bancho/match/PendingMatch.java`
- Modify: `bancho-match/src/main/java/cc/alexlin7/bancho/match/MatchService.java`
- Test: `bancho-match/src/test/java/cc/alexlin7/bancho/match/MatchServiceTest.java`
- Modify: `CLAUDE.md`（`MatchService` 段落）

**Interfaces:**
- Produces:
  ```java
  /** room：收到同名回覆時完成（逾時後晚到也會完成），被 drop 時以 cancel 結束。
   *  unknown：逾時轉為結果未知時完成；在逾時前成功就永遠不完成。 */
  public record MatchCreation(String name, CompletableFuture<MatchRoom> room, CompletableFuture<Void> unknown) {}

  public record PendingMatch(String name, Status status) {
      public enum Status { CREATING, UNKNOWN }
  }
  ```
  `MatchService`：
  - `public static final int MAX_ROOMS = 4;`
  - `public MatchCreation createMatch(String name)`（取代原本回傳 `CompletableFuture<MatchRoom>` 的版本）：名額滿丟 `IllegalStateException("名額已滿（4/4）")`；同名 `CREATING` 丟 `IllegalStateException("已有同名的開房請求在等待中：" + name)`；同名 `UNKNOWN` 丟 `IllegalStateException("同名的開房請求結果未知：" + name + "；確認遊戲內沒有這間房後用 drop 釋放")`。都不送 `!mp make`。
  - `public List<PendingMatch> pendingMatches()`（依建立順序）
  - `public int usedSlots()` = 未關閉的房間數 + 待處理請求數
  - `public boolean drop(String name)`：只移除 `UNKNOWN` 的請求，並 `room().cancel(false)`；找不到或仍是 `CREATING` 回 false。房名先 `strip()`。
  - 收到 `MatchCreated` 時，若有同名請求（任何狀態）就移除並完成 `room`；名額檢查、新增、移除在同一個鎖內，請求轉成房間不重複計數。

- [ ] **Step 1: 改寫並新增測試**（既有測試改用 `.room()`；`createMatchTimesOut` 改寫為下方 `timeoutTurnsUnknownAndKeepsSlot`）
```java
@Test void timeoutTurnsUnknownAndKeepsSlot()
    // c = createMatch("r"); c.unknown() 在 WAIT 內完成；c.room() 未完成；pendingMatches() == [PendingMatch("r", UNKNOWN)]；usedSlots() == 1
@Test void unknownBlocksSameNameOnly()
    // r 結果未知後 createMatch("r") 丟 IllegalStateException（訊息含 "drop"）；createMatch("s") 成功；sender.calls 只有兩則 make
@Test void lateReplyCompletesUnknownRequest()
    // r 結果未知 → onMatchEvent(new MatchCreated(7, "r")) → c.room().join().matchId() == 7；pendingMatches() 為空；usedSlots() == 1
@Test void fifthRoomRejectedLocally()
    // attach(1)；createMatch a、b 成功回覆；createMatch c 留在 CREATING → usedSlots() == 4；createMatch("d") 丟 "名額已滿（4/4）"，不送 make
@Test void closedRoomReleasesSlot()
    // 4 個名額用滿後 onMatchEvent(new MatchClosed(1)) → usedSlots() == 3，可以再 createMatch
@Test void dropReleasesUnknownRequest()
    // r 結果未知 → drop(" r ") 為 true；c.room() isCancelled；usedSlots() == 0；可再 createMatch("r")
@Test void dropIgnoresCreatingRequest()
    // createMatch("r") 尚未逾時 → drop("r") 為 false
@Test void replyAfterDropRegistersRoomOnce()
    // r 結果未知 → drop → MatchCreated(9,"r") → openRooms() 只有 #mp_9，usedSlots() == 1
@Test void passivelyTrackedRoomCountsTowardSlots()
    // onMatchEvent(new PlayerJoined(...) on 未登記房號) → usedSlots() == 1
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-match test -Dtest=MatchServiceTest` → 編譯失敗
- [ ] **Step 3: 實作**：請求存在 `LinkedHashMap<String, Request>`（以 `synchronized` 保護，`Request` 內含 `PendingMatch.Status` 與 `MatchCreation`）；逾時任務把 `CREATING` 改為 `UNKNOWN` 並完成 `unknown`，不移除請求。`MatchReplayIntegrationTest`、`bancho-match-spring` 若用到 `createMatch` 一併調整。CLAUDE.md 的 `MatchService` 段落改寫：名額 4、結果未知、只擋同名、`drop`。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-match,bancho-match-spring -am test`** → PASS
- [ ] **Step 5: Commit** `feat(match): 建房名額與結果未知，結果未知只擋同名並可 drop`

---

### Task 6: shell 的 `make`、`rooms`、`drop`

**Files:**
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/MatchCommands.java`
- Test: `bancho-shell/src/test/java/cc/alexlin7/bancho/shell/MatchCommandsTest.java`、`ShellCommandParsingTest.java`
- Modify: `CLAUDE.md`（shell 指令清單）

**Interfaces:**
- Consumes: Task 5 的 `MatchCreation`、`PendingMatch`、`MatchService.drop/pendingMatches/usedSlots/MAX_ROOMS`。
- Produces: `drop <房名…>` 指令（group `房間`，`InputGuard.check(context, InputGuard.ANY)`），`lastMake()` 改回傳 `MatchCreation` 的 `room()` future（測試用）。

使用者訊息（逐字）：
- 結果未知：`開房結果未知：<房名>；若遊戲內沒有這間房，用 drop <房名> 釋放名額`
- 晚到成功：`房間已建立（晚到的回覆）：#mp_<id> <房名>`，其餘處理（切換房間、套用範本）和準時成功相同
- 準時成功：維持 `房間已建立：#mp_<id> <房名>`
- 被 drop：不印任何東西
- drop 成功：`已釋放：<房名>`；失敗：`找不到結果未知的開房請求：<房名>`
- `rooms`：房間列表後接 `建房中：<房名>`／`結果未知：<房名>` 各一行，最後一行 `名額 <used>/4`；全空時 `目前沒有開著的房間（名額 0/4）`

- [ ] **Step 1: 寫失敗的測試**（`MatchService` 用 200ms 逾時）
```java
@Test void makeTimeoutPrintsUnknownHint()          // printed 含 "開房結果未知：r；若遊戲內沒有這間房，用 drop r 釋放名額"
@Test void lateReplyAfterUnknownStillUsesRoom()    // 逾時後 MatchCreated(5,"r") → printed 含 "房間已建立（晚到的回覆）：#mp_5 r"；session.currentRoom() == 5
@Test void droppedRequestDoesNotPrintFailure()     // 逾時 → drop r → printed 只有結果未知那一行，沒有 "開房失敗"
@Test void dropReleasesSlot()                      // commands.drop(List.of("r"), ctx) == "已釋放：r"；再次 drop == "找不到結果未知的開房請求：r"
@Test void makeRejectedWhenSlotsFull()             // 4 個房間後 make 回 "錯誤：名額已滿（4/4）"（沿用 RoomResolver.guarded 的錯誤格式）
@Test void roomsListsPendingAndSlots()             // 1 間房 + 1 筆結果未知 → 輸出含 "結果未知：r" 與最後一行 "名額 2/4"
```
`ShellCommandParsingTest` 加一個 `drop 第一場 練習` 經 parser 後釋放對應請求的案例。
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-shell -am test -Dtest=MatchCommandsTest+ShellCommandParsingTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL
- [ ] **Step 3: 實作**：`whenComplete` 中 `CancellationException` 直接略過；「晚到」以 `unknown().isDone()` 判斷。
- [ ] **Step 4: 跑同一指令** → PASS
- [ ] **Step 5: Commit** `feat(shell): make 顯示結果未知、rooms 顯示名額、新增 drop`

---

### Task 7: shell 的 `login` 與未登入擋下送訊指令

**Files:**
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/LoginCommands.java`
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/LoginGuard.java`
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/ConnectionStatusPrinter.java`
- Modify: `MatchCommands.java`、`ChatCommands.java`（送訊指令開頭呼叫 `LoginGuard`）
- Modify: `bancho-shell/src/main/resources/application.properties`
- Test: `bancho-shell/src/test/java/cc/alexlin7/bancho/shell/LoginCommandsTest.java`、`MatchCommandsTest.java`、`ChatCommandsTest.java`
- Modify: `CLAUDE.md`（憑證段落、shell 段落）

**Interfaces:**
- Consumes: Task 2 的 `OsuIrcClient.login/state/addConnectionListener`、Task 4 的 `osu.irc.deferred-login`。
- Produces:
  - `@Component class LoginGuard { LoginGuard(OsuIrcClient client); String check(); }`：`state()` 為 `IDLE` 或 `AUTH_FAILED` 時回 `錯誤：請先 login`，否則 null。為了測試，`MatchCommands`、`ChatCommands` 的建構子多一個 `LoginGuard` 參數；測試用 `LoginGuard` 的 package-private 建構子 `LoginGuard(Supplier<IrcConnectionState> state)`。
  - 要擋的指令：`make`、`attach`、`invite`、`map`、`mods`、`set`、`start`、`timer`、`abort`、`close`、`settings`、`password`、`addref`、`mp`、`say`、`msg`。不擋：`use`、`rooms`、`drop`、`login`、`help`、`quit`。檢查放在 `InputGuard` 之後、任何提示輸入之前。
  - `login` 指令（group `帳號`，不收參數）：
    - 已是 `CONNECTING`／`REGISTERED`：回 `錯誤：已登入，要換帳號請重開程式`
    - `TextPrompt` 提示 `osu! 帳號：`；`SecretPrompt` 提示 `IRC 密碼（不是帳號密碼，到 https://osu.ppy.sh/home/account/edit#legacy-api 取得）：`
    - 任一取消：回 `已取消登入`
    - 否則 `client.login(...)` 並回 `登入中：<帳號>`
  - `ConnectionStatusPrinter`（建構時 `client.addConnectionListener`）：`REGISTERED` 印 `已登入 Bancho`；`AUTH_FAILED` 印 `登入失敗：帳號或 IRC 密碼錯誤，請重新 login`。並在 `ApplicationStartedEvent`（早於 shell runner）時若 `!client.hasCredentials()` 印 `尚未登入，請先執行 login`。
  - `application.properties`：`osu.irc.deferred-login=true`；`osu.irc.password=${OSU_IRC_PASSWORD:}`（原本沒有預設值，未設環境變數時無法解析）。

- [ ] **Step 1: 寫失敗的測試**
```java
// LoginCommandsTest（用 FakeIrcServer 與真的 OsuIrcClient(null 帳密)，prompt 以 lambda 回傳預設值）
@Test void loginPromptsAndConnects()          // 回 "登入中：test user"；server.awaitLine("PASS secret")
@Test void cancelledPromptDoesNotLogin()      // 帳號提示回 Optional.empty() → "已取消登入"；client.state() == IDLE；之後再 login 可成功
@Test void secondLoginRejected()              // 已 CONNECTING 時回 "錯誤：已登入，要換帳號請重開程式"
@Test void authFailurePrintsHintAndAllowsRetry() // server 回 464 → printed 含 "登入失敗：帳號或 IRC 密碼錯誤，請重新 login"；再 login 回 "登入中：…"
// MatchCommandsTest / ChatCommandsTest
@Test void makeRejectedBeforeLogin()          // LoginGuard 回 IDLE → "錯誤：請先 login"，sender.calls 為空
@Test void msgRejectedBeforeLoginWithoutPrompting() // 沒有任何 prompt 被呼叫
@Test void roomsWorksBeforeLogin()
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-shell -am test -Dtest=LoginCommandsTest+MatchCommandsTest+ChatCommandsTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: 實作**；既有測試建構 `MatchCommands`／`ChatCommands` 處傳入回 `REGISTERED` 的 `LoginGuard`。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-shell -am test`** → 全部 PASS（`ShellIntegrationTest` 的 test properties 有帳密且 auto-start=false，狀態為 IDLE：若整合測試經 parser 呼叫送訊指令，改在測試設定中提供回 `REGISTERED` 的 `LoginGuard` bean，或對 client 呼叫 login 指向 FakeIrcServer，擇一並在測試註解說明）
- [ ] **Step 5: 手動驗證**：不設環境變數執行 `./mvnw -pl bancho-shell -am compile spring-boot:run`，看到 `尚未登入，請先執行 login`；`make x` 回 `錯誤：請先 login`；`login` 以錯誤密碼得到登入失敗訊息。（正確密碼的登入由使用者自行驗證）
- [ ] **Step 6: Commit** `feat(shell): 加入 login 指令，未登入時擋下送訊指令`

---

### Task 8: 樣本檔

**Files:**
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/SampleFilter.java`
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/SampleRecorder.java`
- Modify: `bancho-shell/src/main/resources/application.properties`、`bancho-shell/src/test/resources/application.properties`
- Test: `bancho-shell/src/test/java/cc/alexlin7/bancho/shell/SampleFilterTest.java`、`SampleRecorderTest.java`
- Modify: `CLAUDE.md`（log 段落、「慣例」的樣本段落）

**Interfaces:**
- Consumes: Task 3 的 `IrcTrafficListener`、`OsuIrcClient.addTrafficListener`。
- Produces:
  - `final class SampleFilter { static boolean keepReceived(String line); static Optional<String> outbound(String line); }`
    - `keepReceived`：`IrcMessage.parse` 後，PING／PONG 不收；nick 為 `BanchoBot` 的 PRIVMSG／NOTICE 收；沒有 prefix 或 prefix 不含 `!` 的 NOTICE（伺服器）收；三位數字指令收；其他不收。
    - `outbound`：只處理 `PRIVMSG <target> :<text>` 且 target 為 `BanchoBot`（不分大小寫）或以 `#mp_` 開頭；`text` 符合 `(?i)^!mp\s+password\s+\S.*` 時換成 `!mp password ***`；其他行回 empty。
  - `class SampleRecorder implements IrcTrafficListener`，建構子 `SampleRecorder(Path directory, Clock clock)`；Spring 由一個 `@Bean`（`bancho.samples.enabled`，預設 true）建立並 `client.addTrafficListener(...)`，`directory` 取 `logging.file.name` 的父目錄（沒有父目錄時用工作目錄）。
  - 檔名 `samples-<yyyy-MM-dd>.irc`（依 `clock` 的本地日期，跨日自動換檔）。新檔第一行 `# bancho-shell 樣本檔；匯入測試資源前把自己的 nick 改成 test_user`。
  - 收進的行原樣寫入，前面在 `HH:mm:ss` 和上一次寫入的時間不同時先寫一行 `# HH:mm:ss`；送出的行寫成 `# HH:mm:ss -> <遮蔽後的行>`（註解行，回放測試會略過）。
  - 寫入失敗只 `log.warn` 一次（`[shell] 無法寫入樣本檔`），之後靜默略過，不影響連線。
  - test `application.properties` 設 `bancho.samples.enabled=false`。

- [ ] **Step 1: 寫失敗的測試**
```java
// SampleFilterTest
@Test void keepsBanchoBotMessages()     // ":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Closed the match" → true
@Test void keepsServerNoticeAndNumerics() // ":cho.ppy.sh NOTICE * :x"、":cho.ppy.sh 464 u :Bad authentication token." → true
@Test void dropsPlayerChatAndPing()     // ":Alexlin86!cho@ppy.sh PRIVMSG #mp_1 :gl"、"PING :cho.ppy.sh"、":A!cho@ppy.sh PRIVMSG me :hi" → false
@Test void keepsOutboundToBanchoBotAndMpOnly() // "PRIVMSG #mp_1 :!mp start 10" → 原樣；"PRIVMSG Alexlin86 :hi"、"JOIN #mp_1" → empty
@Test void masksPassword()              // "PRIVMSG #mp_1 :!mp password hunter2" → "PRIVMSG #mp_1 :!mp password ***"；"PRIVMSG #mp_1 :!mp password" → 原樣
@Test void masksPasswordSentThroughMsg() // "PRIVMSG BanchoBot :!MP PASSWORD hunter2" → 不含 hunter2
// SampleRecorderTest（@TempDir，固定 Clock）
@Test void writesReceivedLinesVerbatimWithTimestampComments() // 檔案內容 == 標頭、"# 12:00:00"、原始行（含行尾空白）
@Test void writesSentLinesAsComments()  // "# 12:00:00 -> PRIVMSG #mp_1 :!mp password ***"，檔案內沒有 hunter2
@Test void createsMissingDirectory()    // directory = tempDir.resolve("logs/nested")
@Test void rollsOverByDate()            // 跨日後寫到 samples-<新日期>.irc
@Test void replayTestCanReadTheFile()   // 以 MatchReplayIntegrationTest 相同的過濾規則（略過空行與 # 開頭）讀回，只剩收進的原始行
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-shell -am test -Dtest=SampleFilterTest+SampleRecorderTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: 實作**：寫入用 `synchronized` 方法，每筆以 `Files.writeString(..., CREATE, APPEND)`（量很小，不必保持檔案開啟）；`received` 與 `sent` 分別在 connection 與 sender thread 上呼叫。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-shell -am test`** → PASS
- [ ] **Step 5: Commit** `feat(shell): 把 BanchoBot 與 Bancho 系統訊息寫進樣本檔`

---

### Task 9: jpackage 打包腳本與使用說明

**Files:**
- Create: `bancho-shell/src/dist/package-windows.ps1`
- Create: `bancho-shell/src/dist/使用說明.txt`
- Modify: `CLAUDE.md`（指令段落）

**Interfaces:**
- Produces: `pwsh bancho-shell/src/dist/package-windows.ps1 -Version <X.Y.Z> -Jar <可執行 jar 路徑> -OutDir <輸出目錄>`，產生 `<OutDir>/bancho-shell-<X.Y.Z>-windows.zip`，解壓縮後最上層是 `bancho-shell/`（內含 `bancho-shell.exe`、`app/`、`runtime/`、`使用說明.txt`）。`-Version` 不符合 `^\d+\.\d+\.\d+$` 時直接失敗。

jpackage 參數（腳本中逐一傳入）：`--type app-image --name bancho-shell --input <只含該 jar 的暫存目錄> --main-jar <jar 檔名> --app-version <Version> --win-console`，以及 `--java-options` 各一次：`--enable-native-access=ALL-UNNAMED`、`-Dlogging.file.name=$APPDIR/../logs/bancho.log`、`-Dspring.shell.history.name=$APPDIR/../bancho-history`（`$APPDIR` 要以單引號傳入，由 launcher 展開）。完成後把 `使用說明.txt` 複製到 app-image 根目錄，再 `Compress-Archive`。

`使用說明.txt` 內容依 spec 4.3 的五點，語氣給朋友看，並列出：取得 IRC 密碼的網址、`login` → `make <房名>` → `invite <名稱>` → `map <id>` → `start 10` → `close` 的基本流程、`rooms`／`drop` 的用途、斷線期間被關掉的房間會一直佔名額（重開程式即可）、回傳整個 `logs/` 資料夾，以及樣本檔「會記／不會記」的清單。

- [ ] **Step 1: 寫腳本與說明**
- [ ] **Step 2: 本機打包**：`./mvnw -pl bancho-shell -am package -DskipTests`，再 `pwsh bancho-shell/src/dist/package-windows.ps1 -Version 0.1.2 -Jar (bancho-shell/target 下的可執行 jar) -OutDir $CLAUDE_JOB_DIR/tmp/dist` → 產生 zip
- [ ] **Step 3: 驗證**：解壓縮到暫存目錄，在一個 `PATH` 與 `JAVA_HOME` 都不含 JDK 的 PowerShell 中執行 `bancho-shell\bancho-shell.exe`：看到 `尚未登入，請先執行 login`，`quit` 後解壓縮目錄的 `logs/bancho.log` 存在，`bancho-history` 也在該目錄。`-Version 0.1` 時腳本失敗。
- [ ] **Step 4: Commit** `build: 加入 Windows jpackage 打包腳本與測試版使用說明`

---

### Task 10: GitHub Actions 發版 workflow

**Files:**
- Create: `.github/workflows/shell-release.yml`
- Modify: `CLAUDE.md`（新增「發布 shell 測試版」小節）

**Interfaces:**
- Consumes: Task 9 的 `package-windows.ps1`。

workflow 規格：
- `on: push: tags: ['shell-v*']`；`permissions: contents: write`；`runs-on: windows-latest`；`defaults.run.shell: pwsh`。
- 步驟：`actions/checkout@v4` → `actions/setup-java@v4`（`distribution: temurin`、`java-version: '25'`、`cache: maven`）→ 由 `$env:GITHUB_REF_NAME` 去掉 `shell-v` 得到版本，不符合 `^\d+\.\d+\.\d+$` 就失敗 → `./mvnw.cmd -B package`（含測試）→ 找出 `bancho-shell/target/bancho-shell-*.jar`（排除 `*.original`、`-sources`、`-javadoc`），複製為 `bancho-shell-<版本>.jar` → 執行 `package-windows.ps1` → `gh release create $env:GITHUB_REF_NAME --prerelease --title "bancho-shell <版本>" --notes "測試版，使用方式見 zip 內的使用說明.txt" <jar> <zip>`（`GH_TOKEN: ${{ github.token }}`）。
- 不讀任何 secret 以外的憑證；不改 pom、不 commit。

CLAUDE.md 小節內容：shell 測試版以 `shell-vX.Y.Z` tag 發布（`git tag -a shell-vX.Y.Z -m "bancho-shell X.Y.Z" && git push origin shell-vX.Y.Z`），版本取自 tag、與 `<revision>` 及 Central 無關；`v*` tag 仍專用於 Central。

- [ ] **Step 1: 寫 workflow 與 CLAUDE.md**
- [ ] **Step 2: 靜態檢查**：若本機有 `actionlint` 就執行；否則以 `python -I -c "import yaml,sys; yaml.safe_load(open(sys.argv[1], encoding='utf-8'))" .github/workflows/shell-release.yml` 確認 YAML 合法
- [ ] **Step 3: Commit** `build: 加入 shell-v tag 觸發的 GitHub Actions 發版`
- [ ] **Step 4: 實際發版驗證（需使用者同意後才推送）**：分支合回 main 並推送後，由使用者決定推送一個 `shell-v0.1.2` tag，確認 Release 為 pre-release 且附兩個檔案。推送 tag 屬於對外動作，執行者不得自行推送。
