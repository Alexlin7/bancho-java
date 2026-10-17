# 建房名額、結果未知與 shell 測試版發布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 補上建房名額與「結果未知」，並把 Spring Shell 包成朋友解壓縮就能用、會留下 BanchoBot 樣本檔的測試版，由 `shell-v*` tag 觸發 GitHub Actions 發版。

**Architecture:** `bancho-irc` 新增延後登入（`login`）、連線狀態通知、送出後通知、出站行遮蔽與原始進出站行觀察，都是新增 API、不破壞既有用法；`bancho-match` 的 `MatchService` 管名額與建房請求狀態，並提供 `!mp password` 的遮蔽規則；`bancho-shell` 加 `login`／`drop` 指令、只在已登入時放行送訊指令、把篩選過的原始行寫進樣本檔；打包用 jpackage 腳本，workflow 在 Windows runner 上執行。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring Shell、JUnit 5／AssertJ／Awaitility、jpackage、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-10-17-shell-tester-release-design.md`

## Global Constraints

- `bancho-irc` 不得依賴 Spring；變更只新增 API，「建構時給齊帳密、`start()` 連線」的既有用法與錯誤訊息（`username 不得空白`）維持不變；已發布型別（`OsuIrcSettings`、`OsuIrcProperties`、`IrcSender`）的既有建構子與方法都要保留。
- 延後登入必須以 `osu.irc.deferred-login=true` 明確開啟；未開啟時帳密空白或完全沒設定都啟動失敗。
- 名額上限 4；已開的房間（含 `attach`、被動登記）、建房中、結果未知的請求共用。
- 建房逾時從 `!mp make` 實際寫出後開始計算；drop 過的房名在本次執行中不能再用。
- 只有連線狀態為 `REGISTERED` 時放行會送 IRC 的 shell 指令。
- 密碼不得出現在任何 log（含 DEBUG、截斷 WARN）、樣本檔、指令歷史；`PASS` 行不得交給任何觀察者。
- 樣本檔只收：BanchoBot 的 PRIVMSG／NOTICE、伺服器 NOTICE、數字回覆、我們送給 BanchoBot 或 `#mp_` 的 PRIVMSG（已遮蔽）。行尾空白保留。
- 程式註解、log、使用者訊息用繁體中文；log 用 `@Slf4j` 並帶 feature 前綴（`[osu! IRC]`、`[match]`、`[shell]`）。
- 測試不 `Thread.sleep`，用 `FakeIrcServer.awaitLine`／Awaitility；測試不連真的 Bancho、不寫 `logs/`。
- 每個 task 若改到 CLAUDE.md 描述的行為，同一個 commit 更新 CLAUDE.md。
- commit 訊息沿用 `feat(irc):`、`feat(match):`、`feat(shell):`、`build:` 前綴，結尾附 Co-Authored-By／Claude-Session 兩行。

## Review Focus

- 認證失敗後再次 `login`：舊的 connection thread 還在收尾時不可產生兩條連線 → Task 2 測試 `loginAgainAfterAuthFailureOpensExactlyOneNewConnection`。
- 斷線時下 `make`（指令還在佇列）：不可逾時、不可被 drop，重連送出後才開始計時 → Task 5 測試 `unsentRequestNeverTimesOut`。
- `drop` 之後晚到的同名回覆：shell 的 `make` callback 收到 `CancellationException` 不可印出「開房失敗」→ Task 7 測試 `droppedRequestDoesNotPrintFailure`。
- 用 `msg BanchoBot` 手打 `!MP PASSWORD secret`（繞過 `password` 指令）：DEBUG log 與觀察者都要遮蔽 → Task 6 測試 `masksPasswordTypedThroughMsg`。
- log 資料夾不存在（第一次啟動）：樣本檔要自己建立目錄 → Task 9 測試 `createsMissingDirectory`。

---

### Task 1: 送訊間隔預設 625ms

