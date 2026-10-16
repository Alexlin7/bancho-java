# 多人房管理：階段一（核心）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把專案改成依功能分 package，加入 BanchoBot 事件解析、`MatchService` / `MatchRoom` / 房間狀態、console 的 `/make` 與 `/rooms`，以及可選的 OTel log 匯出。

**Architecture:** `irc` 套件保留現有 client，`BanchoBotListener` 作為 `IrcMessageListener` 把 BanchoBot 訊息解析成 `MatchEvent`，用 Spring `ApplicationEventPublisher` 發出；`MatchService` 以 `@EventListener` 接收、維護 `MatchRoom` 與不可變的 `MatchState`。所有事件在 IRC dispatcher thread 上同步、依序處理。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Lombok、JUnit 5、AssertJ、`spring-boot-starter-opentelemetry`、`opentelemetry-logback-appender-1.0`。

**Spec:** `docs/superpowers/specs/2026-10-16-match-room-design.md`（第 1–5、6 的階段一、7–10 節）。階段二（Spring Shell、補全、範本）另寫計畫。

## Global Constraints

- 根 package：`idv.alexlin7.tw.bancho`。依賴方向只能是 `console → match → irc`；`irc` 不得 import `match` 或 `console`，`match` 不得 import `console`。
- 只在 feature 內使用的類別一律 package-private；對外公開：`MatchService`、`MatchRoom`、`MatchState`、`MatchEvent`（含巢狀 record）、`IrcSender`、`IrcMessageListener`、`IrcMessage`。
- 一律用 Lombok `@Slf4j` 記 log，不得使用 `System.out` / `System.err`（`consoleOut` bean 例外，已有 `@SuppressWarnings("java:S106")`）。log 前綴：`[osu! IRC]`、`[match]`、`[console]`。註解與 log 訊息用繁體中文。
- SonarQube：`InterruptedException` 要重設中斷旗標；資源用 try-with-resources；log 例外時把例外物件傳給 logger；測試不得使用 `Thread.sleep`。
- 測試不得連線到真的 Bancho；連線行為用 `FakeIrcServer`。
- 新增依賴只有：`spring-boot-starter-opentelemetry`（版本由 Boot 管理）與 `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.1-alpha`（對應 Boot 4.1.1 管理的 OTel API 1.62.0；**不可**用 2.29 以上，它們需要更新的 OTel API）。
- 每個 task 結束時 `./mvnw test` 全部通過才 commit。

## Review Focus

1. **含空白的玩家名稱**（例如 `Some Player joined in slot 3.`）要整個名稱解析出來，不能切在空白處 → Task 3 的 `parsesUsernameWithSpaces`。
2. **含空白或中文的房名**（例如 `!mp make 第一場 練習`）要能正確對應回 `createMatch` 的 future → Task 6 的 `createMatchMatchesRoomNameWithSpaces`。
3. **逾時後才到的 `MatchCreated`** 不能拋例外，房間仍要登記並 join → Task 6 的 `lateMatchCreatedStillRegistersRoom`。
4. **漏掉事件後狀態不一致**：有人 `joined in slot 1.` 但 slot 1 已記錄另一位玩家時，新的玩家覆蓋舊的，舊玩家從狀態中消失 → Task 4 的 `joiningOccupiedSlotReplacesPreviousOccupant`。
5. **多間房同時存在**：A 房的事件不影響 B 房，未登記的房號會自動登記 → Task 6 的 `eventsAreIsolatedPerRoom`。

---

## File Structure

```
src/main/java/idv/alexlin7/tw/bancho/
├── BanchoApplication.java                     （不變）
├── irc/                                       Task 1：以下原檔搬入，只改 package 與 import
│   ├── AuthenticationFailedException.java
│   ├── IrcMessage.java  IrcMessageListener.java  IrcSender.java  IrcText.java
│   ├── OsuIrcClient.java  OsuIrcConfiguration.java  OsuIrcProperties.java
├── observability/
│   └── OpenTelemetryAppenderInitializer.java  Task 2
├── match/
│   ├── event/MatchEvent.java                  Task 3：sealed interface + 巢狀 record
│   ├── BanchoBotParser.java                   Task 3
│   ├── MatchState.java                        Task 4
│   ├── MatchRoom.java                         Task 5
│   ├── MatchService.java  MatchProperties.java  MatchConfiguration.java   Task 6
│   └── BanchoBotListener.java                 Task 7
└── console/                                   Task 9：ConsoleCommandInterpreter、ConsoleConfiguration 修改
src/main/resources/
├── logback-spring.xml  application-otel.properties                        Task 2
src/test/java/idv/alexlin7/tw/bancho/
├── irc/  FakeIrcServer.java（改 public）、RecordingIrcSender.java（新）、既有 irc 測試搬入   Task 1
├── observability/ObservabilityConfigurationTest.java                       Task 2
├── match/ BanchoBotParserTest、MatchStateTest、MatchRoomTest、MatchServiceTest、
│          BanchoBotListenerTest、MatchReplayIntegrationTest                Task 3–8
└── console/ConsoleCommandInterpreterTest.java（修改）                      Task 9
src/test/resources/banchobot/session-2026-10-16.irc                         Task 8
```

刪除：`OsuChatHandlerService.java`（Task 1）。

---

### Task 1: 搬到 `irc` 套件、抽出測試用 sender、移除 `OsuChatHandlerService`

