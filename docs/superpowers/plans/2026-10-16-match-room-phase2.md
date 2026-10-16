# 多人房管理：階段二（Spring Shell）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 Spring Shell 取代自製的 console：房間指令、Tab 補全、房間設定範本，並把 IRC 的啟動改成 `SmartLifecycle`。

**Architecture:** 新增 `shell` 套件（依賴 `match`、`irc`），以 `@Command` 方法呼叫 `MatchService` / `MatchRoom`；收到的 IRC 訊息經 JLine `LineReader.printAbove()` 印出。範本放在 `match/preset`，由 `MatchRoom.apply(MatchPreset)` 依序送出指令。刪除 `console` 套件。

**Tech Stack:** Spring Shell 4.0.3（`spring-shell-starter` + `spring-shell-jline`，版本不受 Boot 管理）、JLine、Spring Boot 4.1.1。

**Spec:** `docs/superpowers/specs/2026-10-16-match-room-design.md` 第 6 節「階段二」與第 9 節。

## 階段二第一步（驗證）的結果

2026-10-16 以拋棄式副本實測（不保留程式碼）：

| 項目 | 結果 |
|---|---|
| Spring Shell 4.0.3 在 Boot 4.1.1 上執行 | 可以。starter 只帶 core；**JLine 版需另外加 `spring-shell-jline`** |
| 互動迴圈是否擋住 `ApplicationReadyEvent` | **會**。shell 以 `ApplicationRunner`（bean 名稱 `springShellApplicationRunner`）執行，ready 事件在 `quit` 之後才發生；`SmartLifecycle.start` 在 runner 之前執行 |
| 取得 `LineReader` / `Terminal` | 可以，bean 名稱 `lineReader`、`terminal` |
| `quit` 後結束程式 | `spring.main.keep-alive=true` 時 JVM **不會結束**；改為 `false` 後 exit 0，`SmartLifecycle.stop` 與 IRC client 的 close 都有執行。`spring.shell.context.close=true` 會在 Boot 發佈 ready 事件時報「context has been closed already」並 exit 1，**不採用** |
| 非互動模式且沒有參數 | 拋 `CommandNotFoundException`，`SpringApplication.run` 失敗 |
| 補全 API | `@Command(completionProvider = "beanName")`；`CompletionProvider extends Function<CompletionContext, List<CompletionProposal>>`；`CompletionContext` 有 `getWords()`、`getWordIndex()`、`currentWord()`、`getCommandOption()`；`new CompletionProposal(value).description(...)` |
| 註解套件 | `org.springframework.shell.core.command.annotation.{Command, Option, Argument, Arguments}` |
| 測試支援 | `org.springframework.shell:spring-shell-starter-test:4.0.3`（`ShellTestClient`、`ShellAssertions`） |

## Global Constraints

- 沿用階段一的 Global Constraints（package 依賴方向、package-private、`@Slf4j`、Sonar 規則、測試不連真的 Bancho）。新的依賴方向：`shell → match → irc`；`irc`、`match` 不得 import `shell`。
- Spring Shell 版本寫在 `<properties>` 的 `<spring-shell.version>4.0.3</spring-shell.version>`。
- `spring.main.keep-alive=false`：互動 shell 本身會佔住 main thread，`quit` 時才能結束程式。日後若有不開 shell 的執行方式（例如只跑 Discord），該設定要改回 `true`。
- 測試一律不得啟動互動 shell、不得讀 stdin。
- log 前綴新增 `[shell]`。
- 不在範圍：每個指令建立 trace span、Discord 傳遞 trace context（已記錄，之後處理）。

## Review Focus

1. **沒有目前房間時下房間指令**（例如剛啟動就打 `invite A`）：要顯示「請先 make / use / attach，或加 --room」，不能拋出堆疊。→ Task 3 `roomCommandWithoutCurrentRoomExplains`。
2. **`--room` 指定一個不存在或已關閉的房號**：顯示清楚的錯誤，不送任何指令。→ Task 3 `unknownOrClosedRoomIsReported`。
3. **範本只填部分欄位**（例如只有 `mods`）：只送該欄位的指令，不送 `!mp set`。→ Task 5 `appliesOnlyFilledFields`。
4. **補全時游標前已輸入部分文字**（例如 `invite Al`）：只回傳開頭相符的候選（不分大小寫）。→ Task 4 `filtersByCurrentWordPrefixIgnoringCase`。
5. **`make --preset` 指定不存在的範本**：開房前就拒絕，不送 `!mp make`。→ Task 5 `unknownPresetRejectedBeforeMake`。