**Files:**
- Modify: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/OsuIrcSettings.java`（`Timing` 的預設值與 javadoc）
- Modify: `bancho-irc/src/test/java/cc/alexlin7/bancho/irc/OsuIrcSettingsTest.java:18`
- Modify: `CLAUDE.md`（`send-interval` 段落）

**Interfaces:** 無。

- [ ] **Step 1: 改測試**：`assertThat(settings.timing().sendInterval()).isEqualTo(Duration.ofMillis(625));`
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-irc test -Dtest=OsuIrcSettingsTest` → FAIL（expected 625 but was 500）
- [ ] **Step 3: 實作**：`sendInterval` 預設改 `Duration.ofMillis(625)`；javadoc 改為「一般帳號上限 10 則 / 5 秒，保留 20% 餘裕為 625ms；bot 帳號 300 則 / 60 秒，建議 240ms」。CLAUDE.md 對應段落改成 625ms／240ms 並註明保留 20% 餘裕。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-irc test`** → 全部 PASS
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
    - `public boolean hasCredentials()`（帳密來自 settings 或 `login`）
    - `public void addConnectionListener(IrcConnectionListener listener)`
    - `public void login(String username, String password)`：帳密空白丟 `IllegalArgumentException`（`username 不得空白`／`password 不得空白`）；`state()` 不是 `IDLE` 或 `AUTH_FAILED` 時丟 `IllegalStateException("已登入或正在連線")`；否則記下帳密並開始連線（尚未 `start()` 過就等同 `start()`，認證失敗後則重開 connection thread）。
    - `start()`：沒有帳密時丟 `IllegalStateException("尚未提供帳密，請先呼叫 login")`。
    - nick 取自目前的帳密（`login` 給的優先於 settings）。

- [ ] **Step 1: 寫失敗的測試**

`OsuIrcSettingsTest`：
```java
@Test void allowsDeferredCredentials() {
    assertThat(new OsuIrcSettings(null, 0, null, null, null).hasCredentials()).isFalse();
}
@Test void rejectsOnlyOneMissingCredential() {
    assertThatThrownBy(() -> new OsuIrcSettings(null, 0, "someone", null, null)).hasMessage("password 不得空白");
    assertThatThrownBy(() -> new OsuIrcSettings(null, 0, "", "", null)).hasMessage("username 不得空白"); // 空字串仍是錯誤，只有 null 代表延後
}
```

`OsuIrcClientTest`（以 `new OsuIrcSettings("127.0.0.1", server.port(), null, null, timing(...))` 建立 client）：
```java
@Test void startWithoutCredentialsFails()          // start() 丟 IllegalStateException；state() == IDLE；server.awaitConnection(SHORT) 為 false
@Test void loginConnectsWithGivenCredentials()     // login("test user","secret") → awaitLine("PASS secret")、awaitLine("NICK test_user")；server.welcome() 後 state() == REGISTERED
@Test void notifiesStateChanges()                  // listener 依序收到 CONNECTING、REGISTERED；另一個 client 收到 ":cho.ppy.sh 464 test_user :Bad authentication token." 後收到 AUTH_FAILED
@Test void loginRejectedWhileConnected()           // REGISTERED 後再 login 丟 IllegalStateException("已登入或正在連線")
@Test void loginAgainAfterAuthFailureOpensExactlyOneNewConnection()
        // 464 → AUTH_FAILED → login("test user","right") → awaitLine("PASS right")；await 直到 connectionCount() == 2，再等 SHORT 確認仍是 2
@Test void loginRejectsBlankCredentials()          // login("", "x") → IllegalArgumentException("username 不得空白")，state 仍是 IDLE
```

- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-irc test -Dtest=OsuIrcSettingsTest+OsuIrcClientTest` → 編譯失敗
- [ ] **Step 3: 實作**：帳密存在 client 的 volatile 欄位，`register()` 讀它；`login` 與 `start` 共用 `lifecycleLock`。認證失敗時 connection thread 結束前設為 `AUTH_FAILED`；`login` 重開時只啟動新的 connection thread（dispatcher、sender 照舊）並重設 `reconnectDelay`。狀態變更集中在 `setState(IrcConnectionState)`，負責通知 listener 並 catch `RuntimeException`。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-irc test`** → 全部 PASS（既有測試不需修改）
- [ ] **Step 5: Commit** `feat(irc): 加入延後登入 login 與連線狀態通知`

---

### Task 3: `bancho-irc` 送出後通知、出站遮蔽與原始行觀察

**Files:**
- Create: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/IrcTrafficListener.java`
- Modify: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/IrcSender.java`
- Modify: `bancho-irc/src/main/java/cc/alexlin7/bancho/irc/OsuIrcClient.java`
- Test: `bancho-irc/src/test/java/cc/alexlin7/bancho/irc/OsuIrcClientTest.java`

**Interfaces:**
- Produces:
  - `IrcSender` 新增 `default void sendMessage(String target, String message, Runnable onSent)`：預設實作呼叫兩參數版本後立即 `onSent.run()`（`RecordingIrcSender` 因此不必改）。javadoc 寫明：預設實作只對「兩參數方法同步送出」的實作才符合「實際送出後通知」；以佇列或其他方式非同步送出的實作**必須覆寫**這個方法。CLAUDE.md 的 `IrcSender` 段落同步註明。`OsuIrcClient` 覆寫：`onSent` 在 sender thread 上、該行**實際寫出成功後**呼叫；斷線重送時只在最後成功那次呼叫；`close()` 後未送出的不呼叫。`onSent` 拋出例外時只記 log：該行視為已送出、不重送，sender thread 繼續處理下一行。出站佇列改存 `(line, onSent)`。
  - `OsuIrcClient.setOutboundRedactor(UnaryOperator<String> redactor)`：預設 identity。套用在 (1) `-> line` 的 DEBUG log、(2) 超過 512 bytes 截斷時的 WARN、(3) `IrcTrafficListener.sent` 收到的行。實際送出的內容不變。截斷 WARN 改為記錄遮蔽後的整行。
  - ```java
    /** 原始 IRC 行的觀察者（例如記錄樣本）。例外只記 log，不影響連線。 */
    public interface IrcTrafficListener {
        /** connection thread 上，每收到一行（含 PING、數字回覆）在處理前呼叫；保留行尾空白。 */
        default void received(String line) {}
        /** sender thread 上，出站佇列的一行成功寫出後呼叫，內容已套用 outbound redactor。
         *  登入的 PASS/NICK/USER、PONG、keepalive PING 不經佇列，不會通知。 */
        default void sent(String line) {}
    }
    ```
    `OsuIrcClient.addTrafficListener(IrcTrafficListener listener)`。

- [ ] **Step 1: 寫失敗的測試**
```java
@Test void onSentRunsOnlyAfterLineIsWritten()          // 尚未 welcome 時 sendMessage("BanchoBot","!mp make r", latch::countDown) → latch 在 SHORT 內未歸零；welcome 後 awaitLine("PRIVMSG BanchoBot :!mp make r") 且 latch 歸零
@Test void onSentExceptionDoesNotResendOrStopSender()  // 第一則的 onSent 丟 RuntimeException；server 只收到一次該行（再 send 第二則並 awaitLine 後，確認第一則出現次數為 1）；第二則照常送出且其 onSent 有執行
@Test void reportsRawInboundLinesWithTrailingSpaces()  // server.send(":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Slot 1  Not Ready https://osu.ppy.sh/u/1 A   ") → received 收到完全相同的字串
@Test void reportsSentLinesButNeverRegistration()      // sent 收到 "PRIVMSG BanchoBot :!mp make r"；所有 sent 都不以 PASS/NICK/USER 開頭
@Test void redactorAppliesToLogsAndListenersNotWire(CapturedOutput output)
        // 設定 debug level（logback 以 LoggerContext 調整 cc.alexlin7.bancho.irc 為 DEBUG，測試結束還原）；
        // setOutboundRedactor(l -> l.replace("hunter2", "***"))；sendMessage("#mp_1","!mp password hunter2")
        // server.awaitLine("PRIVMSG #mp_1 :!mp password hunter2") 不為 null；sent 收到含 *** 的行；output 不含 hunter2