**Files:**
- Move（`git mv`）：`src/main/java/idv/alexlin7/tw/bancho/{AuthenticationFailedException,IrcMessage,IrcMessageListener,IrcSender,IrcText,OsuIrcClient,OsuIrcConfiguration,OsuIrcProperties}.java` → `.../bancho/irc/`
- Move：`src/test/java/idv/alexlin7/tw/bancho/{FakeIrcServer,IrcMessageTest,IrcTextTest,OsuIrcClientTest,OsuIrcConfigurationTest}.java` → `.../bancho/irc/`
- Delete：`src/main/java/idv/alexlin7/tw/bancho/OsuChatHandlerService.java`
- Create：`src/test/java/idv/alexlin7/tw/bancho/irc/RecordingIrcSender.java`
- Modify：`console/*.java`、`console/*Test.java` 的 import；`ConsoleCommandInterpreterTest`、`ConsoleInputLoopTest` 改用 `RecordingIrcSender`

**Interfaces:**
- Produces：
  - `public class RecordingIrcSender implements IrcSender`：`public final List<String> calls`（執行緒安全，`Collections.synchronizedList`），記錄格式為 `"msg <target> <message>"`、`"join <channel>"`、`"part <channel>"`；`join`/`part` 對不以 `#` 開頭的名稱拋 `IllegalArgumentException("頻道名稱必須以 # 開頭：" + channel)`。類別不可為 final（`ConsoleInputLoopTest` 會繼承它）。
  - `FakeIrcServer` 改為 `public final class`，以下方法改為 public：建構子、`port()`、`connectionCount()`、`answerPing()`、`onConnect(...)`、`send(String)`、`welcome()`、`dropConnection()`、`awaitConnection(Duration)`、`awaitConnectionTime(Duration)`、兩個 `awaitLine(...)`。

- [ ] **Step 1：搬移檔案並修正 package / import**

用 `git mv` 搬移，把 package 改成 `idv.alexlin7.tw.bancho.irc`。`IrcText` 仍為 package-private（只有 `OsuIrcClient` 與 `IrcTextTest` 使用，兩者同 package）。

- [ ] **Step 2：刪除 `OsuChatHandlerService` 並修正 `OsuIrcConfigurationTest`**

`runner` 改為 `.withUserConfiguration(OsuIrcConfiguration.class)`；`registersEveryListenerBeanWithoutCircularDependency` 的斷言改為：

```java
assertThat(context.getBeansOfType(IrcMessageListener.class)).containsOnlyKeys("consoleMessageListener");
```

- [ ] **Step 3：建立 `RecordingIrcSender`，console 測試改用它**

刪除 `ConsoleCommandInterpreterTest` 內的巢狀 `RecordingSender`，兩個 console 測試改用 `idv.alexlin7.tw.bancho.irc.RecordingIrcSender`，斷言內容不變。

- [ ] **Step 4：確認全部測試通過**

Run: `./mvnw test`
Expected: `Tests run: 72, Failures: 0, Errors: 0` 且 `BUILD SUCCESS`（測試數與搬移前相同）。

- [ ] **Step 5：確認依賴方向**

Run: `grep -rn "import idv.alexlin7.tw.bancho.console" src/main/java/idv/alexlin7/tw/bancho/irc`
Expected: 沒有輸出。

- [ ] **Step 6：Commit**

```bash
git add -A
git commit -m "refactor: IRC 核心搬到 irc 套件，移除 OsuChatHandlerService"
```

---

### Task 2: OpenTelemetry log 匯出（預設關閉，`otel` profile 開啟）

**Files:**
- Modify：`pom.xml`
- Create：`src/main/resources/logback-spring.xml`、`src/main/resources/application-otel.properties`
- Create：`src/main/java/idv/alexlin7/tw/bancho/observability/OpenTelemetryAppenderInitializer.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/observability/ObservabilityConfigurationTest.java`、`OtelProfileTest.java`
- Modify：`src/test/resources/application.properties`

**Interfaces:**
- Produces：`@Component class OpenTelemetryAppenderInitializer implements InitializingBean`，建構子注入 `io.opentelemetry.api.OpenTelemetry`，`afterPropertiesSet()` 呼叫 `OpenTelemetryAppender.install(openTelemetry)`。

- [ ] **Step 1：寫失敗的測試**

```java
@SpringBootTest
class ObservabilityConfigurationTest {
    @Autowired ApplicationContext context;

    @Test
    void exportsNothingWithoutOtelProfile() {
        assertThat(context.getBeansOfType(io.opentelemetry.sdk.logs.export.LogRecordExporter.class)).isEmpty();
        assertThat(context.getBeansOfType(io.opentelemetry.sdk.trace.export.SpanExporter.class)).isEmpty();
        assertThat(context.getBeansOfType(io.micrometer.registry.otlp.OtlpMeterRegistry.class)).isEmpty();
    }

    @Test
    void installsOpenTelemetryAppenderOnRootLogger() {
        var root = ((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory())
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        assertThat(root.getAppender("OTEL")).isInstanceOf(
                io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender.class);
    }

}

@SpringBootTest
@ActiveProfiles("otel")
class OtelProfileTest {
    @Autowired ApplicationContext context;

    @Test
    void createsLogAndMetricExporters() {
        assertThat(context.getBeansOfType(io.opentelemetry.sdk.logs.export.LogRecordExporter.class)).isNotEmpty();
        assertThat(context.getBeansOfType(io.micrometer.registry.otlp.OtlpMeterRegistry.class)).isNotEmpty();
    }
}
```

