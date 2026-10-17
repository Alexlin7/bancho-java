# 第二次 shell 實測回饋 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正必填參數的錯誤訊息、密碼改成遮蔽提示輸入、解析新的 BanchoBot 樣本、擋下連寫的 mod、授權 JLine 的 native access。

**Architecture:** shell 層新增 `SecretPrompt` 介面（JLine 實作 + 非互動時丟例外），`password` 改用它；match 層新增四種事件與 `MatchState.gameMode`，`MatchRoom` 的 mod 檢查擋連寫；建置設定加 native access。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring Shell 4.0.3（JLine 3.30）、JUnit 5、AssertJ、Awaitility。

**Spec:** `docs/superpowers/specs/2026-10-17-shell-feedback-design.md`

## Global Constraints

- 依賴方向 `shell → match → irc`；`match.preset` 不得 import `match`。
- 程式註解、log、使用者看到的文字一律繁體中文；錯誤訊息以 `錯誤：` 開頭（經 `RoomResolver.guarded` 的例外會自動加上）。
- 測試不連真的 Bancho；新的 BanchoBot 規則必須先有樣本與測試，每條 regex 比對整行。
- 每個 task 結束時 `./mvnw test` 全綠。
- commit 訊息結尾加：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01PHuQdL4CJUKhWCpEvqaMTR
  ```

## Review Focus

1. 非互動模式（測試 context、`SPRING_SHELL_INTERACTIVE_ENABLED=false`）下 `password` 不可卡住等輸入，要回 `錯誤：非互動模式無法輸入密碼`（Task 4 `ShellCommandParsingTest`）。
2. 遮蔽輸入的密碼不可進入 JLine 歷史（Task 4 `JLineSecretPromptTest.secretIsNotAddedToHistory`）。
3. `password` 在沒有目前房間時不可先跳出密碼提示才說找不到房間——先解析房間再提示（Task 4 `passwordWithoutRoomDoesNotPrompt`）。
4. `set 0 3`（少一個）與 `set`（全少）都要回用法，且經真正 parser 時不再出現 `primitive type`（Task 1 `ShellCommandParsingTest`）。
5. 合法的長名稱 `Freemod`／`freemod` 不可被連寫檢查擋下（Task 3）。

---

### Task 1: 必填參數改成可為 null 並回用法

**Files:**
- Modify: `src/main/java/idv/alexlin7/tw/bancho/shell/MatchCommands.java`（`attach`、`use`、`map`、`set`）
- Test: `src/test/java/idv/alexlin7/tw/bancho/shell/MatchCommandsTest.java`、`ShellCommandParsingTest.java`

**Interfaces:**
- Produces: `attach(Long, CommandContext)`、`use(Long, CommandContext)`、`map(Long, String, Long, CommandContext)`、`set(Integer, Integer, Integer, Long, CommandContext)`。既有測試以 int 字面值呼叫 `map`／`attach` 的地方要改成 `L` 結尾（`int` 不能自動裝箱成 `Long`）。

- [ ] **Step 1: 寫失敗的測試**

`MatchCommandsTest`：
```java
@Test
void missingRequiredArgumentsShowUsage() {
    matchService.attach(42);
    sender.calls.clear();
    String setUsage = "錯誤：用法：set <team mode> <score mode> <人數> [--room <房號>]";
    assertThat(commands.set(null, null, null, 42L, null)).isEqualTo(setUsage);
    assertThat(commands.set(2, null, null, 42L, null)).isEqualTo(setUsage);
    assertThat(commands.set(0, 3, null, 42L, null)).isEqualTo(setUsage);
    assertThat(commands.map(null, null, 42L, null)).isEqualTo("錯誤：用法：map <beatmap id> [模式] [--room <房號>]");
    assertThat(commands.attach(null, null)).isEqualTo("錯誤：用法：attach <房號>");
    assertThat(commands.use(null, null)).isEqualTo("錯誤：用法：use <房號>");
    assertThat(sender.calls).isEmpty();
}
```
`ShellCommandParsingTest`（重現實測的錯誤）：
```java
@Test
void missingSetArgumentsShowUsageInsteadOfPrimitiveError() throws Exception {
    for (String command : List.of("set", "set 2", "set --room 42 2", "set 0 3 --room 42")) {
        assertThat(run(command)).contains("錯誤：用法：set").doesNotContain("primitive");
    }
    assertThat(run("map --room 42")).contains("錯誤：用法：map");
    assertThat(sender.calls).isEmpty();
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw test -Dtest=MatchCommandsTest,ShellCommandParsingTest`
Expected: 編譯失敗（`null` 不能傳給 `int`/`long`）。

- [ ] **Step 3: 實作**

四個指令的位置參數改成 `Integer`／`Long`。`InputGuard.check` 之後、找房間之前，任一必填值為 null 就回上面測試中的用法字串（用法常數放在 `MatchCommands`）。`set`、`map` 仍透過 `guardedOnRoom`，null 檢查放在 `InputGuard.check` 與 `rooms.onRoom` 之間（例如在 `guardedOnRoom` 多收一個 `String usage, Object... required` 參數，或在指令內先 check 再判斷）。

- [ ] **Step 4: 實測非數字的錯誤訊息**

在 `ShellCommandParsingTest` 暫時加 `System.err.println(run("set a b c --room 42"))` 跑一次，看 Spring Shell 的訊息。
- 如果看得懂（例如有提到轉換失敗與值），刪掉暫時的程式碼，在 ledger 記下原文。
- 如果看不懂，把四個指令的數字參數改收 `String`，自行 `Integer.parseInt`／`Long.parseLong`，失敗時回同一組用法字串，並加測試 `assertThat(run("set a b c --room 42")).contains("錯誤：用法：set")`。

- [ ] **Step 5: 跑全部測試**

Run: `./mvnw test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/idv/alexlin7/tw/bancho/shell/MatchCommands.java src/test/java/idv/alexlin7/tw/bancho/shell/
git commit -m "fix: 少給必填參數時回用法，不再出現 primitive 的錯誤"
```

---

### Task 2: 新的 BanchoBot 樣本與事件

**Files:**
- Create: `src/test/resources/banchobot/session-2026-10-17b.irc`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/event/MatchEvent.java`、`match/BanchoBotParser.java`、`match/MatchState.java`、`shell/MatchCommands.java`（`describe`）
- Test: `BanchoBotParserTest`、`MatchStateTest`、`MatchReplayIntegrationTest`、`MatchCommandsTest`

**Interfaces:**
- Produces（`MatchEvent` 的巢狀 record）：
  - `GameModeChanged(long matchId, String mode)`：`Changed match mode to {mode}`
  - `PlayerFinished(long matchId, String username, long score, String result)`：`{username} finished playing (Score: {score}, {result}).`
  - `MatchFinished(long matchId)`：`The match has finished!`
  - `AllPlayersReady(long matchId)`：`All players are ready`
- Produces：`MatchState` 新增最後一個元件 `String gameMode`（`initial` 時為 null）；`GameModeChanged` 時更新，其餘三個新事件不改狀態。

- [ ] **Step 1: 建立樣本檔**

`src/test/resources/banchobot/session-2026-10-17b.irc`（UTF-8、LF，無行尾空白）：
```
# 使用者 2026-10-17 以 Spring Shell 實測的 BanchoBot 原文（第二次），依原順序；私訊對象統一為 test_user。
# 取自終端機紀錄，沒有保留行尾空白；這些訊息不像 slot 行有補空白。
:BanchoBot!cho@ppy.sh PRIVMSG test_user :Created the tournament match https://osu.ppy.sh/mp/122022753 test123房間
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Changed the match password
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Invited Alexlin86 to the room
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Alexlin86 joined in slot 1.
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Changed beatmap to https://osu.ppy.sh/b/2453358 Minato Aqua - Shiroi Yuki no Princess wa
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Changed match mode to CatchTheBeat
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Removed the match password
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :BMOBMO joined in slot 2.
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :All players are ready
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :The match has started!
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Started the match
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Alexlin86 finished playing (Score: 9245186, PASSED).
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :BMOBMO finished playing (Score: 3896754, PASSED).
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :The match has finished!
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Changed beatmap to https://osu.ppy.sh/b/4199355 Sena Airi (CV: Gogyou Nazuna) - Symphonic Love ReArrange
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Changed match mode to CatchTheBeat
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Disabled all mods, disabled FreeMod
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Enabled NoFail, Hidden, disabled FreeMod
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :BMOBMO left the game.
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :All players are ready
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :The match has started!
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Started the match
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Alexlin86 finished playing (Score: 12045531, PASSED).
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :The match has finished!
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Changed the match password
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :The match has started!
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Started the match
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Aborted the match
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022753 :Changed match settings to 6 slots, TagTeamVs, ScoreV2
```

- [ ] **Step 2: 寫失敗的測試**

- `BanchoBotParserTest.realSamples` 加：
  - `"Changed match mode to CatchTheBeat"` → `new GameModeChanged(ROOM_ID, "CatchTheBeat")`
  - `"Alexlin86 finished playing (Score: 9245186, PASSED)."` → `new PlayerFinished(ROOM_ID, "Alexlin86", 9245186, "PASSED")`
  - `"The match has finished!"` → `new MatchFinished(ROOM_ID)`
  - `"All players are ready"` → `new AllPlayersReady(ROOM_ID)`
- `parsesUsernameWithSpaces` 加：`"Some Player finished playing (Score: 0, FAILED)."` → `new PlayerFinished(1, "Some Player", 0, "FAILED")`
- `ignoresKnownSettingsLines` 加 `"Removed the match password"`。
- `MatchStateTest`：
  - `gameModeChangedUpdatesMode`：`initial.apply(new GameModeChanged(1, "CatchTheBeat")).gameMode()` 為 `"CatchTheBeat"`，`initial.gameMode()` 為 null。
  - `finishAndReadyEventsKeepState`：`initial` 套用 `PlayerFinished`、`MatchFinished`、`AllPlayersReady` 後仍 `isEqualTo(initial)`。
- `MatchReplayIntegrationTest.replaysThirdSessionIntoRoomState`：用 `replay("/banchobot/session-2026-10-17b.irc", "JOIN #mp_122022753", …)`。先 `await` 到 collector 含 `new SettingsChanged(122022753, 6, "TagTeamVs", "ScoreV2")`，再斷言：
  - `state.name()` 為 `"test123房間"`，`closed()` 為 false
  - `state.beatmap()` 為 `new Beatmap(4199355, "Sena Airi (CV: Gogyou Nazuna) - Symphonic Love ReArrange")`
  - `state.mods()` 為 `new ModSelection(List.of("NoFail", "Hidden"), false)`
  - `state.gameMode()` 為 `"CatchTheBeat"`，`state.settings()` 為 `new Settings(6, "TagTeamVs", "ScoreV2")`
  - `state.slots()` 為 `Map.of(1, new Slot("Alexlin86", null, null))`
  - 事件數：`PlayerFinished` 3、`MatchFinished` 2、`AllPlayersReady` 2、`MatchStarted` 3、`MatchAborted` 1
  - 測試結束後 `output` 不含 `未辨識的 BanchoBot 訊息`
- `MatchCommandsTest.roomsShowsGameMode`：`matchService.attach(42)` 後 `commands.rooms(null)` 含 `"mode ?"`。

- [ ] **Step 3: 跑測試確認失敗**

Run: `./mvnw test -Dtest=BanchoBotParserTest,MatchStateTest,MatchReplayIntegrationTest,MatchCommandsTest`
Expected: 編譯失敗（新事件、`gameMode()` 不存在）。

- [ ] **Step 4: 實作**

- `MatchEvent` 加四個 record，Javadoc 寫原文格式（同既有寫法）。
- `BanchoBotParser.CHANNEL_RULES` 加規則：`Changed match mode to (\S+)`、`(.+?) finished playing \(Score: (\d+), (\w+)\)\.`、`The match has finished!`、`All players are ready`；`IGNORED` 加 `Removed the match password`。
- `MatchState` 加元件 `gameMode`，`switch` 處理四個新事件（`MatchStarted`/`MatchAborted` 那一行可一起列不改狀態的事件）。
- `MatchCommands.describe` 在 `map` 之後加 `"  mode " + (gameMode == null ? "?" : gameMode)`。

- [ ] **Step 5: 跑全部測試**

Run: `./mvnw test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/idv/alexlin7/tw/bancho/match/ src/main/java/idv/alexlin7/tw/bancho/shell/MatchCommands.java src/test/
git commit -m "feat: 解析遊玩模式變更、玩家完成、比賽結束與全員準備"
```

---

### Task 3: 擋下連寫的 mod

**Files:**
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/MatchRoom.java`（`modsCommand`）
- Test: `MatchRoomTest`、`MatchRoomPresetTest`、`MatchCommandsTest`

**Interfaces:**
- Produces：`MatchRoom.mods(String...)` 與 `apply` 在任一 mod 長度大於 2 且不是 `Freemod`（不分大小寫）時丟 `IllegalArgumentException("mod 要用空白分開，例如 mods NF HD")`。

- [ ] **Step 1: 寫失敗的測試**

- `MatchRoomTest.concatenatedModsAreRejected`：`room.mods("NFHD")` 丟 `IllegalArgumentException`，訊息為 `mod 要用空白分開，例如 mods NF HD`；`sender.calls` 為空。
- `MatchRoomTest.freemodIsAccepted`：`room.mods("Freemod")`、`room.mods("freemod")`、`room.mods("HD", "HR")` 都送出（`!mp mods Freemod` 等）。
- `MatchRoomPresetTest.concatenatedModsSendNothing`：`new MatchPreset(0, 3, 2, null, List.of("NFHD"), null, null, null, null)` 套用時丟例外，`sender.calls` 為空。
- `MatchCommandsTest.modsRejectsConcatenatedNames`：`commands.mods(List.of("NFHD"), 42L, null)` 等於 `"錯誤：mod 要用空白分開，例如 mods NF HD"`，不送。

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw test -Dtest=MatchRoomTest,MatchRoomPresetTest,MatchCommandsTest`
Expected: FAIL（`NFHD` 被送出）。

- [ ] **Step 3: 實作**

在 `modsCommand` 既有的空白檢查迴圈內加上長度檢查；允許的長名稱集合為 `Set.of("freemod")`，比對前轉小寫（`Locale.ROOT`）。

- [ ] **Step 4: 跑全部測試**

Run: `./mvnw test`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/idv/alexlin7/tw/bancho/match/MatchRoom.java src/test/
git commit -m "fix: 擋下連寫的 mod，避免 BanchoBot 清空所有 mod"
```

---

### Task 4: 密碼改成遮蔽提示輸入

**Files:**
- Create: `src/main/java/idv/alexlin7/tw/bancho/shell/SecretPrompt.java`、`shell/JLineSecretPrompt.java`
- Modify: `shell/ShellConfiguration.java`、`shell/MatchCommands.java`（建構子、`password`、`mp`）、`src/main/resources/application.properties`、`src/test/resources/application.properties`、`.gitignore`
- Test: `src/test/java/idv/alexlin7/tw/bancho/shell/JLineSecretPromptTest.java`（新）、`MatchCommandsTest`、`ShellCommandParsingTest`、`observability/LoggingPropertiesTest`

**Interfaces:**
- Produces：
  ```java
  @FunctionalInterface
  public interface SecretPrompt {
      /** @return 輸入的文字；空白輸入、Ctrl+C、Ctrl+D 時為 empty
       *  @throws IllegalStateException 無法提示輸入（非互動模式） */
      Optional<String> read(String prompt);
  }
  ```
- Produces：`final class JLineSecretPrompt implements SecretPrompt`，建構子 `JLineSecretPrompt(LineReader reader)`；以 `reader.readLine(prompt, '*')` 讀取，`UserInterruptException`／`EndOfFileException` 回 empty，結果 `strip()` 後為空也回 empty。
- Produces：`ShellConfiguration` 的 `@Bean SecretPrompt secretPrompt(ObjectProvider<LineReader> lineReader, @Value("${spring.shell.interactive.enabled:true}") boolean interactive)`：互動時回 `JLineSecretPrompt`，否則回丟 `IllegalStateException("非互動模式無法輸入密碼")` 的實作。
- Produces：`MatchCommands` 建構子最後加一個參數 `SecretPrompt secretPrompt`；`password(@Arguments List<String> words, @Option(longName = "clear", description = "移除密碼") boolean clear, @Option(longName = ROOM, …) Long room, CommandContext context)`。

- [ ] **Step 1: 寫 `JLineSecretPromptTest`（失敗）**

用不接系統主控台的 terminal 建 `LineReader`（`TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(input.getBytes(UTF_8)), OutputStream.nullOutputStream()).build()`，`LineReaderBuilder.builder().terminal(t).build()`）：
- `readsTypedSecret`：輸入 `"hunter2\r"` → `Optional.of("hunter2")`
- `blankInputCancels`：輸入 `"  \r"` → empty
- `endOfInputCancels`：輸入 `""` → empty
- `secretIsNotAddedToHistory`：讀完 `"hunter2\r"` 後 `reader.getHistory().size()` 為 0

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw test -Dtest=JLineSecretPromptTest`
Expected: 編譯失敗（類別不存在）。