@Test void truncationWarningIsRedacted(CapturedOutput output) // 600 bytes 含 hunter2 的訊息 → WARN 有出現，output 不含 hunter2
@Test void trafficListenerExceptionDoesNotBreakConnection()  // listener 每次都丟例外，訊息仍送達 IrcMessageListener，state 維持 REGISTERED
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-irc test -Dtest=OsuIrcClientTest` → 編譯失敗
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑 `./mvnw -pl bancho-irc test`** → PASS
- [ ] **Step 5: Commit** `feat(irc): 加入送出後通知、出站遮蔽與 IrcTrafficListener`

---

### Task 4: `bancho-irc-spring` 延後登入設定

**Files:**
- Modify: `bancho-irc-spring/src/main/java/cc/alexlin7/bancho/irc/spring/OsuIrcProperties.java`
- Modify: `bancho-irc-spring/src/main/java/cc/alexlin7/bancho/irc/spring/OsuIrcLifecycle.java`
- Test: `bancho-irc-spring/src/test/java/cc/alexlin7/bancho/irc/spring/OsuIrcAutoConfigurationTest.java`
- Modify: `CLAUDE.md`（`osu.irc.*` 段落）

**Interfaces:**
- Consumes: Task 2 的 `OsuIrcSettings.hasCredentials()`、`OsuIrcClient.hasCredentials()`、`state()`。
- Produces:
  - `OsuIrcProperties` 新增 record 元件 `boolean deferredLogin`（`osu.irc.deferred-login`，預設 false，javadoc 進 metadata）。**保留原本的五參數建構子** `OsuIrcProperties(String host, int port, String username, String password, Timing timing)`，委派到新建構子並傳 `false`；Boot 綁定用的建構子以 `@ConstructorBinding` 標在新的 canonical 建構子上。
  - `toSettings()`：`deferredLogin` 為 true 且帳密**都**空白（null 或空字串）時，以 null 帳密建立延後登入的 settings；其他情況先把 null 轉成空字串再交給 `OsuIrcSettings`，確保未開啟時 null/null 也會得到 `osu.irc.username 不得空白`。
  - `OsuIrcLifecycle.start()`：`!client.hasCredentials()` 時不呼叫 `client.start()`，記 INFO `[osu! IRC] 未設定帳密，等待 login`。

- [ ] **Step 1: 寫失敗的測試**
```java
@Test void deferredLoginStartsWithoutCredentials()
    // username=、password=、deferred-login=true、auto-start=true、host/port 指向 FakeIrcServer
    // context 沒有失敗；client.state() == IDLE；server.awaitConnection(SHORT) 為 false
@Test void deferredLoginStillUsesCredentialsWhenGiven()   // deferred-login=true 加帳密 → awaitLine("PASS secret")
@Test void failsWhenCredentialsAreOmittedEntirely()
    // 不設 username/password 屬性（並以 withSystemProperties／空的 environment 確保不讀到開發者的 OSU_IRC_* 環境變數），
    // 不開 deferred-login → context hasFailed，root cause 訊息為 "osu.irc.username 不得空白"