Exporter 只是建立 bean，測試期間沒有 collector 也不會失敗。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest='ObservabilityConfigurationTest,OtelProfileTest'`
Expected: 編譯失敗（找不到 `OpenTelemetryAppender`、`LogRecordExporter`）。

- [ ] **Step 3：修改 `pom.xml`**

移除 `spring-boot-starter-micrometer-metrics`、`micrometer-registry-otlp`、`spring-boot-starter-micrometer-metrics-test`。新增 `spring-boot-starter-opentelemetry`（compile）、`spring-boot-starter-opentelemetry-test`（test，4.1.1 已確認存在於 Maven Central）、`opentelemetry-logback-appender-1.0`，版本寫在 `<properties>` 的 `<opentelemetry-logback-appender.version>2.28.1-alpha</opentelemetry-logback-appender.version>`，並加註解說明為何不能升到 2.29 以上。

- [ ] **Step 4：新增設定檔與 initializer**

- `logback-spring.xml`：`<include resource="org/springframework/boot/logging/logback/base.xml"/>`，再宣告 `<appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">`，設定 `<captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>`、`<captureExperimentalAttributes>true</captureExperimentalAttributes>`，並加到 `<root>`。
- `application-otel.properties`：

```properties
management.opentelemetry.logging.export.otlp.endpoint=http://localhost:4318/v1/logs
management.otlp.metrics.export.enabled=true
management.otlp.metrics.export.url=http://localhost:4318/v1/metrics
```

- `src/main/resources/application.properties` 與 `src/test/resources/application.properties` 都加上 `management.otlp.metrics.export.enabled=false`。兩個檔案同名，測試時 test 版會遮蔽 main 版，所以兩邊都要寫；正式環境的行為由 Task 10 的手動驗證確認。
- 建立 `OpenTelemetryAppenderInitializer`。

- [ ] **Step 5：確認測試通過**

Run: `./mvnw test -Dtest='ObservabilityConfigurationTest,OtelProfileTest'`
Expected: PASS。若 `exportsNothingWithoutOtelProfile` 的 `SpanExporter` 斷言失敗，在 main 與 test 的 `application.properties` 都加 `management.tracing.export.enabled=false`，並在 `application-otel.properties` 保持不開啟 tracing，再跑一次。

- [ ] **Step 6：全部測試並 commit**

Run: `./mvnw test` → `BUILD SUCCESS`

```bash
git add -A
git commit -m "feat: OpenTelemetry log 匯出，預設關閉，otel profile 開啟"
```

---

### Task 3: `MatchEvent` 與 `BanchoBotParser`

**Files:**
- Create：`src/main/java/idv/alexlin7/tw/bancho/match/event/MatchEvent.java`
- Create：`src/main/java/idv/alexlin7/tw/bancho/match/BanchoBotParser.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/match/BanchoBotParserTest.java`

**Interfaces:**
- Produces：

```java
public sealed interface MatchEvent {
    long matchId();
    record MatchCreated(long matchId, String name) implements MatchEvent {}
    record MatchClosed(long matchId) implements MatchEvent {}
    record PlayerInvited(long matchId, String username) implements MatchEvent {}
    record PlayerJoined(long matchId, String username, int slot) implements MatchEvent {}
    record PlayerMoved(long matchId, String username, int slot) implements MatchEvent {}
    record PlayerLeft(long matchId, String username) implements MatchEvent {}
    record SettingsChanged(long matchId, int size, String teamMode, String winCondition) implements MatchEvent {}
    record BeatmapChanged(long matchId, long beatmapId, String title) implements MatchEvent {}
    record ModsChanged(long matchId, List<String> mods, boolean freeMod) implements MatchEvent {}  // mods 以 List.copyOf 保存
    record SlotStatus(long matchId, int slot, String status, long userId, String username) implements MatchEvent {}
}
```

```java
final class BanchoBotParser {
    static final String BANCHO_BOT = "BanchoBot";
    static Result parse(IrcMessage message);
    sealed interface Result {
        record Parsed(MatchEvent event) implements Result {}
        record Ignored() implements Result {}                          // 已知但刻意不產生事件的行
        record Unrecognized(Long matchId, String text) implements Result {}  // matchId 在私訊時為 null
        record NotFromBanchoBot() implements Result {}
    }
}
```

（spec 寫的是 `Optional<MatchEvent>`；改用 `Result` 是為了區分「刻意略過」與「看不懂」，只有後者要記 INFO。）

- [ ] **Step 1：寫失敗的測試**

測資以 `":BanchoBot!cho@ppy.sh PRIVMSG <target> :<text>"` 組成 `IrcMessage`。`@ParameterizedTest @MethodSource` 的 (target, text, expected event)：