- [ ] **Step 3: 實作 `SecretPrompt`、`JLineSecretPrompt`，跑測試**

Run: `./mvnw test -Dtest=JLineSecretPromptTest`
Expected: PASS。若 `secretIsNotAddedToHistory` 失敗，就在讀取前 `reader.setOpt(LineReader.Option.DISABLE_HISTORY)`、`finally` 中依原本狀態還原，並在 ledger 記下 JLine 的實際行為。

- [ ] **Step 4: 寫 `password`／`mp` 的失敗測試**

`MatchCommandsTest` 的建構改成多傳一個假的 `SecretPrompt`：測試類別欄位 `private Optional<String> typed = Optional.empty(); private final List<String> prompts = new ArrayList<>();`，lambda 為 `prompt -> { prompts.add(prompt); return typed; }`。
- `passwordIsReadFromPrompt`：`typed = Optional.of("abc")`，`commands.password(List.of(), false, 42L, null)` 等於 `"已送出：!mp password ***"`；送出 `"msg #mp_42 !mp password abc"`；`prompts` 為 `["新密碼："]`
- `cancelledPromptSendsNothing`：`typed = Optional.empty()` → 等於 `"已取消，密碼沒有變更"`，不送
- `clearRemovesPassword`：`commands.password(List.of(), true, 42L, null)` 等於 `"已送出：!mp password（移除密碼）"`，送出 `"msg #mp_42 !mp password"`，`prompts` 為空
- `passwordOnCommandLineIsRejected`：`commands.password(List.of("abc"), false, 42L, null)` 等於 `"錯誤：密碼請在提示中輸入，不要寫在指令裡（會留在指令歷史）"`，不送、`prompts` 為空
- `passwordWithoutRoomDoesNotPrompt`：沒有目前房間時 `commands.password(List.of(), false, null, null)` 等於 `NO_ROOM`，`prompts` 為空
- `mpPasswordIsRejected`（取代 `mpMasksPasswordReply`）：`mp(List.of("password", "secret"), …)`、`mp(List.of("PaSsWoRd"), …)` 都等於 `"錯誤：設定密碼請用 password 指令（移除請用 password --clear）"`，不送
- `otherRoomCommands` 改呼叫 `commands.password(List.of(), true, 42L, null)`，預期送出不變