---

## Task 0 的由來

使用者在 Grafana 看到 `service_name=unknown_service` 的 log，來源都是 `OtelProfileTest`：它啟用 `otel` profile，`./mvnw test` 時把 log 和 metrics 送到開發機上的 collector（localhost:4318）；test 的 `application.properties` 遮蔽了 main 的同名檔，沒有 `spring.application.name`，所以 service name 變成 `unknown_service`。使用者決定：測試照常送到 collector，只要用獨立的 service name（`bancho-test`）與正式資料區隔；不要把端點導向沒有服務的 port（會是 anti-pattern）。

---

## File Structure

```
src/main/java/idv/alexlin7/tw/bancho/
├── irc/OsuIrcLifecycle.java                   Task 1（新）；OsuIrcConfiguration 改用它
├── match/MatchRoom.java                        Task 5：新增 apply(MatchPreset)
├── match/preset/MatchPreset.java               Task 5
├── match/preset/MatchPresetProperties.java     Task 5
├── shell/ShellOutput.java                      Task 2：輸出介面 + LineReader 實作
├── shell/ShellMessagePrinter.java              Task 2：IrcMessageListener，取代 ConsoleMessageListener
├── shell/ShellSession.java                     Task 3：目前房間
├── shell/MatchCommands.java                    Task 3、5
├── shell/ChatCommands.java                     Task 3：say、msg
├── shell/KnownUsers.java                       Task 4
├── shell/ShellCompletions.java                 Task 4：CompletionProvider beans
└── console/                                    Task 2：整個刪除
```

---

### Task 0: 測試的 OTel 資料使用獨立的 service name

**Files:**
- Modify：`src/test/resources/application.properties`、`src/test/java/idv/alexlin7/tw/bancho/observability/OtelProfileTest.java`

- [ ] **Step 1：寫失敗的測試**

`OtelProfileTest` 新增 `usesTestServiceName`：注入 `Environment`，斷言 `spring.application.name` 為 `bancho-test`。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=OtelProfileTest` → `usesTestServiceName` 失敗（application name 為 null）。

- [ ] **Step 3：修改設定**

- `src/test/resources/application.properties` 加 `spring.application.name=bancho-test`，註解說明「與正式的 bancho 在 OTel 中區隔」。

- [ ] **Step 4：確認測試通過並跑全部測試**

Run: `./mvnw test` → `BUILD SUCCESS`；之後 Grafana 中測試的資料出現在 `service_name="bancho-test"`，不再有新的 `unknown_service`。

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "test: 測試的 OTel 資料使用 bancho-test service name"
```

---

### Task 1: IRC 啟動改用 `SmartLifecycle`

**Files:**
- Create：`src/main/java/idv/alexlin7/tw/bancho/irc/OsuIrcLifecycle.java`
- Modify：`src/main/java/idv/alexlin7/tw/bancho/irc/OsuIrcConfiguration.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/irc/OsuIrcConfigurationTest.java`

**Interfaces:**
- Produces：`class OsuIrcLifecycle implements SmartLifecycle`（package-private），建構子 `OsuIrcLifecycle(OsuIrcClient client)`；`start()` 呼叫 `client.start()`，`stop()` 呼叫 `client.close()`，`isRunning()` 反映兩者。`OsuIrcConfiguration` 以 `@Bean @ConditionalOnProperty(prefix = "osu.irc", name = "auto-start", havingValue = "true", matchIfMissing = true) OsuIrcLifecycle osuIrcLifecycle(OsuIrcClient client)` 取代 `osuIrcAutoStarter`。client bean 保留 `destroyMethod = "close"`（`close()` 是 idempotent）。

- [ ] **Step 1：寫失敗的測試**