| target | text | expected |
|---|---|---|
| `test_user` | `Created the tournament match https://osu.ppy.sh/mp/122021273 測試房` | `MatchCreated(122021273, "測試房")` |
| `test_user` | `Created the tournament match https://osu.ppy.sh/mp/5 第一場 練習` | `MatchCreated(5, "第一場 練習")` |
| `#mp_122021273` | `Closed the match` | `MatchClosed(122021273)` |
| `#mp_122021273` | `Invited Alexlin86 to the room` | `PlayerInvited(122021273, "Alexlin86")` |
| `#mp_122021273` | `Alexlin86 joined in slot 1.` | `PlayerJoined(122021273, "Alexlin86", 1)` |
| `#mp_122021273` | `Alexlin86 moved to slot 2` | `PlayerMoved(122021273, "Alexlin86", 2)` |
| `#mp_122021273` | `Alexlin86 left the game.` | `PlayerLeft(122021273, "Alexlin86")` |
| `#mp_122021273` | `Changed match settings to 2 slots, HeadToHead, ScoreV2` | `SettingsChanged(122021273, 2, "HeadToHead", "ScoreV2")` |
| `#mp_122021273` | `Changed beatmap to https://osu.ppy.sh/b/1502772 Asriel - Kegare Naki Yume` | `BeatmapChanged(122021273, 1502772, "Asriel - Kegare Naki Yume")` |
| `#mp_122021273` | `Beatmap: https://osu.ppy.sh/b/1502772 Asriel - Kegare Naki Yume` | 同上 |
| `#mp_122021273` | `Enabled Hidden, HardRock, disabled FreeMod` | `ModsChanged(122021273, List.of("Hidden", "HardRock"), false)` |
| `#mp_122021273` | `Slot 1  Not Ready https://osu.ppy.sh/u/4841753 Alexlin86       ` | `SlotStatus(122021273, 1, "Not Ready", 4841753, "Alexlin86")` |

其他測試：
- `parsesUsernameWithSpaces`：`Some Player joined in slot 3.` → `PlayerJoined(1, "Some Player", 3)`；`Some Player left the game.` → `PlayerLeft(1, "Some Player")`。
- `ignoresKnownSettingsLines`：`Room name: 測試房, History: https://osu.ppy.sh/mp/122021273`、`Team mode: HeadToHead, Win condition: ScoreV2`、`Players: 1` → `Ignored`。
- `reportsUnrecognizedText`：`#mp_7` 的 `Something new happened` → `Unrecognized(7L, "Something new happened")`；私訊的 `You cannot create more than 4 rooms` → `Unrecognized(null, ...)`。
- `ignoresMessagesNotFromBanchoBot`：`:Alexlin86!cho@ppy.sh PRIVMSG #mp_1 :Closed the match` → `NotFromBanchoBot`。
- `channelMessagesOutsideMpChannelsAreUnrecognized`：`#osu` 的 `Closed the match` → `Unrecognized(null, "Closed the match")`。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=BanchoBotParserTest`
Expected: 編譯失敗（`MatchEvent`、`BanchoBotParser` 不存在）。

- [ ] **Step 3：實作**

規則清單為 `List<Rule>`，每條是 `(Pattern, Function<Matcher, MatchEvent>)`，用 `matcher(text).matches()` 比對整行，text 先 `stripTrailing()`。私訊只套用 `Created the tournament match https://osu\.ppy\.sh/mp/(\d+) (.+)`。頻道房號由 `#mp_(\d+)` 取得。名稱類欄位用 `(.+?)` 並以後面的固定文字收尾（例如 `(.+?) joined in slot (\d+)\.`）。slot 行：`Slot (\d+)\s+(.+?)\s+https://osu\.ppy\.sh/u/(\d+) (.+)`。mods 行：`Enabled (.+), disabled FreeMod` → `freeMod=false`，`Enabled (.+), enabled FreeMod` → `freeMod=true`，mods 以 `", "` 分割。

- [ ] **Step 4：確認測試通過**

Run: `./mvnw test -Dtest=BanchoBotParserTest`
Expected: PASS

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: BanchoBot 訊息解析成 MatchEvent"
```

---

### Task 4: `MatchState` 與事件套用

**Files:**
- Create：`src/main/java/idv/alexlin7/tw/bancho/match/MatchState.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/match/MatchStateTest.java`

**Interfaces:**
- Consumes：Task 3 的 `MatchEvent`。
- Produces：

```java
public record MatchState(String name, boolean closed, Settings settings, Beatmap beatmap,
                         ModSelection mods, Map<Integer, Slot> slots) {
    public record Settings(Integer size, String teamMode, String winCondition) {}   // 未知時欄位為 null
    public record Beatmap(long id, String title) {}
    public record ModSelection(List<String> mods, boolean freeMod) {}
    public record Slot(String username, Long userId, String status) {}               // userId、status 可為 null
    public static MatchState initial(String name);   // name 可為 null；settings 全 null、beatmap null、mods = (List.of(), false)、slots 空
    MatchState apply(MatchEvent event);              // 純函式，回傳新實例
}
```

`slots` 在 compact constructor 以 `Map.copyOf` 保存；`MatchCreated` 且目前 `name` 為 null 時補上名稱。

- [ ] **Step 1：寫失敗的測試**

- `playerJoinedOccupiesSlot`：`initial("r").apply(PlayerJoined(1, "A", 1)).slots()` 等於 `Map.of(1, new Slot("A", null, null))`。
- `playerMovedLeavesPreviousSlot`：A 進 slot 1 再移到 3 → `Map.of(3, Slot("A", null, null))`。
- `playerLeftRemovesPlayer`：A 進 slot 1 再離開 → slots 為空。
- `joiningOccupiedSlotReplacesPreviousOccupant`：A 進 slot 1，再 B 進 slot 1 → `Map.of(1, Slot("B", null, null))`。
- `slotStatusOverwritesSlotWithDetails`：A 進 slot 2，再 `SlotStatus(1, 1, "Not Ready", 4841753, "A")` → `Map.of(1, Slot("A", 4841753L, "Not Ready"))`（同名玩家從 slot 2 移除）。
- `settingsBeatmapAndModsAreRecorded`：依序套用 `SettingsChanged(1, 2, "HeadToHead", "ScoreV2")`、`BeatmapChanged(1, 1502772, "Asriel - Kegare Naki Yume")`、`ModsChanged(1, List.of("Hidden", "HardRock"), false)` → 對應欄位相符。
- `matchClosedMarksClosed`：`closed()` 為 true，其他欄位不變。
- `invitedDoesNotChangeSlots`：`PlayerInvited` 後 `slots()` 為空。
- `createdFillsUnknownName`：`initial(null).apply(MatchCreated(1, "測試房")).name()` 為 `"測試房"`。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=MatchStateTest` → 編譯失敗。