@Test void fiveArgumentConstructorStillWorks()            // new OsuIrcProperties(null, 0, "u", "p", null).deferredLogin() 為 false，toSettings() 成功
```
既有的 `failsFastWhenCredentialsAreMissing` 不改。
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-irc-spring -am test -Dtest=OsuIrcAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL
- [ ] **Step 3: 實作**；CLAUDE.md 說明 `deferred-login`，並註明「帳密空白時建立 client 就失敗」只在未開啟時成立。
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
- Consumes: Task 3 的 `IrcSender.sendMessage(target, message, onSent)`。
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
  - `public MatchCreation createMatch(String name)`（取代原本回傳 `CompletableFuture<MatchRoom>` 的版本）：以三參數 `sendMessage` 送 `!mp make`，**在 `onSent` 時才排定逾時**。拒絕時丟 `IllegalStateException`，都不送 `!mp make`，依序檢查：
    - drop 過的房名：`此房名已 drop，本次執行不能再用，請換一個房名：<房名>`
    - 同名 `CREATING`：`已有同名的開房請求在等待中：<房名>`
    - 同名 `UNKNOWN`：`同名的開房請求結果未知：<房名>；確認遊戲內沒有這間房後用 drop 釋放`
    - 名額滿：`名額已滿（4/4）`
  - `public List<PendingMatch> pendingMatches()`（依建立順序）
  - `public int usedSlots()` = 未關閉的房間數 + 待處理請求數
  - `public boolean drop(String name)`：只移除 `UNKNOWN` 的請求，`room().cancel(false)`，並把房名加入本次執行的 drop 名單；找不到或仍是 `CREATING` 回 false。房名先 `strip()`。
  - 收到 `MatchCreated` 時，若有同名請求就移除並完成 `room`；沒有（含已 drop）就照現有行為登記。名額檢查、新增、移除在同一個鎖內，請求轉成房間不重複計數。

- [ ] **Step 1: 改寫並新增測試**（既有測試改用 `.room()`；`createMatchTimesOut` 改寫為 `timeoutTurnsUnknownAndKeepsSlot`；需要控制送出時機的測試用 `HeldSender extends RecordingIrcSender`，覆寫三參數 `sendMessage` 只記錄 `onSent` 不執行）
```java
@Test void timeoutTurnsUnknownAndKeepsSlot()      // c.unknown() 在 WAIT 內完成；c.room() 未完成；pendingMatches() == [PendingMatch("r", UNKNOWN)]；usedSlots() == 1
@Test void unsentRequestNeverTimesOut()           // HeldSender：等 3 倍逾時後 c.unknown() 仍未完成、狀態 CREATING、drop("r") 為 false；執行 onSent 後 unknown 在 WAIT 內完成
@Test void replyBeforeOnSentStaysSuccessful()     // HeldSender：MatchCreated(3,"r") 先到 → 再執行 onSent → 等超過逾時：c.room() 已完成、c.unknown() 未完成、pendingMatches() 為空、usedSlots() == 1
@Test void staleTimerDoesNotAffectNewSameNameRequest() // HeldSender：r 送出（執行 onSent）→ MatchCreated(3,"r") 成功 → MatchClosed(3) → 再 createMatch("r")（不執行 onSent）→ 等超過第一個計時器的時間：新請求仍是 CREATING、unknown 未完成
@Test void unknownBlocksSameNameOnly()            // r 結果未知後 createMatch("r") 的訊息含 "drop"；createMatch("s") 成功；sender.calls 只有兩則 make
@Test void lateReplyCompletesUnknownRequest()     // r 結果未知 → MatchCreated(7,"r") → c.room().join().matchId() == 7；pendingMatches() 為空；usedSlots() == 1
@Test void fifthRoomRejectedLocally()             // attach(1)；a、b 成功；c 留在 CREATING → usedSlots() == 4；createMatch("d") 訊息 "名額已滿（4/4）"，不送 make
@Test void closedRoomReleasesSlot()               // 用滿後 MatchClosed(1) → usedSlots() == 3，可再 createMatch
@Test void dropReleasesSlotButNotTheName()        // r 結果未知 → drop(" r ") 為 true；c.room() isCancelled；usedSlots() == 0；createMatch("r") 訊息 "此房名已 drop，本次執行不能再用，請換一個房名：r"
@Test void dropIgnoresCreatingRequest()           // 未逾時 → drop("r") 為 false
@Test void replyAfterDropRegistersRoomOnce()      // r 結果未知 → drop → MatchCreated(9,"r") → openRooms() 只有 #mp_9；usedSlots() == 1
@Test void passivelyTrackedRoomCountsTowardSlots()// 未登記房號的 PlayerJoined → usedSlots() == 1
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-match test -Dtest=MatchServiceTest` → 編譯失敗
- [ ] **Step 3: 實作**：請求存在 `LinkedHashMap<String, Request>`（`synchronized` 保護）；drop 名單為 `Set<String>`。`onSent` 與計時器都綁定**那一個 `Request` 物件**：只在 map 中同名的仍是同一個物件、且狀態仍是 `CREATING` 時才排定計時或改成 `UNKNOWN`，已完成或已被取代的請求一律略過。`MatchReplayIntegrationTest`、`bancho-match-spring` 用到 `createMatch` 處一併調整。CLAUDE.md 的 `MatchService` 段落改寫：名額 4、送出後才計時、結果未知只擋同名、drop 與房名不可重用。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-match,bancho-match-spring -am test`** → PASS
- [ ] **Step 5: Commit** `feat(match): 建房名額與結果未知，送出後才計時，drop 後房名不可重用`