修改 `OsuIrcConfigurationTest`：
- `autoStarterCanBeDisabled` 的 bean 名稱改為 `osuIrcLifecycle`。
- 新增 `lifecycleConnectsOnContextStartAndQuitsOnClose`：用 `FakeIrcServer`，`ApplicationContextRunner` 載入 `OsuIrcConfiguration`，屬性帶 host / port / 帳密（username `someone`）。`run` 內（context 已 refresh）：斷言 `server.awaitConnection(3s)` 為 true、`server.awaitLine("NICK someone", 3s)` 不為 null，接著 `server.welcome()` 讓 client 登入。`run` 結束（context 關閉）後：斷言 `server.awaitLine("QUIT :Shutdown", 3s)` 不為 null。
- 新增 `lifecycleNotCreatedWhenAutoStartDisabled`：`auto-start=false` 時 `FakeIrcServer` 在 1 秒內沒有連線。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=OsuIrcConfigurationTest`
Expected: `lifecycleConnectsOnContextStartAndQuitsOnClose` 失敗（`ApplicationContextRunner` 不會發佈 `ApplicationReadyEvent`，client 沒有連線），`autoStarterCanBeDisabled` 失敗（找不到 `osuIrcLifecycle`）。

- [ ] **Step 3：實作 `OsuIrcLifecycle` 並修改 `OsuIrcConfiguration`**

`OsuIrcConfiguration` 的 Javadoc 改為說明：用 `SmartLifecycle` 是因為 Spring Shell 以 `ApplicationRunner` 佔住 main thread，`ApplicationReadyEvent` 要到 shell 結束才會發生。

- [ ] **Step 4：確認測試通過並跑全部測試**

Run: `./mvnw test` → `BUILD SUCCESS`

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "refactor: IRC 改由 SmartLifecycle 啟動"
```

---

### Task 2: 加入 Spring Shell、以 shell 輸出取代 console

**Files:**
- Modify：`pom.xml`、`src/main/resources/application.properties`、`src/test/resources/application.properties`
- Create：`shell/ShellOutput.java`、`shell/ShellMessagePrinter.java`、`shell/ShellConfiguration.java`
- Delete：`src/main/java/idv/alexlin7/tw/bancho/console/`、`src/test/java/idv/alexlin7/tw/bancho/console/`
- Modify：`irc/OsuIrcConfigurationTest.java`（移除 console 相關測試）
- Test：`src/test/java/idv/alexlin7/tw/bancho/shell/ShellMessagePrinterTest.java`、`src/test/java/idv/alexlin7/tw/bancho/BanchoApplicationTests.java`

**Interfaces:**
- Produces：

```java
@FunctionalInterface
public interface ShellOutput {           // shell 套件內各元件印訊息都經過它
    void println(String text);
}
@Component class ShellMessagePrinter implements IrcMessageListener   // 建構子 ShellMessagePrinter(ShellOutput out)
@Configuration class ShellConfiguration {
    @Bean ShellOutput shellOutput(ObjectProvider<LineReader> lineReader);  // 有 LineReader 時用 printAbove，否則 System.out.println（@SuppressWarnings("java:S106")）
}
```

- [ ] **Step 1：寫失敗的測試**

`ShellMessagePrinterTest`：把 `ConsoleMessageListenerTest` 的三個案例改為對 `ShellOutput` 收集到的字串斷言（頻道訊息 `[#mp_123] BanchoBot: All players are ready`、私訊 `[PM] BanchoBot: 你好`、CTCP ACTION `[PM] * Someone is listening to a song`）。