- [ ] **Step 3：實作 `MatchState`**

`apply` 用 `switch (event)` 對 sealed interface 做 pattern matching，涵蓋所有事件型別，不使用 `default`（新增事件時編譯器會提醒）。

- [ ] **Step 4：確認測試通過**

Run: `./mvnw test -Dtest=MatchStateTest` → PASS

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: MatchState 依事件更新房間狀態"
```

---

### Task 5: `MatchRoom` 指令

**Files:**
- Create：`src/main/java/idv/alexlin7/tw/bancho/match/MatchRoom.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/match/MatchRoomTest.java`

**Interfaces:**
- Consumes：`IrcSender`、`MatchState`、`MatchEvent`、Task 1 的 `RecordingIrcSender`（測試）。
- Produces：

```java
public final class MatchRoom {
    MatchRoom(long matchId, String name, IrcSender sender);   // package-private，只有 MatchService 建立
    public long matchId();
    public String channel();                                   // "#mp_" + matchId
    public MatchState state();                                 // volatile 欄位
    void apply(MatchEvent event);                              // package-private；state = state.apply(event)
    public void invite(String username);
    public void set(int teamMode, int scoreMode, int size);
    public void map(long beatmapId);
    public void mods(String... mods);
    public void start();
    public void start(Duration delay);
    public void timer(Duration duration);
    public void abort();
    public void close();
    public void password(String password);
    public void addRef(String... usernames);
    public void settings();
    public void say(String text);
}
```

- [ ] **Step 1：寫失敗的測試**

用 `RecordingIrcSender` 與 `new MatchRoom(123, "r", sender)`，斷言 `sender.calls`：

| 呼叫 | 預期 |
|---|---|
| `invite("Alexlin86")` | `msg #mp_123 !mp invite Alexlin86` |
| `set(0, 3, 2)` | `msg #mp_123 !mp set 0 3 2` |
| `map(1502772)` | `msg #mp_123 !mp map 1502772` |
| `mods("HD", "HR")` | `msg #mp_123 !mp mods HD HR` |
| `start()` | `msg #mp_123 !mp start` |
| `start(Duration.ofSeconds(10))` | `msg #mp_123 !mp start 10` |
| `timer(Duration.ofSeconds(30))` | `msg #mp_123 !mp timer 30` |
| `abort()` / `close()` / `settings()` | `!mp abort` / `!mp close` / `!mp settings` |
| `password("abc")` | `msg #mp_123 !mp password abc` |
| `password(null)` 與 `password(" ")` | `msg #mp_123 !mp password` |
| `addRef("A", "B")` | `msg #mp_123 !mp addref A B` |
| `say("gl hf")` | `msg #mp_123 gl hf` |

驗證（皆拋 `IllegalArgumentException` 且 `calls` 為空）：`set(4, 0, 2)`、`set(0, -1, 2)`、`set(0, 0, 0)`、`set(0, 0, 17)`、`mods("HD HR")`、`mods()`、`invite(" ")`、`addRef()`、`start(Duration.ofSeconds(-1))`。

關閉後：`room.apply(new MatchClosed(123))` 後呼叫 `invite("A")` 拋 `IllegalStateException`，`calls` 為空。

狀態：`room.apply(new PlayerJoined(123, "A", 1))` 後 `room.state().slots()` 含 slot 1。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=MatchRoomTest` → 編譯失敗。

- [ ] **Step 3：實作 `MatchRoom`**

所有指令經過同一個 `private void command(String text)`：先檢查 `state.closed()`，再 `sender.sendMessage(channel(), text)`。

- [ ] **Step 4：確認測試通過**

Run: `./mvnw test -Dtest=MatchRoomTest` → PASS

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: MatchRoom 封裝 !mp 指令"
```

---

### Task 6: `MatchService`

**Files:**
- Create：`src/main/java/idv/alexlin7/tw/bancho/match/MatchService.java`、`MatchProperties.java`、`MatchConfiguration.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/match/MatchServiceTest.java`

**Interfaces:**
- Consumes：`IrcSender`、`MatchRoom`（package-private 建構子與 `apply`）、`MatchEvent`。
- Produces：

```java
@ConfigurationProperties("bancho.match")
public record MatchProperties(Duration createTimeout) {}       // compact constructor：null 時改為 Duration.ofSeconds(15)

public class MatchService {
    public MatchService(IrcSender sender, Duration createTimeout);
    public CompletableFuture<MatchRoom> createMatch(String name);
    public MatchRoom attach(long matchId);
    public Optional<MatchRoom> room(long matchId);               // 已關閉的房間也查得到
    public List<MatchRoom> openRooms();                          // 依 matchId 排序，不含已關閉
    @EventListener @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onMatchEvent(MatchEvent event);
}
```