`ShellCommandParsingTest`（測試 context 為非互動）：
- 從 `startTimerAndPasswordTakePositionalValues`、`withoutValueStartTimerAndPasswordUseDefaults` 移除 password 那一行與對應的預期
- `passwordInNonInteractiveModeIsAnError`：`run("password --room 42")` 含 `錯誤：非互動模式無法輸入密碼`；`run("password --clear --room 42")` 送出 `"msg #mp_42 !mp password"`；`run("password hunter2 --room 42")` 含 `不要寫在指令裡`，且 sender 只收到 clear 那一則

`LoggingPropertiesTest.shellHistoryHasItsOwnFile`：main 的 `application.properties` 中 `spring.shell.history.name` 為 `bancho-history`。

- [ ] **Step 5: 跑測試確認失敗**

Run: `./mvnw test -Dtest=MatchCommandsTest,ShellCommandParsingTest,LoggingPropertiesTest`
Expected: 編譯失敗（建構子與 `password` 簽章不符）。

- [ ] **Step 6: 實作**

- `password` 的流程：`InputGuard.check(context, InputGuard.ANY, ROOM, "clear")` → `words` 非空就回拒絕訊息 → `rooms.onRoom(room, r -> …)`。在 action 裡：`clear` 時 `r.password(null)`；否則 `secretPrompt.read("新密碼：")`，empty 回取消訊息，有值時 `r.password(值)`。非互動模式的 `IllegalStateException` 由 `RoomResolver.guarded` 轉成 `錯誤：…`。
- `password` 的 description 改成 `設定房間密碼（在提示中輸入，以 * 顯示）；--clear 移除密碼`。
- `mp`：子指令 `password`（不分大小寫）時回上面的拒絕訊息，移除原本的遮蔽回應。
- `ShellConfiguration` 加 `secretPrompt` bean。
- main `application.properties` 加註解與 `spring.shell.history.name=bancho-history`（註解：Spring Shell 預設用 `${spring.application.name}.log` 存指令歷史，會和 `logs/bancho.log` 混淆）；test `application.properties` 加 `spring.shell.history.enabled=false`。
- `.gitignore` 的 `### Log ###` 區塊加 `bancho-history`。