`BanchoApplicationTests.contextLoads` 維持不變（它就是「測試不會啟動互動 shell」的檢查：若互動 shell 被啟動，測試會卡住或因非互動模式沒有參數而失敗）。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=ShellMessagePrinterTest` → 編譯失敗。

- [ ] **Step 3：修改依賴與設定**

- `pom.xml`：新增 `spring-shell-starter`、`spring-shell-jline`（compile）、`spring-shell-starter-test`（test），版本 `${spring-shell.version}`。
- `src/main/resources/application.properties`：`spring.main.keep-alive=false`，註解改為說明「互動 shell 佔住 main thread；quit 時才能結束程式（true 時 quit 後 JVM 不會結束）」。
- `src/test/resources/application.properties`：移除 `bancho.console.enabled`，加上 `spring.autoconfigure.exclude=org.springframework.shell.core.autoconfigure.ShellRunnerAutoConfiguration` 與註解「測試不啟動 shell runner」。若此排除造成其他 shell bean 缺少相依而啟動失敗，改為在 test 屬性設 `spring.shell.interactive.enabled=false` 並在 `src/test/java/.../TestShellRunnerConfiguration.java` 以 `@TestConfiguration` 提供名為 `springShellApplicationRunner` 的空 `ApplicationRunner`（`ShellRunnerAutoConfiguration` 對該名稱是 `@ConditionalOnMissingBean`），並用 `@Import` 加到 `@SpringBootTest` 的測試類別；擇一並記錄 ruling。
- 刪除 `console` 套件（main 與 test）；`OsuIrcConfigurationTest` 刪除 `registersEveryListenerBeanWithoutCircularDependency` 中的 `ConsoleConfiguration`（改成只載入 `OsuIrcConfiguration`、`MatchConfiguration`，斷言沒有 listener bean 也能建立 client）與 `consoleCanBeDisabled`。

- [ ] **Step 4：實作 `ShellOutput`、`ShellMessagePrinter`、`ShellConfiguration`**

`ShellMessagePrinter` 的格式邏輯從 `ConsoleMessageListener` 搬來。

- [ ] **Step 5：確認全部測試通過**

Run: `./mvnw test` → `BUILD SUCCESS`，且執行時間內沒有卡住。

- [ ] **Step 6：手動冒煙**

Run（Git Bash）：`./mvnw -q package -DskipTests && (sleep 3; printf 'help\nquit\n') | timeout 20 java -jar target/bancho-0.0.1-SNAPSHOT.jar --osu.irc.username=a --osu.irc.password=b --osu.irc.auto-start=false; echo exit=$?`
Expected：輸出含 `quit` 的說明、`exit=0`。

- [ ] **Step 7：Commit**

```bash
git add -A
git commit -m "feat: 以 Spring Shell 取代自製 console"
```

---

### Task 3: 房間與聊天指令

**Files:**
- Create：`shell/ShellSession.java`、`shell/MatchCommands.java`、`shell/ChatCommands.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/shell/MatchCommandsTest.java`、`ChatCommandsTest.java`、`ShellIntegrationTest.java`

**Interfaces:**
- Consumes：`MatchService`（`createMatch`、`attach`、`room`、`openRooms`）、`MatchRoom`、`IrcSender`、`ShellOutput`。
- Produces：

```java
@Component public class ShellSession {            // volatile Long currentRoom
    public Optional<Long> currentRoom();
    public void use(long matchId);
}
@Component class MatchCommands {                   // 建構子 (MatchService, ShellSession, ShellOutput)
    // 以下皆為 @Command 方法；房間相關的方法都有 @Option(longName = "room") Long room
    String make(String name);                       // 非同步：立即回傳「已送出開房請求：<name>」，結果經 ShellOutput 印出並 use()
    String attach(long matchId);
    String use(long matchId);
    String rooms();                                  // 格式沿用階段一 /rooms
    String invite(String username, Long room);
    String map(long beatmapId, Long room);
    String mods(List<String> mods, Long room);
    String set(int teamMode, int scoreMode, int size, Long room);
    String start(Integer delaySeconds, Long room);  // null = 立即
    String timer(Integer seconds, Long room);       // null = BanchoBot 預設 30 秒，送 "!mp timer"
    String abort(Long room);  String close(Long room);  String settings(Long room);
    String password(String password, Long room);    // null 或空白 = 移除
    String addref(List<String> usernames, Long room);
}
@Component class ChatCommands {                    // 建構子 (IrcSender, ShellSession, MatchService)
    String say(List<String> words, Long room);      // 以空白合併後送到房間頻道
    String msg(String target, List<String> words);
}
```

- 房間解析：`room` 參數優先，否則 `ShellSession.currentRoom()`；都沒有時回傳 `請先用 make / use / attach 選擇房間，或加上 --room <房號>`；房號未登記回傳 `找不到房間：<id>`；已關閉回傳 `房間已關閉：#mp_<id>`。
- 成功的房間指令回傳 `已送出：<指令>`（例如 `已送出：!mp invite Alexlin86`）。
- `IllegalArgumentException` / `IllegalStateException` 一律轉成回傳字串 `錯誤：<訊息>`，不讓例外冒到 shell。
- `mods`、`addref`、`say`、`msg` 的多字參數：先用 `@Arguments List<String>`；若 Spring Shell 4.0.3 不支援以 `List<String>` 收集剩餘參數，改為單一 `@Argument String` 並自行 `split("\\s+")`，記錄 ruling。

- [ ] **Step 1：寫失敗的測試**