`MatchConfiguration`：`@Configuration @EnableConfigurationProperties(MatchProperties.class)`，`@Bean MatchService matchService(IrcSender sender, MatchProperties properties)`。`IrcSender` 由 `OsuIrcClient` bean 提供；`MatchService` 不是 `IrcMessageListener`，不會形成循環依賴。

- [ ] **Step 1：寫失敗的測試**

`MatchServiceTest` 用 `RecordingIrcSender` 與 `new MatchService(sender, Duration.ofMillis(200))`：

- `createMatchSendsMakeCommandAndCompletesOnCreated`：`createMatch("測試房")` → `calls` 含 `msg BanchoBot !mp make 測試房`、future 未完成；`onMatchEvent(new MatchCreated(122021273, "測試房"))` → future 完成，`room.matchId()` 為 122021273、`calls` 含 `join #mp_122021273`、`openRooms()` 含該房。
- `createMatchMatchesRoomNameWithSpaces`：`createMatch("第一場 練習")`，餵 `MatchCreated(5, "第一場 練習")` → future 完成且房號為 5。
- `rejectsDuplicatePendingName`：連續兩次 `createMatch("r")`，第二次拋 `IllegalStateException`，`calls` 只有一筆 `!mp make`。
- `rejectsBlankOrMultilineName`：`createMatch(" ")`、`createMatch("a\nb")` 拋 `IllegalArgumentException`。
- `createMatchTimesOut`：`createMatch("r")` 的 future 以 `assertThat(future).failsWithin(Duration.ofSeconds(2)).withThrowableOfType(ExecutionException.class).havingCause().isInstanceOf(TimeoutException.class)`；之後可以再次 `createMatch("r")`（pending 已清除）。
- `lateMatchCreatedStillRegistersRoom`：等 timeout 失敗後才餵 `MatchCreated(9, "r")` → 不拋例外、`room(9)` 存在、`calls` 含 `join #mp_9`。
- `manualMatchCreatedIsTrackedAndJoined`：未呼叫 `createMatch`，直接餵 `MatchCreated(7, "手動")` → `room(7)` 的 `state().name()` 為 `"手動"`、`calls` 含 `join #mp_7`。
- `eventsAreIsolatedPerRoom`：餵 `MatchCreated(1, "a")`、`PlayerJoined(1, "A", 1)`、`PlayerJoined(2, "B", 1)` → `room(1)` slots 只有 A；`room(2)` 自動登記且 `state().name()` 為 null、slots 只有 B；`calls` 不含 `join #mp_2`（自動登記不 join，因為收到事件代表已在頻道內）。
- `attachJoinsAndTracksRoom`：`attach(42)` → `calls` 含 `join #mp_42`、`openRooms()` 含 42；再次 `attach(42)` 回傳同一個實例。
- `matchClosedRemovesFromOpenRoomsAndParts`：建立房間 1 後餵 `MatchClosed(1)` → `openRooms()` 為空、`room(1).get().state().closed()` 為 true、`calls` 含 `part #mp_1`。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=MatchServiceTest` → 編譯失敗。

- [ ] **Step 3：實作**

`rooms` 與 `pending` 都用 `ConcurrentHashMap`（`createMatch` 由前端 thread 呼叫，事件由 dispatcher thread 處理）。逾時用 `future.orTimeout(createTimeout, MILLISECONDS)`，並以 `whenComplete` 從 `pending` 以 `remove(name, future)` 移除。房名檢查：空白或含 `\r`、`\n` 拋 `IllegalArgumentException`。

- [ ] **Step 4：確認測試通過**

Run: `./mvnw test -Dtest=MatchServiceTest` → PASS

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: MatchService 開房、追蹤房間"
```

---

### Task 7: `BanchoBotListener`

**Files:**
- Create：`src/main/java/idv/alexlin7/tw/bancho/match/BanchoBotListener.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/match/BanchoBotListenerTest.java`

**Interfaces:**
- Consumes：`BanchoBotParser.parse`、`ApplicationEventPublisher`。
- Produces：`@Component class BanchoBotListener implements IrcMessageListener`，建構子 `BanchoBotListener(ApplicationEventPublisher publisher)`。

- [ ] **Step 1：寫失敗的測試（`@ExtendWith(OutputCaptureExtension.class)`）**

以 lambda 實作的 `ApplicationEventPublisher` 收集事件：