---

### Task 6: `!mp password` 遮蔽

**Files:**
- Create: `bancho-match/src/main/java/cc/alexlin7/bancho/match/BanchoRedaction.java`
- Modify: `bancho-match-spring/src/main/java/cc/alexlin7/bancho/match/spring/MatchAutoConfiguration.java`
- Test: `bancho-match/src/test/java/cc/alexlin7/bancho/match/BanchoRedactionTest.java`、`bancho-match-spring/src/test/...`（既有的 auto-configuration 測試類別）
- Modify: `CLAUDE.md`（`match` 段落）

**Interfaces:**
- Consumes: Task 3 的 `OsuIrcClient.setOutboundRedactor`。
- Produces: `public final class BanchoRedaction { public static String redactOutbound(String line); }`：符合 `(?i)^(PRIVMSG \S+ :!mp\s+password\s+)\S.*$` 時把密碼部分換成 `***`，其他原樣回傳。`MatchAutoConfiguration` 在有 `OsuIrcClient` bean 時以 `InitializingBean`（或等效方式）呼叫 `client.setOutboundRedactor(BanchoRedaction::redactOutbound)`，保證在 lifecycle 連線前完成。

- [ ] **Step 1: 寫失敗的測試**
```java
@Test void masksPassword()          // "PRIVMSG #mp_1 :!mp password hunter2" → "PRIVMSG #mp_1 :!mp password ***"
@Test void masksPasswordTypedThroughMsg() // "PRIVMSG BanchoBot :!MP PASSWORD hunter 2" → 不含 hunter
@Test void keepsClearPasswordAndOthers()  // "PRIVMSG #mp_1 :!mp password"、"PRIVMSG #mp_1 :!mp start 10" → 原樣
// auto-configuration 測試：ApplicationContextRunner（auto-start=false、帳密）→ 對 client 發 sendMessage("#mp_1","!mp password x") 前註冊 IrcTrafficListener，
// 以 FakeIrcServer 連線後 sent 收到 "PRIVMSG #mp_1 :!mp password ***"
@Test void autoConfigurationInstallsRedactor()
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-match,bancho-match-spring -am test -Dtest=BanchoRedactionTest+MatchAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`（測試類別名稱以實際存在者為準）
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑 `./mvnw -pl bancho-match,bancho-match-spring -am test`** → PASS
- [ ] **Step 5: Commit** `feat(match): 遮蔽 log 與觀察者中的 !mp password`

---

### Task 7: shell 的 `make`、`rooms`、`drop`

**Files:**
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/MatchCommands.java`
- Test: `bancho-shell/src/test/java/cc/alexlin7/bancho/shell/MatchCommandsTest.java`、`ShellCommandParsingTest.java`
- Modify: `CLAUDE.md`（shell 指令清單）

**Interfaces:**
- Consumes: Task 5 的 `MatchCreation`、`PendingMatch`、`MatchService.drop/pendingMatches/usedSlots/MAX_ROOMS`。
- Produces: `drop <房名…>` 指令（group `房間`，`InputGuard.check(context, InputGuard.ANY)`）；`lastMake()` 回傳 `MatchCreation` 的 `room()` future（測試用）。

使用者訊息（逐字）：
- 結果未知：`開房結果未知：<房名>；若遊戲內沒有這間房，用 drop <房名> 釋放名額（之後不能再用這個房名）`
- 晚到成功：`房間已建立（晚到的回覆）：#mp_<id> <房名>`，其餘處理（切換房間、套用範本）和準時成功相同
- 準時成功：維持 `房間已建立：#mp_<id> <房名>`
- 被 drop：不印任何東西
- drop 成功：`已釋放：<房名>`；失敗：`找不到結果未知的開房請求：<房名>`
- `rooms`：房間列表後接 `建房中：<房名>`／`結果未知：<房名>` 各一行，最後一行 `名額 <used>/4`；全空時 `目前沒有開著的房間（名額 0/4）`
- `MatchService` 拒絕時沿用 `RoomResolver.guarded` 的 `錯誤：<訊息>`