- [ ] **Step 7: 跑全部測試**

Run: `./mvnw test`
Expected: BUILD SUCCESS。

- [ ] **Step 8: Commit**

```bash
git add .gitignore src/
git commit -m "feat: 密碼改成遮蔽提示輸入，不再留在指令歷史"
```

---

### Task 5: 授權 JLine 的 native access，更新 CLAUDE.md

**Files:**
- Modify: `pom.xml`、`CLAUDE.md`、`docs/superpowers/specs/2026-10-17-shell-feedback-design.md`（狀態與待驗證結果）

- [ ] **Step 1: 修改 `pom.xml`**

- `spring-boot-maven-plugin` 加 `<configuration><jvmArguments>--enable-native-access=ALL-UNNAMED</jvmArguments></configuration>`（`spring-boot:run`）。
- 打包的 manifest 加 `Enable-Native-Access: ALL-UNNAMED`：在 `maven-jar-plugin` 的 `<archive><manifestEntries>` 設定（Boot 的 repackage 會保留原 manifest 的條目）。

- [ ] **Step 2: 驗證 manifest 與 `java -jar` 沒有警告**

```bash
./mvnw -q package -DskipTests
unzip -p target/bancho-0.0.1-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Enable-Native-Access
D=$(mktemp -d)
SPRING_SHELL_INTERACTIVE_ENABLED=false OSU_IRC_AUTO_START=false OSU_IRC_USERNAME=x OSU_IRC_PASSWORD=x \
  BANCHO_LOG_FILE="$(cygpath -m "$D")/bancho.log" \
  java -jar target/bancho-0.0.1-SNAPSHOT.jar rooms > "$D/out.txt" 2>&1; echo exit=$?
grep -c "restricted method" "$D/out.txt"
```
Expected：manifest 有 `Enable-Native-Access: ALL-UNNAMED`、`exit=0`、`restricted method` 為 0 行。
非互動模式可能根本不會碰到 JLine 的 FFM。如果改掉 manifest 後重跑，警告也沒有出現，這個驗證就無效；這時在 ledger 記下來，交給使用者在真正的終端機用 `java -jar` 確認。如果 fat jar 的 manifest 沒有效果，就在 CLAUDE.md 的啟動指令加 `--enable-native-access=ALL-UNNAMED`。