- `publishesParsedEvents`：`onMessage(":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Alexlin86 joined in slot 1.")` → 收到 `PlayerJoined(1, "Alexlin86", 1)`；output 含 `[match]` 與 `PlayerJoined`。
- `logsUnrecognizedBanchoBotText`：`#mp_7` 的 `Something new happened` → 未發佈事件；output 含 `未辨識的 BanchoBot 訊息` 與 `Something new happened`。
- `staysSilentForIgnoredLines`：`Players: 1` → 未發佈事件；output 不含 `未辨識`。
- `ignoresOtherUsers`：`:Alexlin86!cho@ppy.sh PRIVMSG #mp_1 :Closed the match` → 未發佈事件、output 不含 `未辨識`。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=BanchoBotListenerTest` → 編譯失敗。

- [ ] **Step 3：實作**

`switch (BanchoBotParser.parse(message))`：
- `Parsed`：先 `log.atInfo().addKeyValue("match.id", ...).addKeyValue("match.event", event.getClass().getSimpleName()).log("[match] {}", event)`，再 `publisher.publishEvent(event)`。
- `Unrecognized`：`log.atInfo().addKeyValue("match.id", matchId).addKeyValue("banchobot.text", text).log("[match] 未辨識的 BanchoBot 訊息：{}", text)`。
- `Ignored`、`NotFromBanchoBot`：不做事。

- [ ] **Step 4：確認測試通過**

Run: `./mvnw test -Dtest=BanchoBotListenerTest` → PASS

- [ ] **Step 5：修正 Task 1 的 listener 斷言並跑全部測試**

`OsuIrcConfigurationTest.registersEveryListenerBeanWithoutCircularDependency` 只載入 `OsuIrcConfiguration` 與 `ConsoleConfiguration`，`BanchoBotListener` 不在其中，斷言維持 `consoleMessageListener`。

Run: `./mvnw test` → `BUILD SUCCESS`

- [ ] **Step 6：Commit**

```bash
git add -A
git commit -m "feat: BanchoBotListener 發佈 MatchEvent"
```

---

### Task 8: 真實樣本回放整合測試

**Files:**
- Create：`src/test/resources/banchobot/session-2026-10-16.irc`
- Test：`src/test/java/idv/alexlin7/tw/bancho/match/MatchReplayIntegrationTest.java`

**Interfaces:**
- Consumes：`FakeIrcServer`、`OsuIrcConfiguration`、`MatchConfiguration`、`BanchoBotListener`、`MatchService`。

- [ ] **Step 1：建立樣本檔**

每行一則 Bancho 送出的原始 IRC 訊息，`#` 開頭的行是註解。內容依使用者 2026-10-16 的兩次實測，前綴一律為 `:BanchoBot!cho@ppy.sh PRIVMSG`，私訊對象為 `test_user`：

```
# 第一次：開房後關房
:BanchoBot!cho@ppy.sh PRIVMSG test_user :Created the tournament match https://osu.ppy.sh/mp/122021237 測試房
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021237 :Closed the match
# 第二次
:BanchoBot!cho@ppy.sh PRIVMSG test_user :Created the tournament match https://osu.ppy.sh/mp/122021273 測試房
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Invited Alexlin86 to the room
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Alexlin86 joined in slot 1.
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Alexlin86 moved to slot 2
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Alexlin86 moved to slot 3
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Alexlin86 left the game.
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Changed match settings to 2 slots, HeadToHead, ScoreV2
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Alexlin86 joined in slot 1.
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Changed beatmap to https://osu.ppy.sh/b/5884441 Keri Hilson - Pretty Girl Rock
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Changed beatmap to https://osu.ppy.sh/b/1502772 Asriel - Kegare Naki Yume
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Room name: 測試房, History: https://osu.ppy.sh/mp/122021273
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Beatmap: https://osu.ppy.sh/b/1502772 Asriel - Kegare Naki Yume
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Team mode: HeadToHead, Win condition: ScoreV2
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Players: 1
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Slot 1  Not Ready https://osu.ppy.sh/u/4841753 Alexlin86       
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Enabled Hidden, HardRock, disabled FreeMod
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122021273 :Closed the match
```

（`Slot 1` 那行結尾的空白要保留，`.editorconfig` 的 `trim_trailing_whitespace` 可能會刪掉；必要時在 `.editorconfig` 加 `[*.irc] trim_trailing_whitespace = false`。）

- [ ] **Step 2：寫測試（`@ExtendWith(OutputCaptureExtension.class)`）**

`ApplicationContextRunner` 載入 `OsuIrcConfiguration`、`MatchConfiguration`、`BanchoBotListener`，屬性：`osu.irc.host=127.0.0.1`、`osu.irc.port=<fake port>`、`osu.irc.username=test_user`、`osu.irc.password=pw`、`osu.irc.timing.send-interval=0ms`。在 context 內取得 `OsuIrcClient` 呼叫 `start()`，`server.awaitConnection` 後 `server.welcome()`，逐行 `server.send(...)`，最後一行送出後用 `server.awaitLine("PART #mp_122021273", Duration.ofSeconds(3))` 等待處理完成。

斷言：
- `server` 依序收到 `JOIN #mp_122021237`、`PART #mp_122021237`、`JOIN #mp_122021273`、`PART #mp_122021273`（以 `awaitLine` 依序檢查）。
- `matchService.openRooms()` 為空。
- `room(122021237).state().closed()` 為 true。
- `room(122021273).state()`：`name` 為 `"測試房"`、`closed` 為 true、`settings` 為 `new Settings(2, "HeadToHead", "ScoreV2")`、`beatmap` 為 `new Beatmap(1502772, "Asriel - Kegare Naki Yume")`、`mods` 為 `new ModSelection(List.of("Hidden", "HardRock"), false)`、`slots` 為 `Map.of(1, new Slot("Alexlin86", 4841753L, "Not Ready"))`。
- output 不含 `未辨識的 BanchoBot 訊息`。

- [ ] **Step 3：執行測試**

Run: `./mvnw test -Dtest=MatchReplayIntegrationTest`
Expected: PASS。若失敗，依失敗訊息回頭修正 Task 3–7 的實作，不得修改樣本檔內容。

- [ ] **Step 4：Commit**

```bash
git add -A
git commit -m "test: 以真實 BanchoBot 樣本回放驗證房間狀態"
```

---

### Task 9: console 的 `/make` 與 `/rooms`