`MatchCommandsTest`（直接呼叫方法，搭配 `RecordingIrcSender`、`new MatchService(sender, Duration.ofMillis(200))`、收集字串的 `ShellOutput`）：
- `makeSendsRequestAndUsesRoomWhenCreated`：`make("測試房")` 回傳含 `已送出開房請求：測試房`；`sender.calls` 含 `msg BanchoBot !mp make 測試房`；餵 `MatchCreated(122021273, "測試房")` 後輸出含 `房間已建立：#mp_122021273 測試房`、`session.currentRoom()` 為 122021273。
- `makeTimeoutIsPrinted`：等 future 失敗後輸出含 `開房逾時：r`（以 `MatchService.createMatch` 回傳的 future 等待不可得時，`MatchCommands` 提供 package-private `CompletableFuture<?> lastMake()`，與階段一相同作法）。
- `roomCommandUsesCurrentRoom`：`attach(42)`、`use(42)` 後 `invite("Alexlin86", null)` → `sender.calls` 含 `msg #mp_42 !mp invite Alexlin86`，回傳 `已送出：!mp invite Alexlin86`。
- `roomOptionOverridesCurrentRoom`：current 為 42、`attach(7)` 後 `abort(7L)` → `msg #mp_7 !mp abort`。
- `roomCommandWithoutCurrentRoomExplains`：新 session 下 `invite("A", null)` 回傳 `請先用 make / use / attach 選擇房間，或加上 --room <房號>`，`sender.calls` 為空。
- `unknownOrClosedRoomIsReported`：`invite("A", 999L)` → `找不到房間：999`；房間 1 餵 `MatchClosed(1)` 後 `abort(1L)` → `房間已關閉：#mp_1`；兩者皆不送指令。
- `invalidArgumentsBecomeErrorText`：`set(9, 0, 2, 42L)` 回傳以 `錯誤：` 開頭。
- `startAndTimer`：`start(null, 42L)` → `!mp start`；`start(10, 42L)` → `!mp start 10`；`timer(null, 42L)` → `!mp timer`；`timer(30, 42L)` → `!mp timer 30`。

`MatchRoom` 需要新增 `public void timer()`（無參數，送 `!mp timer`）；在 `MatchRoomTest` 加對應案例。

`ChatCommandsTest`：`say(List.of("gl", "hf"), 42L)` → `msg #mp_42 gl hf`；`msg("BanchoBot", List.of("!mp", "make", "x"))` → `msg BanchoBot !mp make x`。

`ShellIntegrationTest`（`@SpringBootTest` + `ShellTestClient`，依 `spring-shell-starter-test` 的用法）：送 `rooms` → 畫面含 `目前沒有開著的房間`；送 `help` → 畫面含 `make`、`invite`、`quit`。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest='MatchCommandsTest,ChatCommandsTest,ShellIntegrationTest,MatchRoomTest'` → 編譯失敗。

- [ ] **Step 3：實作**

`@Command` 的 `group` 設為 `"房間"` 與 `"聊天"`，`description` 用繁體中文。

- [ ] **Step 4：確認測試通過並跑全部測試**

Run: `./mvnw test` → `BUILD SUCCESS`

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: Spring Shell 房間與聊天指令"
```

---

### Task 4: Tab 補全

**Files:**
- Create：`shell/KnownUsers.java`、`shell/ShellCompletions.java`
- Modify：`shell/MatchCommands.java`（`@Command(completionProvider = ...)`）
- Test：`src/test/java/idv/alexlin7/tw/bancho/shell/KnownUsersTest.java`、`ShellCompletionsTest.java`

**Interfaces:**
- Produces：

```java
@Component public class KnownUsers implements IrcMessageListener {
    public List<String> all();                                   // 依名稱排序、不重複
    @EventListener public void onMatchEvent(MatchEvent event);   // PlayerInvited / PlayerJoined / PlayerMoved / SlotStatus 的名稱
    @Override public void onMessage(IrcMessage message);         // 私訊的發送者（nick 不是 BanchoBot）
    // 範本的 invite / referees 名單在 Task 5 加入
}
@Configuration class ShellCompletions {
    static final List<String> MODS = List.of("EZ", "NF", "HT", "HR", "SD", "PF", "DT", "NC", "HD", "FL", "RL", "AP", "SO", "SV2", "Freemod");
    @Bean CompletionProvider roomCompletion(MatchService matchService);         // 開著的房號，description 為房名
    @Bean CompletionProvider userCompletion(KnownUsers knownUsers, MatchService matchService);  // 也支援 --room
    @Bean CompletionProvider modsCompletion(MatchService matchService);
    static List<CompletionProposal> filter(List<CompletionProposal> proposals, String currentWord);  // 前綴、不分大小寫
}
```