- [ ] **Step 3: 更新 CLAUDE.md**

- 「指令」：`java -jar` 這一行註明 manifest 已授權 native access，`spring-boot:run` 也已透過 `jvmArguments` 授權；protobuf 的 `sun.misc.Unsafe` 警告來自 OTLP metrics 依賴，先不處理，日後 JDK 預設改成 deny 時，要升級 protobuf 或加 `--sun-misc-unsafe-memory-access=allow`。
- 「指令」的 log 段落：補上指令歷史存在 `<工作目錄>/bancho-history`（`spring.shell.history.name`）。
- 「shell」：`MatchCommands` 清單的 `password [密碼]` 改成 `password [--clear]`；補一句 `password` 用 `SecretPrompt` 遮蔽提示輸入（`*` 回顯），不接受寫在指令裡，非互動模式回錯誤；`mp password` 被拒絕。
- 「match」：`MatchState` 有 `gameMode`（BanchoBot 原文）。

- [ ] **Step 4: 更新 spec 狀態**

把 spec 狀態改成「已實作」；在第 8 節表格把實測結果填進去，包括 Task 1 Step 4 的訊息、Task 4 Step 3 的歷史行為和 Task 5 Step 2 的結果。

- [ ] **Step 5: 跑全部測試並 commit**

Run: `./mvnw test`
Expected: BUILD SUCCESS。
```bash
git add pom.xml CLAUDE.md docs/superpowers/specs/2026-10-17-shell-feedback-design.md
git commit -m "build: 授權 JLine 的 native access，更新文件"
```