**Files:**
- Modify：`src/main/java/idv/alexlin7/tw/bancho/console/ConsoleCommandInterpreter.java`、`ConsoleConfiguration.java`
- Test：`src/test/java/idv/alexlin7/tw/bancho/console/ConsoleCommandInterpreterTest.java`、`ConsoleInputLoopTest.java`

**Interfaces:**
- Consumes：`MatchService.createMatch`、`MatchService.openRooms`、`MatchRoom.state()`。
- Produces：建構子改為 `ConsoleCommandInterpreter(IrcSender sender, MatchService matchService, PrintStream out)`；`currentTarget` 改為 `volatile`。`ConsoleConfiguration.consoleInputLoop` 多注入 `MatchService`。

- [ ] **Step 1：寫失敗的測試**

`ConsoleCommandInterpreterTest` 的 `setUp` 建立 `new MatchService(sender, Duration.ofMillis(200))` 傳入：

- `makeCreatesRoomAndSwitchesTarget`：`execute("/make 測試房")` → `sender.calls` 含 `msg BanchoBot !mp make 測試房`；`matchService.onMatchEvent(new MatchCreated(122021273, "測試房"))` → output 含 `房間已建立：#mp_122021273 測試房`、`currentTarget()` 為 `#mp_122021273`。
- `makeReportsTimeout`：`execute("/make r")`，以 `interpreter.lastMake().handle((r, e) -> null).get(2, SECONDS)` 等待 → output 含 `開房逾時：r`。
- `makeWithoutNamePrintsUsage`：`execute("/make")` → output 含 `用法：/make <房名>`。
- `roomsListsOpenRooms`：餵 `MatchCreated(1, "a")`、`PlayerJoined(1, "Alexlin86", 1)`、`BeatmapChanged(1, 1502772, "Asriel - Kegare Naki Yume")`、`ModsChanged(1, List.of("Hidden"), false)` 後 `execute("/rooms")` → output 含 `#mp_1 a`、`map 1502772 Asriel - Kegare Naki Yume`、`mods Hidden`、`[1] Alexlin86`。
- `roomsWhenNoneOpen`：output 含 `目前沒有開著的房間`。
- `/help` 輸出含 `/make <房名>` 與 `/rooms`。

`/make` 把 `createMatch(...).whenComplete(...)` 回傳的 future 存在 `volatile CompletableFuture<?> lastMake`，以 package-private 的 `CompletableFuture<?> lastMake()` 提供給測試等待，測試不需要 `Thread.sleep`。

- [ ] **Step 2：確認測試失敗**

Run: `./mvnw test -Dtest=ConsoleCommandInterpreterTest` → 編譯失敗。

- [ ] **Step 3：實作**

- `/make <房名>`：`createMatch(name).whenComplete(...)`。成功印 `房間已建立：<channel> <房名>` 並設定 `currentTarget`；失敗時若 cause 為 `TimeoutException` 印 `開房逾時：<房名>`，其他例外印 `開房失敗：<房名>（<訊息>）`。`createMatch` 直接拋出的 `IllegalArgumentException` / `IllegalStateException` 由既有的 `錯誤：` 處理。
- `/rooms` 每間房輸出：

```
#mp_<id> <name 或 (未知)>  size <size 或 ?>  map <id> <title 或 (未設定)>  mods <以空白分隔，或 (無)>
  [<slot>] <username>
```

- `HELP` 新增兩行：`/make <房名>          開房，成功後切換到房間頻道` 與 `/rooms                 列出開著的房間`。

- [ ] **Step 4：確認測試通過並跑全部測試**

Run: `./mvnw test` → `BUILD SUCCESS`

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: console 新增 /make 與 /rooms"
```

---

### Task 10: 文件更新與手動驗證

**Files:**
- Modify：`CLAUDE.md`

- [ ] **Step 1：更新 `CLAUDE.md`**

- 「架構」改為依 package 說明：`irc`（原內容）、`match`（parser → `BanchoBotListener` → `ApplicationEventPublisher` → `MatchService` `@EventListener`；`MatchState` 不可變、volatile；`MatchRoom` 指令）、`observability`、`console`（新增 `/make`、`/rooms`）。
- 刪除 `OsuChatHandlerService` 的說明。
- 「指令」加上 `--spring.profiles.active=otel` 的用法與 `localhost:4318`。
- 「慣例」加上：依賴方向 `console → match → irc`；BanchoBot 樣本放在 `src/test/resources/banchobot/`，新增解析規則時要先加樣本與測試；`opentelemetry-logback-appender-1.0` 版本須與 Boot 管理的 OTel API 對應。

- [ ] **Step 2：完整測試與打包**

Run: `./mvnw package`
Expected: 所有測試通過、`BUILD SUCCESS`。

- [ ] **Step 3：手動驗證（交給使用者）**

在真的終端機執行：

```powershell
java -jar target/bancho-0.0.1-SNAPSHOT.jar --spring.profiles.active=otel
```

另外不帶 profile 再啟動一次，確認沒有出現 OTLP 連線錯誤。帶 profile 時輸入 `/make 測試房` → 出現 `房間已建立`；`!mp settings` → `/rooms` 顯示 slot 與圖；`!mp close` → `/rooms` 顯示沒有房間；collector 中能查到帶 `match.id` attribute 的 log。

- [ ] **Step 4：Commit**

```bash
git add -A
git commit -m "docs: 更新 CLAUDE.md 的架構與慣例"
```