- [ ] **Step 1: 寫失敗的測試**（`MatchService` 用 200ms 逾時，`RecordingIrcSender` 會立即執行 `onSent`）
```java
@Test void makeTimeoutPrintsUnknownHint()
@Test void lateReplyAfterUnknownStillUsesRoom()    // printed 含 "房間已建立（晚到的回覆）：#mp_5 r"；session.currentRoom() == 5
@Test void droppedRequestDoesNotPrintFailure()     // 逾時 → drop r → printed 只有結果未知那一行
@Test void dropReleasesSlot()                      // "已釋放：r"；再 drop == "找不到結果未知的開房請求：r"；make r 回 "錯誤：此房名已 drop，本次執行不能再用，請換一個房名：r"
@Test void makeRejectedWhenSlotsFull()             // "錯誤：名額已滿（4/4）"
@Test void roomsListsPendingAndSlots()             // 1 間房 + 1 筆結果未知 → 含 "結果未知：r"，最後一行 "名額 2/4"
```
`ShellCommandParsingTest` 加 `drop 第一場 練習` 經 parser 釋放對應請求的案例。
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-shell -am test -Dtest=MatchCommandsTest+ShellCommandParsingTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: 實作**：`whenComplete` 中 `CancellationException` 直接略過；「晚到」以 `unknown().isDone()` 判斷。
- [ ] **Step 4: 跑同一指令** → PASS
- [ ] **Step 5: Commit** `feat(shell): make 顯示結果未知、rooms 顯示名額、新增 drop`

---

### Task 8: shell 的 `login` 與只在已登入時放行送訊指令

**Files:**
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/LoginCommands.java`
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/LoginGuard.java`
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/ConnectionStatusPrinter.java`
- Modify: `MatchCommands.java`、`ChatCommands.java`
- Modify: `bancho-shell/src/main/resources/application.properties`
- Test: `bancho-shell/src/test/java/cc/alexlin7/bancho/shell/LoginCommandsTest.java`、`LoginGuardTest.java`、`MatchCommandsTest.java`、`ChatCommandsTest.java`
- Modify: `CLAUDE.md`（憑證段落、shell 段落）

**Interfaces:**
- Consumes: Task 2 的 `OsuIrcClient.login/state/hasCredentials/addConnectionListener`、Task 4 的 `osu.irc.deferred-login`。
- Produces:
  - `@Component class LoginGuard { LoginGuard(OsuIrcClient client); String check(); }`，另有 package-private `LoginGuard(Supplier<IrcConnectionState> state)` 給測試。`check()`：`REGISTERED` 回 null；`IDLE`、`AUTH_FAILED` 回 `錯誤：請先 login`；`CONNECTING` 回 `錯誤：正在連線 Bancho，請等登入完成`；`CLOSED` 回 `錯誤：連線已關閉`。`MatchCommands`、`ChatCommands` 建構子多一個 `LoginGuard` 參數。
  - 要擋的指令：`make`、`attach`、`invite`、`map`、`mods`、`set`、`start`、`timer`、`abort`、`close`、`settings`、`password`、`addref`、`mp`、`say`、`msg`。不擋：`use`、`rooms`、`drop`、`login`、`help`、`quit`。檢查放在 `InputGuard` 之後、任何提示輸入之前。
  - `login` 指令（group `帳號`，不收參數）：
    - `CONNECTING`／`REGISTERED`：回 `錯誤：已登入，要換帳號請重開程式`
    - `TextPrompt` 提示 `osu! 帳號：`；`SecretPrompt` 提示 `IRC 密碼（不是帳號密碼，到 https://osu.ppy.sh/home/account/edit#legacy-api 取得）：`
    - 任一取消：回 `已取消登入`
    - 否則 `client.login(...)` 並回 `登入中：<帳號>`
  - `ConnectionStatusPrinter`（建構時 `client.addConnectionListener`）：`REGISTERED` 印 `已登入 Bancho`；`AUTH_FAILED` 印 `登入失敗：帳號或 IRC 密碼錯誤，請重新 login`。在 `ApplicationStartedEvent`（早於 shell runner）時若 `!client.hasCredentials()` 印 `尚未登入，請先執行 login`。
  - `application.properties`：`osu.irc.deferred-login=true`；`osu.irc.password=${OSU_IRC_PASSWORD:}`。

- [ ] **Step 1: 寫失敗的測試**
```java
// LoginGuardTest
@Test void onlyRegisteredPasses()   // 五種狀態對應上面的回傳值
// LoginCommandsTest（FakeIrcServer + OsuIrcClient(null 帳密)，prompt 以 lambda 回傳預設值）
@Test void loginPromptsAndConnects()             // "登入中：test user"；awaitLine("PASS secret")
@Test void cancelledPromptDoesNotLogin()         // 帳號提示回 empty → "已取消登入"；state() == IDLE；之後 login 可成功
@Test void secondLoginRejected()                 // CONNECTING 時回 "錯誤：已登入，要換帳號請重開程式"
@Test void authFailurePrintsHintAndAllowsRetry() // 464 → printed 含登入失敗訊息；再 login 回 "登入中：…"
// MatchCommandsTest / ChatCommandsTest
@Test void makeRejectedWhileConnecting()         // LoginGuard 回 CONNECTING → "錯誤：正在連線 Bancho，請等登入完成"，sender.calls 為空
@Test void msgRejectedBeforeLoginWithoutPrompting() // IDLE → "錯誤：請先 login"，沒有任何 prompt 被呼叫
@Test void roomsAndDropWorkBeforeLogin()
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-shell -am test -Dtest=LoginGuardTest+LoginCommandsTest+MatchCommandsTest+ChatCommandsTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: 實作**；既有測試建構 `MatchCommands`／`ChatCommands` 處傳入回 `REGISTERED` 的 `LoginGuard`。`ShellIntegrationTest` 等 `@SpringBootTest` 的 client 狀態為 `IDLE`（auto-start=false）：在 `TestShellRunnerConfiguration` 提供一個 `@Primary` 回 `REGISTERED` 的 `LoginGuard` bean，並在註解說明原因。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-shell -am test`** → 全部 PASS
- [ ] **Step 5: 手動驗證**：不設環境變數執行 `./mvnw -pl bancho-shell -am compile spring-boot:run`，看到 `尚未登入，請先執行 login`；`make x` 回 `錯誤：請先 login`；`login` 以錯誤密碼得到登入失敗訊息。正確密碼的登入由使用者驗證。
- [ ] **Step 6: Commit** `feat(shell): 加入 login 指令，只在已登入時放行送訊指令`