（MODS 依 osu! wiki「Game modifier」頁面的 osu!stable 縮寫整理；`Freemod` 是 `!mp mods` 的特殊值。）

每個 provider 判斷目前在補哪個參數：`context.getCommandOption()` 不為 null 且 long name 為 `room` 時回傳房號候選；否則依指令回傳主要參數的候選。對應：`use`、`attach` → 房號；`invite`、`addref` → 使用者；`mods` → MODS；其他房間指令的 `--room` → 房號。

- [ ] **Step 1：寫失敗的測試**

`KnownUsersTest`：
- 餵 `PlayerJoined(1, "Alexlin86", 1)`、`PlayerInvited(1, "Some Player")`、私訊 `:Friend!cho@ppy.sh PRIVMSG test_user :hi` → `all()` 為 `[Alexlin86, Friend, Some Player]`。
- BanchoBot 的私訊不加入；頻道訊息的發送者不加入（避免把整個頻道的人都收進來）。

`ShellCompletionsTest`（以 `new CompletionContext(words, wordIndex, position, null, option)` 建立情境；`option` 為 null 代表主要參數）：
- `filtersByCurrentWordPrefixIgnoringCase`：KnownUsers 有 `Alexlin86`、`alice`、`Bob`，目前字為 `al` → 候選值為 `Alexlin86`、`alice`。
- `roomCompletionListsOpenRoomsWithNames`：開著 #mp_1「a」、#mp_2「b」，已關閉 #mp_3 → 候選 `1`（description `a`）、`2`（description `b`）。
- `modsCompletionOffersModList`：目前字為 `h` → `HT`、`HR`、`HD`。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest='KnownUsersTest,ShellCompletionsTest'` → 編譯失敗。

- [ ] **Step 3：實作並在指令上指定 `completionProvider`**

- [ ] **Step 4：確認測試通過並跑全部測試**

Run: `./mvnw test` → `BUILD SUCCESS`

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: Spring Shell Tab 補全"
```

---

### Task 5: 房間設定範本

**Files:**
- Create：`match/preset/MatchPreset.java`、`match/preset/MatchPresetProperties.java`
- Modify：`match/MatchRoom.java`、`match/MatchConfiguration.java`、`shell/MatchCommands.java`、`shell/KnownUsers.java`、`shell/ShellCompletions.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/match/MatchRoomPresetTest.java`、`match/preset/MatchPresetPropertiesTest.java`，修改 `MatchCommandsTest`、`KnownUsersTest`

**Interfaces:**
- Produces：

```java
public record MatchPreset(Integer teamMode, Integer scoreMode, Integer size, String password,
                          List<String> mods, Long beatmap, List<String> referees, List<String> invite) {}
    // compact constructor：mods / referees / invite 為 null 時換成 List.of()；
    // teamMode、scoreMode、size 必須三者都有或都沒有，否則拋 IllegalArgumentException("team-mode、score-mode、size 必須一起設定")
@ConfigurationProperties("bancho.match")
public record MatchPresetProperties(Map<String, MatchPreset> presets) {}   // null 時 Map.of()
// MatchRoom
public void apply(MatchPreset preset);   // 依 set → password → mods → map → addref → invite 送出，未填的欄位跳過
// MatchCommands.make 新增 @Option(longName = "preset") String preset
```

`MatchPresetProperties` 由 `MatchConfiguration` 的 `@EnableConfigurationProperties` 一併註冊。

- [ ] **Step 1：寫失敗的測試**

`MatchRoomPresetTest`：
- `appliesAllFieldsInOrder`：`new MatchPreset(0, 3, 2, "abc", List.of("HD", "HR"), 1502772L, List.of("Ref1"), List.of("Alexlin86", "Friend"))` → `sender.calls` 依序為 `!mp set 0 3 2`、`!mp password abc`、`!mp mods HD HR`、`!mp map 1502772`、`!mp addref Ref1`、`!mp invite Alexlin86`、`!mp invite Friend`（皆送到 `#mp_123`）。
- `appliesOnlyFilledFields`：`new MatchPreset(null, null, null, null, List.of("HD"), null, null, null)` → 只有 `!mp mods HD`。
- `partialSetIsRejected`：`new MatchPreset(0, null, 2, ...)` 拋 `IllegalArgumentException`。