---

## 交給使用者實測

- `password`：出現 `新密碼：`、輸入以 `*` 顯示；Ctrl+C 取消；之後 `history` 與 `bancho-history` 都沒有密碼。
- `set` 少參數回用法、`mods NFHD` 被擋。
- 開機時不再出現 JLine 的 `restricted method` 警告（protobuf 的仍會出現）。
- 刪除專案根目錄舊的 `bancho.log`、`bancho-test.log`，因為裡面有明文密碼。

---

### Task 6: 以 AtomicReference 取代 volatile 參考（SonarQube S3077）

使用者回報 SonarLint 對 `MatchRoom.state`、`MatchCommands.lastMake` 報 S3077（"Use a thread-safe type; adding volatile is not enough"）。兩者都是「整個換掉不可變物件／future 的參考」，`volatile` 在語意上已足夠，但 Sonar 無法判斷 `MatchState` 不可變；改用 `AtomicReference` 讓意圖明確，行為不變。

**Files:**
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/MatchRoom.java`、`src/main/java/idv/alexlin7/tw/bancho/shell/MatchCommands.java`

- [ ] **Step 1: 改寫**

- `MatchRoom`：`private final AtomicReference<MatchState> state`；`state()` 回 `state.get()`；套用事件改成 `state.updateAndGet(current -> current.apply(event))`；其他讀取改 `state.get()`。
- `MatchCommands`：`private final AtomicReference<CompletableFuture<?>> lastMake = new AtomicReference<>(CompletableFuture.completedFuture(null))`，`make` 用 `set`，`lastMake()` 回 `get()`。
- `ShellSession.currentRoom`（`volatile Long`）與各 `volatile boolean` 不在回報範圍內，維持原狀。

- [ ] **Step 2: 跑全部測試並 commit**

純重構，既有的 `MatchRoomTest`、`MatchStateTest`、`MatchServiceTest`、`MatchCommandsTest` 已涵蓋行為。
Run: `./mvnw test`
Expected: BUILD SUCCESS。
```bash
git add src/main/java/idv/alexlin7/tw/bancho/match/MatchRoom.java src/main/java/idv/alexlin7/tw/bancho/shell/MatchCommands.java
git commit -m "refactor: 以 AtomicReference 取代 volatile 參考，消除 SonarQube S3077"
```

---

### Task 7: Ctrl+C 連按兩次才離開（spec 第 9 節）

**Files:**
- Create: `src/main/java/idv/alexlin7/tw/bancho/shell/CtrlCGuard.java`、`shell/InterruptAwareInputProvider.java`
- Modify: `shell/ShellLineEditing.java`、`CLAUDE.md`
- Test: `CtrlCGuardTest`（新）、`InterruptAwareInputProviderTest`（新）、`ShellLineEditingTest`

**Interfaces:**
- `final class CtrlCGuard`：`CtrlCGuard(Clock clock, Duration window)`；`Action onInterrupt(String partialLine)`；`void reset()`；`enum Action { CLEAR_LINE, WARN, EXIT }`。
- `final class InterruptAwareInputProvider extends JLineInputProvider`：`InterruptAwareInputProvider(JLineInputProvider delegate, CtrlCGuard guard)`，`readInput()` 委派給 delegate；成功讀到一行時 `guard.reset()`；`UserInterruptException` 時依 Action 回 `""`／印提示並回 `""`／回 `null`。提示 `（再按一次 Ctrl+C 離開，或輸入 quit）` 寫到 reader 的 terminal writer。

- [ ] **Step 1: 失敗的測試**
  - `CtrlCGuardTest`（可調整的假 Clock，window 2 秒）：`partialLineClearsWithoutArming`（"abc" → CLEAR_LINE，接著空白 → WARN）；`firstEmptyInterruptWarns`；`secondWithinWindowExits`（+1.9 秒、+2 秒都 EXIT）；`afterWindowWarnsAgain`（+2.1 秒 → WARN）；`resetDisarms`（WARN、reset、空白 → WARN）。
  - `InterruptAwareInputProviderTest`（delegate 為覆寫 `readInput` 的子類別，依序丟出例外或回傳字串；reader 用輸出到 `ByteArrayOutputStream` 的 dumb terminal）：一般輸入原樣回傳；空白中斷回 `""` 且輸出含提示；2 秒內第二次回 `null`；輸入到一半的中斷回 `""` 且沒有提示。
  - `ShellLineEditingTest.wrapsInputProvider`：處理 `JLineInputProvider` 後得到 `InterruptAwareInputProvider`，且 `getLineReader()` 是同一個 reader；已包過的不再包一次。
- [ ] **Step 2: 跑測試確認失敗**（編譯失敗）
- [ ] **Step 3: 實作**；`ShellLineEditing` 以 `Clock.systemUTC()`、`Duration.ofSeconds(2)` 建立 guard。
- [ ] **Step 4: `./mvnw test` 全綠；CLAUDE.md 的 shell 段落補上行為與 `spring-boot:run` 的限制**
- [ ] **Step 5: Commit** `feat: Ctrl+C 連按兩次才離開 shell`