---

### Task 9: 樣本檔

**Files:**
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/SampleFilter.java`
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/SampleRecorder.java`
- Modify: `bancho-shell/src/main/resources/application.properties`、`bancho-shell/src/test/resources/application.properties`
- Test: `bancho-shell/src/test/java/cc/alexlin7/bancho/shell/SampleFilterTest.java`、`SampleRecorderTest.java`
- Modify: `CLAUDE.md`（log 段落、「慣例」的樣本段落）

**Interfaces:**
- Consumes: Task 3 的 `IrcTrafficListener`、`OsuIrcClient.addTrafficListener`（`sent` 收到的行已由 Task 6 遮蔽）。
- Produces:
  - `final class SampleFilter { static boolean keepReceived(String line); static boolean keepSent(String line); }`
    - `keepReceived`：`IrcMessage.parse` 後，PING／PONG 不收；nick 為 `BanchoBot` 的 PRIVMSG／NOTICE 收；沒有 prefix 或 prefix 不含 `!` 的 NOTICE 收；三位數字指令收；其他不收。
    - `keepSent`：只收 `PRIVMSG <target> :…` 且 target 為 `BanchoBot`（不分大小寫）或以 `#mp_` 開頭。
  - `class SampleRecorder implements IrcTrafficListener`，建構子 `SampleRecorder(Path directory, Clock clock)`；由 `@Bean`（`@ConditionalOnProperty bancho.samples.enabled`，預設 true）建立並 `client.addTrafficListener(...)`；`directory` 取 `logging.file.name` 的父目錄（沒有父目錄時用工作目錄）。
  - 檔名 `samples-<yyyy-MM-dd>.irc`（依 `clock` 的本地日期，跨日換檔）。新檔第一行 `# bancho-shell 樣本檔；匯入測試資源前把自己的 nick 改成 test_user`。
  - 收進的行原樣寫入，`HH:mm:ss` 與上一次寫入不同時先寫 `# HH:mm:ss`；送出的行寫成 `# HH:mm:ss -> <行>`（註解行，回放測試會略過）。
  - 寫入失敗只 `log.warn` 一次（`[shell] 無法寫入樣本檔`），之後靜默略過。
  - test `application.properties` 設 `bancho.samples.enabled=false`。

- [ ] **Step 1: 寫失敗的測試**
```java
// SampleFilterTest
@Test void keepsBanchoBotMessages()       // ":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Closed the match" → true
@Test void keepsServerNoticeAndNumerics() // ":cho.ppy.sh NOTICE * :x"、":cho.ppy.sh 464 u :Bad authentication token." → true
@Test void dropsPlayerChatAndPing()       // ":Alexlin86!cho@ppy.sh PRIVMSG #mp_1 :gl"、"PING :cho.ppy.sh"、":A!cho@ppy.sh PRIVMSG me :hi" → false
@Test void keepsSentToBanchoBotAndMpOnly()// "PRIVMSG #mp_1 :!mp start 10"、"PRIVMSG banchobot :!mp make r" → true；"PRIVMSG Alexlin86 :hi"、"JOIN #mp_1" → false
// SampleRecorderTest（@TempDir，固定 Clock）
@Test void writesReceivedLinesVerbatimWithTimestampComments() // 內容 == 標頭、"# 12:00:00"、原始行（含行尾空白）
@Test void writesSentLinesAsComments()    // "# 12:00:00 -> PRIVMSG #mp_1 :!mp start 10"
@Test void createsMissingDirectory()      // directory = tempDir.resolve("logs/nested")
@Test void rollsOverByDate()
@Test void replayFilterReadsOnlyReceivedLines() // 以「略過空行與 # 開頭」讀回，只剩收進的原始行
```
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw -pl bancho-shell -am test -Dtest=SampleFilterTest+SampleRecorderTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: 實作**：寫入用 `synchronized` 方法，每筆 `Files.writeString(..., CREATE, APPEND)`。
- [ ] **Step 4: 跑 `./mvnw -pl bancho-shell -am test`** → PASS
- [ ] **Step 5: Commit** `feat(shell): 把 BanchoBot 與 Bancho 系統訊息寫進樣本檔`