`MatchPresetPropertiesTest`（`ApplicationContextRunner` + `MatchConfiguration` + `OsuIrcConfiguration`，帳密屬性）：屬性
```
bancho.match.presets.practice.team-mode=0
bancho.match.presets.practice.score-mode=3
bancho.match.presets.practice.size=2
bancho.match.presets.practice.mods[0]=HD
bancho.match.presets.practice.invite[0]=Alexlin86
```
→ `presets().get("practice")` 等於 `new MatchPreset(0, 3, 2, null, List.of("HD"), null, List.of(), List.of("Alexlin86"))`；`bancho.match.create-timeout=5s` 仍綁定到 `MatchProperties`。

`MatchCommandsTest` 新增：
- `makeWithPresetAppliesItAfterCreation`：`make("r", "practice")`，餵 `MatchCreated(5, "r")` → `sender.calls` 在 `join #mp_5` 之後含 `msg #mp_5 !mp set 0 3 2` 等範本指令。
- `unknownPresetRejectedBeforeMake`：`make("r", "nope")` 回傳 `找不到範本：nope`，`sender.calls` 為空。

`KnownUsersTest` 新增：範本的 invite / referees 名單出現在 `all()`。`ShellCompletionsTest` 新增：`--preset` 補全列出範本名稱。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest='MatchRoomPresetTest,MatchPresetPropertiesTest,MatchCommandsTest,KnownUsersTest,ShellCompletionsTest'` → 編譯失敗。

- [ ] **Step 3：實作**

`MatchProperties` 與 `MatchPresetProperties` 共用前綴 `bancho.match`，兩個 record 各自只綁定自己的欄位。

- [ ] **Step 4：確認測試通過並跑全部測試**

Run: `./mvnw test` → `BUILD SUCCESS`

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: 房間設定範本與 make --preset"
```

---

### Task 6: 文件與手動驗證

**Files:**
- Modify：`CLAUDE.md`、`docs/superpowers/specs/2026-10-16-match-room-design.md`（第 9 節標記已驗證項目）

- [ ] **Step 1：更新 `CLAUDE.md`**

- 「指令」：啟動方式不變；說明 shell 指令一覽可用 `help`，`quit` 結束；Tab 補全需在真正的終端機（Windows Terminal 或 IntelliJ 的 Terminal 分頁），IntelliJ 的 Run 視窗沒有補全。
- 「架構」：移除 `console`，新增 `shell`（`ShellOutput` / `printAbove`、`MatchCommands`、`ChatCommands`、`KnownUsers`、`ShellCompletions`）與 `match/preset`；`irc` 改為 `OsuIrcLifecycle`（說明 Spring Shell 以 ApplicationRunner 佔住 main thread 的原因）。
- `spring.main.keep-alive` 的說明改為 `false` 與其理由。
- 範本設定範例（YAML 或 properties 皆可，與 spec 第 6 節一致）。
- 「慣例」：依賴方向改為 `shell → match → irc`；測試不得啟動 shell runner（說明採用的方式）。

- [ ] **Step 2：更新 spec 第 9 節**

把「Spring Shell 能否執行」「是否阻擋 ApplicationReadyEvent」「能否取得 LineReader」「quit 是否正常關閉」標為已驗證並寫上結果（見本計畫開頭的表格）。

- [ ] **Step 3：完整測試與打包**

Run: `./mvnw package` → 所有測試通過、`BUILD SUCCESS`。

- [ ] **Step 4：手動驗證（交給使用者）**

在 Windows Terminal 執行 `java -jar target/bancho-0.0.1-SNAPSHOT.jar`：`make 測試房 --preset practice`（需先在設定加範本）→ 房間建立並自動套用；`invite Al<Tab>` 補全；BanchoBot 訊息印在輸入行上方、不打亂正在輸入的文字；`quit` 正常結束並送出 QUIT。

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "docs: 更新 CLAUDE.md 與 spec 的階段二結果"
```