---

### Task 10: jpackage 打包腳本與使用說明

**Files:**
- Create: `bancho-shell/src/dist/package-windows.ps1`
- Create: `bancho-shell/src/dist/使用說明.txt`
- Modify: `CLAUDE.md`（指令段落）

**Interfaces:**
- Produces: `pwsh bancho-shell/src/dist/package-windows.ps1 -Version <X.Y.Z> -Jar <可執行 jar 路徑> -OutDir <輸出目錄>`，產生 `<OutDir>/bancho-shell-<X.Y.Z>-windows.zip`，解壓縮後最上層是 `bancho-shell/`（內含 `bancho-shell.exe`、`app/`、`runtime/`、`使用說明.txt`）。`-Version` 不符合 `^\d+\.\d+\.\d+$` 時直接失敗。

jpackage 參數：`--type app-image --name bancho-shell --input <只含該 jar 的暫存目錄> --main-jar <jar 檔名> --app-version <Version> --win-console`，以及 `--java-options` 各一次：`--enable-native-access=ALL-UNNAMED`、`-Dlogging.file.name=$APPDIR/../logs/bancho.log`、`-Dspring.shell.history.name=$APPDIR/../bancho-history`（`$APPDIR` 以單引號傳入，由 launcher 展開）。完成後把 `使用說明.txt` 複製到 app-image 根目錄，再 `Compress-Archive`。

`使用說明.txt` 依 spec 4.4 的五點，語氣給朋友看，並列出：取得 IRC 密碼的網址、`login` → `make <房名>` → `invite <名稱>` → `map <id>` → `start 10` → `close` 的基本流程、`rooms`／`drop` 的用途（drop 後房名不能再用）、斷線期間被關掉的房間會一直佔名額（重開程式即可）、回傳整個 `logs/` 資料夾，以及樣本檔「會記／不會記」的清單。

- [ ] **Step 1: 寫腳本與說明**
- [ ] **Step 2: 本機打包**：`./mvnw -pl bancho-shell -am package -DskipTests`，再執行腳本（`-Version 0.1.2`、`-OutDir $CLAUDE_JOB_DIR/tmp/dist`）→ 產生 zip
- [ ] **Step 3: 驗證**：解壓縮到暫存目錄，在 `PATH` 與 `JAVA_HOME` 都不含 JDK 的 PowerShell 中執行 `bancho-shell\bancho-shell.exe`：看到 `尚未登入，請先執行 login`；`quit` 後解壓縮目錄的 `logs/bancho.log` 與 `bancho-history` 存在。`-Version 0.1` 時腳本失敗。
- [ ] **Step 4: Commit** `build: 加入 Windows jpackage 打包腳本與測試版使用說明`

---

### Task 11: GitHub Actions 發版 workflow

**Files:**
- Create: `.github/workflows/shell-release.yml`
- Modify: `CLAUDE.md`（新增「發布 shell 測試版」小節）

**Interfaces:**
- Consumes: Task 10 的 `package-windows.ps1`。

workflow 規格：
- `on: push: tags: ['shell-v*']`；`permissions: contents: write`；`runs-on: windows-latest`；`defaults.run.shell: pwsh`。
- 步驟：`actions/checkout@v5` → `actions/setup-java@v5`（`distribution: temurin`、`java-version: '25'`、`cache: maven`）→ 由 `$env:GITHUB_REF_NAME` 去掉 `shell-v` 得到版本，不符合 `^\d+\.\d+\.\d+$` 就失敗 → `./mvnw.cmd -B package`（含測試）→ 找出 `bancho-shell/target/bancho-shell-*.jar`（排除 `*.original`、`-sources`、`-javadoc`），複製為 `bancho-shell-<版本>.jar` → 執行 `package-windows.ps1` → `gh release create $env:GITHUB_REF_NAME --prerelease --title "bancho-shell <版本>" --notes "測試版，使用方式見 zip 內的使用說明.txt" <jar> <zip>`（`GH_TOKEN: ${{ github.token }}`）。
- 不改 pom、不 commit。

CLAUDE.md 小節：shell 測試版以 `shell-vX.Y.Z` tag 發布（`git tag -a shell-vX.Y.Z -m "bancho-shell X.Y.Z" && git push origin shell-vX.Y.Z`），版本取自 tag、與 `<revision>` 及 Central 無關；`v*` tag 仍專用於 Central。

- [ ] **Step 1: 寫 workflow 與 CLAUDE.md**
- [ ] **Step 2: 靜態檢查**：有 `actionlint` 就執行；否則 `python -I -c "import yaml,sys; yaml.safe_load(open(sys.argv[1], encoding='utf-8'))" .github/workflows/shell-release.yml`
- [ ] **Step 3: Commit** `build: 加入 shell-v tag 觸發的 GitHub Actions 發版`
- [ ] **Step 4: 實際發版驗證（需使用者同意）**：分支合回 main 並推送後，由使用者決定推送 `shell-v0.1.2` tag，確認 Release 為 pre-release 且附兩個檔案。推送 tag 屬於對外動作，執行者不得自行推送。
