# Shell 實測修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依 2026-10-17 實測結果補上 map 遊玩模式、新 BanchoBot 樣本、`mp` 轉送指令、`-` 開頭名稱的跳脫提示，以及 log 改寫檔案。

**Architecture:** 沿用 `shell → match → irc`。遊玩模式是 `match` 層的 `GameMode` enum；新事件加進 sealed `MatchEvent`；shell 指令照舊經過 `InputGuard` 與 `RoomResolver`。log 只動設定檔。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring Shell 4.0.3、JUnit 5、AssertJ、Maven wrapper。

**Spec:** `docs/superpowers/specs/2026-10-17-shell-refinements-design.md`

**執行紀錄（2026-10-17）：** Task 1–6 已實作並各自提交。Task 5 採設計備案，不自動加補全引號：真實 JLine 對已輸入開引號的前綴會重複引號；完整說明由 `addref --help`／`invite --help` 顯示。缺省模式在 Spring Shell 會綁成空字串，實作將空字串與 null 都視為未指定。以下 checkbox 保留原始執行步驟供追溯；驗證結果以提交紀錄與設計文件為準。

**Review 修正：** 原 Task 5 固定共用 `addref` 文案會誤導邀請操作；實際實作改為保留原指令，`invite` 的錯誤與完整 help 皆示範 `invite`，`addref` 示範不變。通用未知選項提示不再指定另一個指令。

## Global Constraints

- 分支：`feature/match-room-phase2`，每個 task 結束時 commit；commit attribution 只標註實際參與者，不沿用其他模型或 session 的 trailer。
- 依賴方向 `shell → match → irc`；`match.preset` 不得 import `match`（`match` 已 import `match.preset`，反過來會成環）。
- 程式註解、log、使用者看到的訊息一律繁體中文；log 用 `@Slf4j`；錯誤以 `錯誤：` 開頭的字串回給 shell，不讓例外冒出。
- 遊玩模式：0 osu!、1 osu!taiko、2 osu!catch、3 osu!mania；名稱 `osu`/`std`→0、`taiko`→1、`catch`/`fruits`/`ctb`→2、`mania`→3，不分大小寫。
- 新解析規則必須比對整行；樣本檔 `.irc` 的行尾空白是原文（`.editorconfig` 已設定不修剪）。
- Git Bash 下用 `./mvnw`；長輸出導到檔案再看結尾。
- 每個 task 結束前跑 `./mvnw test`，全部通過。

## Review Focus

1. `addref -- -SH- --room 42` 與 `addref -- -SH- --room=42`：`--` 之後的房間選項不可被當成裁判名稱送出 → 回錯誤、不送指令，並教使用者改用 `addref "-SH-" --room 42`；`invite` 同樣處理（Task 5）。
2. `map 2453358 MANIA`、`map 2453358 Ctb` 大小寫與別名可用；`map 2453358 4`、`map 2453358 abc` 回錯誤且不送任何指令（Task 2）。
3. 範本的 `mode` 值無效（例如 `mode: 9`）時，啟動就失敗；不會開出房間後才發現範本錯誤（Task 1）。
4. `mp`、`mp make x`、`mp MakePrivate x` 都不送指令；`mp password secret` 的回覆不可出現 `secret`（Task 4）。
5. `map --room 42 2453358 <Tab>`：選項與其值不算位置參數，第二個位置參數仍補出模式名稱（Task 2）。
6. `Started the match` 與 `The match has started!` 同時出現時只產生一個 `MatchStarted`（Task 3 回放測試數事件）。

---

### Task 1: `GameMode` 與 `MatchRoom.map(id, mode)`、範本的 `mode`

**Files:**
- Create: `src/main/java/idv/alexlin7/tw/bancho/match/GameMode.java`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/MatchRoom.java`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/preset/MatchPreset.java`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/MatchConfiguration.java`
- Test: `src/test/java/idv/alexlin7/tw/bancho/match/GameModeTest.java`（新）、`MatchRoomTest.java`、`MatchRoomPresetTest.java`、`preset/MatchPresetPropertiesTest.java`

**Interfaces:**
- Produces:
  - `public enum GameMode { OSU, TAIKO, CATCH, MANIA; public int id(); public static Optional<GameMode> parse(String text); }` — `parse` 接受 `0`–`3` 與 Global Constraints 的名稱，前後空白去除，`null`/空白/其他值回 `Optional.empty()`。
  - `MatchRoom.map(long beatmapId, GameMode mode)` → 送 `!mp map <id> <mode.id()>`；`mode == null` 時等同 `map(long)`。
  - `MatchPreset` 新增最後一個欄位 `String mode`（原文字串）。建構子：`mode != null && beatmap == null` 時丟 `IllegalArgumentException("mode 必須和 beatmap 一起設定")`。

  - `MatchConfiguration` 新增 `@Bean InitializingBean presetModeValidation(MatchPresetProperties presets)`：逐一以 `GameMode.parse` 檢查有填的 `mode`，無效時丟 `IllegalStateException("範本 " + 名稱 + " 的 mode 無效：" + mode + "（可用 osu、taiko、catch、mania 或 0–3）")`，讓啟動失敗。

`match.preset` 不能 import `GameMode`（會成環），所以 `MatchPreset.mode` 存字串，值的檢查放在 `match` 層的 `MatchConfiguration`（依賴方向仍是 `match → match.preset`）。`MatchRoom.apply` 的事前檢查保留作為直接呼叫時的防線。

- [ ] **Step 1: 寫失敗的測試**

`GameModeTest`：
```java
@ParameterizedTest
@CsvSource({"0,OSU", "osu,OSU", "STD,OSU", "1,TAIKO", "Taiko,TAIKO", "2,CATCH", "catch,CATCH",
        "fruits,CATCH", "CTB,CATCH", "3,MANIA", "mania,MANIA", "' mania ',MANIA"})
void parsesNumbersAndNames(String text, GameMode expected) {
    assertThat(GameMode.parse(text)).contains(expected);
}

@ParameterizedTest
@NullAndEmptySource
@ValueSource(strings = {"4", "-1", "abc", " ", "osu!"})
void rejectsUnknown(String text) {
    assertThat(GameMode.parse(text)).isEmpty();
}

@Test
void idsMatchBanchoBot() {
    assertThat(Arrays.stream(GameMode.values()).map(GameMode::id)).containsExactly(0, 1, 2, 3);
}
```
`MatchRoomTest`：`room.map(2453358, GameMode.MANIA)` → `sender.calls` 含 `"msg #mp_<id> !mp map 2453358 3"`；`room.map(2453358, null)` → `"... !mp map 2453358"`。
`MatchRoomPresetTest`（建構子多一個參數，既有呼叫補 `null`）：
- `appliesModeWithBeatmap`：`new MatchPreset(null,null,null,null,null,1502772L,null,null,"mania")` → `containsExactly("msg #mp_123 !mp map 1502772 3")`
- `invalidModeSendsNothing`：`new MatchPreset(0,3,2,null,null,1502772L,null,null,"9")` → `apply` 丟 `IllegalArgumentException`，`sender.calls` 為空。
- `modeWithoutBeatmapIsRejected`：建構子丟 `IllegalArgumentException`，訊息 `mode 必須和 beatmap 一起設定`。
`MatchPresetPropertiesTest`：`modeWithoutBeatmapFailsAtStartup`（只設 `bancho.match.presets.bad.mode=mania` → `hasFailed()`）；`invalidModeFailsAtStartup`（`bad.beatmap=1502772`、`bad.mode=9` → `hasFailed()`，失敗原因訊息含 `範本 bad 的 mode 無效：9`）；`validModeStarts`（`ok.beatmap=1502772`、`ok.mode=ctb` → `hasNotFailed()`）；既有的 `isEqualTo(new MatchPreset(...))` 補 `null`。

- [ ] **Step 2: 執行，確認失敗**

Run: `./mvnw test -Dtest='GameModeTest,MatchRoom*Test,MatchPresetPropertiesTest'`
Expected: 編譯失敗（`GameMode` 不存在、`MatchPreset` 參數數量不符）。

- [ ] **Step 3: 實作**

`GameMode` 每個常數帶 `id` 與別名清單；`MatchRoom` 新增私有 `mapCommand(long, GameMode)`，`apply` 在組指令階段用 `GameMode.parse(preset.mode())`，解析不到時丟 `IllegalArgumentException("不認得的遊玩模式：" + mode)`（仍在送出前）。`MatchPreset` 的 Javadoc 加 `@param mode`。CLAUDE.md「`match/preset`」段落的欄位清單加上 `mode`（需搭配 `beatmap`），YAML 範例不變。

- [ ] **Step 4: 執行，確認通過**

Run: `./mvnw test -Dtest='GameModeTest,MatchRoom*Test,MatchPresetPropertiesTest'`
Expected: PASS。再跑 `./mvnw test` 全部通過（`MatchPreset` 的其他呼叫處要補參數）。

- [ ] **Step 5: Commit** — `feat: map 指定遊玩模式（GameMode）與範本的 mode`

---

### Task 2: shell 的 `map <id> [模式]` 與模式補全

**Files:**
- Modify: `src/main/java/idv/alexlin7/tw/bancho/shell/MatchCommands.java:134-142`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/shell/ShellCompletions.java`
- Test: `MatchCommandsTest.java`、`ShellCommandParsingTest.java`、`ShellCompletionsTest.java`

**Interfaces:**
- Consumes: `GameMode.parse(String)`、`MatchRoom.map(long, GameMode)`（Task 1）。
- Produces: `MatchCommands.map(long beatmapId, String mode, Long room, CommandContext context)`，mode 是 `@Argument(index = 1)`、可為 null；`@Command(completionProvider = "mapCompletion")`；bean `CompletionProvider mapCompletion(MatchService)`。

- [ ] **Step 1: 寫失敗的測試**

`MatchCommandsTest`：
- `map(2453358, "mania", 42L, null)` → 回傳 `"已送出：!mp map 2453358 3"`，送出 `"msg #mp_42 !mp map 2453358 3"`
- `map(2453358, null, 42L, null)` → `"已送出：!mp map 2453358"`
- `map(2453358, "4", 42L, null)` → 回傳 `"錯誤：不認得的遊玩模式：4（可用 osu、taiko、catch、mania 或 0–3）"`，`sender.calls` 為空

`ShellCommandParsingTest`：
```java
@Test
void mapTakesOptionalMode() throws Exception {
    run("map 2453358 2 --room 42");
    run("map 2453358 MANIA --room 42");
    run("map 2453358 --room 42");
    assertThat(sender.calls).containsExactly("msg #mp_42 !mp map 2453358 2",
            "msg #mp_42 !mp map 2453358 3", "msg #mp_42 !mp map 2453358");
}

@Test
void mapRejectsUnknownModeAndExtraArguments() throws Exception {
    assertThat(run("map 2453358 abc --room 42")).contains("不認得的遊玩模式：abc");
    assertThat(run("map 2453358 3 x --room 42")).contains("多餘的參數：x");
    assertThat(sender.calls).isEmpty();
}
```
`ShellCompletionsTest`：
- `mapCompletionOffersModeNamesForSecondArgument`：`typing("map", "2453358", "")` → values `containsExactly("osu", "taiko", "catch", "mania")`，descriptions `containsExactly("0", "1", "2", "3")`；`typing("map", "2453358", "MA")` → `containsExactly("mania")`
- `mapCompletionCountsOnlyPositionalWords`：`typing("map", "--room", "42", "2453358", "")` → 模式清單；`typing("map", "--room", "42", "")` → 空（這是 beatmap id 的位置）
- `mapCompletionOffersNothingForBeatmapId`：`typing("map", "")` → 空
- `mapCompletionStillCompletesRoomOption`：`typingRoomOption("map", "1", "--room", "")` → 開著的房號

- [ ] **Step 2: 執行，確認失敗**

Run: `./mvnw test -Dtest='MatchCommandsTest,ShellCommandParsingTest,ShellCompletionsTest'`
Expected: 編譯失敗（`map` 參數數量、`mapCompletion` 不存在）。

- [ ] **Step 3: 實作**

`map` 的 guard 上限改為 2；模式解析失敗時直接回錯誤字串（不呼叫 `MatchRoom`）。`description` 改為 `換圖；可指定遊玩模式（osu、taiko、catch、mania 或 0–3），std 圖可轉成其他模式`。`mapCompletion` 用 `withRoomOption`，主要參數依「目前是第幾個位置參數」決定：從 `words[1]` 數到 `getWordIndex()` 之前，以 `-` 開頭的字算選項，`--room` 再連同下一個字（它的值）一起略過，其餘才算位置參數；第 1 個位置參數（0 起算，第 0 個是 beatmap id）回模式清單，其他位置回空。CLAUDE.md shell 段落的指令列表把 `map` 改成 `map <beatmapId> [模式]`，補全清單加 `mapCompletion`。

- [ ] **Step 4: 執行，確認通過** — 同 Step 2 指令，PASS；`./mvnw test` 全部通過。

- [ ] **Step 5: Commit** — `feat: shell map 可指定遊玩模式並補全模式名稱`

---

### Task 3: 新 BanchoBot 樣本、`MatchStarted` / `MatchAborted`、清空 mod

**Files:**
- Create: `src/test/resources/banchobot/session-2026-10-17.irc`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/event/MatchEvent.java`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/BanchoBotParser.java`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/MatchState.java`（及其他對 `MatchEvent` 做窮舉 switch 的地方，以編譯錯誤為準）
- Test: `BanchoBotParserTest.java`、`MatchStateTest.java`（若無則加在既有狀態測試）、`MatchReplayIntegrationTest.java`

**Interfaces:**
- Produces: `record MatchStarted(long matchId)`、`record MatchAborted(long matchId)`（`MatchEvent` 的巢狀 record，Javadoc 寫原文）。`MatchState.apply` 對這兩個事件回傳原狀態。

- [ ] **Step 1: 建立樣本檔**

內容如下（`#` 開頭為註解；Slot 行結尾是 **7 個空白**，與 2026-10-16 的樣本相同）：
```
# 使用者 2026-10-17 以 Spring Shell 實測的 BanchoBot 原文，依原順序；私訊對象統一為 test_user。
:BanchoBot!cho@ppy.sh PRIVMSG test_user :Created the tournament match https://osu.ppy.sh/mp/122022152 mp測試です
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Invited Alexlin86 to the room
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Alexlin86 joined in slot 1.
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Changed beatmap to https://osu.ppy.sh/b/2160012 Kaneko Chiharu - iLLness LiLin
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Disabled all mods, disabled FreeMod
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Enabled Hidden, disabled FreeMod
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Enabled NoFail, Hidden, disabled FreeMod
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Countdown ends in 10 seconds
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Countdown ends in 5 seconds
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Countdown ends in 4 seconds
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Countdown ends in 3 seconds
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Countdown ends in 2 seconds
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Countdown ends in 1 second
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Countdown finished
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Changed the match password
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Room name: mp測試です, History: https://osu.ppy.sh/mp/122022152
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Beatmap: https://osu.ppy.sh/b/2160012 Kaneko Chiharu - iLLness LiLin
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Team mode: HeadToHead, Win condition: Score
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Active mods: NoFail, Hidden
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Players: 1
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Slot 1  Not Ready https://osu.ppy.sh/u/4841753 Alexlin86       
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :The match has started!
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Started the match
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Aborted the match
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :Changed beatmap to https://osu.ppy.sh/b/2453358 Minato Aqua - Shiroi Yuki no Princess wa
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :User not found: alexlin7
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :You have too many referees! Remove some to add new ones.
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :User not found: 2548278
:BanchoBot!cho@ppy.sh PRIVMSG #mp_122022152 :User not found: _AppleCat_
```

- [ ] **Step 2: 寫失敗的測試**

`BanchoBotParserTest.realSamples` 加入（頻道 `#mp_42`）：
- `Disabled all mods, disabled FreeMod` → `new ModsChanged(42, List.of(), false)`
- `Disabled all mods, enabled FreeMod` → `new ModsChanged(42, List.of(), true)`
- `The match has started!` → `new MatchStarted(42)`
- `Aborted the match` → `new MatchAborted(42)`

`ignoresKnownSettingsLines` 加入：`Started the match`、`Countdown ends in 10 seconds`、`Countdown ends in 1 second`、`Countdown finished`、`Changed the match password`、`Active mods: NoFail, Hidden`。
新測試 `stillReportsErrorRepliesAsUnrecognized`：`User not found: alexlin7`、`You have too many referees! Remove some to add new ones.` → `Result.Unrecognized`。
狀態測試 `disabledAllModsClearsMods`：先套 `ModsChanged(42, List.of("Hidden"), false)` 再套 `ModsChanged(42, List.of(), false)` → `mods()` 為 `new ModSelection(List.of(), false)`；`startedAndAbortedKeepState`：套用 `MatchStarted`、`MatchAborted` 後 `isEqualTo` 套用前。

`MatchReplayIntegrationTest`：把現有回放流程抽成共用方法（參數：資源路徑、要等待的 JOIN 行），新增 `replaysSecondSessionIntoRoomState`：等 `JOIN #mp_122022152`，送完全部行後最終狀態——
```java
assertThat(state.name()).isEqualTo("mp測試です");
assertThat(state.closed()).isFalse();
assertThat(state.beatmap()).isEqualTo(new Beatmap(2453358, "Minato Aqua - Shiroi Yuki no Princess wa"));
assertThat(state.mods()).isEqualTo(new ModSelection(List.of("NoFail", "Hidden"), false));
assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("Alexlin86", 4841753L, "Not Ready")));
```
並以 `@EventListener` 測試 bean（或 `ApplicationEvents`）收集事件：`MatchStarted` 恰好 1 個、`MatchAborted` 恰好 1 個；log 中「未辨識的 BanchoBot 訊息」恰好出現 4 次（3 則 User not found + 1 則 too many referees）。等待「全部處理完」的方式：送完樣本後再送一行可辨識的哨兵訊息不可行（會改狀態）——改為 `await` 直到收集到的事件包含最後一個 `BeatmapChanged(…, 2453358, …)`，再額外等 log 出現 `User not found: _AppleCat_`（用有時限的輪詢，不用 `Thread.sleep`）。

- [ ] **Step 3: 執行，確認失敗**

Run: `./mvnw test -Dtest='BanchoBotParserTest,MatchState*Test,MatchReplayIntegrationTest'`
Expected: 編譯失敗（`MatchStarted` / `MatchAborted` 不存在）。

- [ ] **Step 4: 實作**

`CHANNEL_RULES` 加：`The match has started!` → `MatchStarted`、`Aborted the match` → `MatchAborted`、`Disabled all mods, (enabled|disabled) FreeMod` → `ModsChanged(id, List.of(), "enabled".equals(g1))`。`IGNORED` 加：`Started the match`、`Countdown ends in \d+ seconds?`、`Countdown finished`、`Changed the match password`、`Active mods: .+`。`IGNORED` 目前只用於頻道訊息，確認新規則也只在頻道比對。編譯器指出的每個窮舉 switch 補上兩個新事件（`MatchState` 回 `this`；`KnownUsers` 等不處理的地方照既有寫法忽略）。

CLAUDE.md：`MatchEvent` 說明不用改；「慣例」的樣本段落不用改。spec（2026-10-16）第 1 節「不在這次範圍」中「比賽開始/結束」改為「比賽結束、ready、分數」。

- [ ] **Step 5: 執行，確認通過** — 同 Step 3 指令 PASS；`./mvnw test` 全部通過。

- [ ] **Step 6: Commit** — `feat: 解析比賽開始、中止與清空 mod，略過倒數等已知訊息`

---

### Task 4: `mp` 轉送指令

**Files:**
- Modify: `src/main/java/idv/alexlin7/tw/bancho/match/MatchRoom.java`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/shell/MatchCommands.java`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/shell/ShellCompletions.java`
- Test: `MatchRoomTest.java`、`MatchCommandsTest.java`、`ShellCommandParsingTest.java`、`ShellCompletionsTest.java`

**Interfaces:**
- Produces:
  - `MatchRoom.raw(String arguments)`：`null`/空白丟 `IllegalArgumentException("指令不可為空白")`；否則送 `"!mp " + arguments.strip()`；房間關閉時照 `command()` 丟 `IllegalStateException`。
  - `MatchCommands.mp(@Arguments List<String> words, @Option(longName = "room") Long room, CommandContext context)`，`@Command(name = "mp", completionProvider = "mpCompletion")`。
  - `ShellCompletions.MP_SUBCOMMANDS = List.of("aborttimer", "clearhost", "host", "kick", "listrefs", "lock", "move", "removeref", "size", "team", "unlock")`；bean `mpCompletion(MatchService)`。

- [ ] **Step 1: 寫失敗的測試**

`MatchRoomTest`：`room.raw(" host Alexlin86 ")` → `"msg #mp_<id> !mp host Alexlin86"`；`raw(" ")` 丟 `IllegalArgumentException`；關房後 `raw("host x")` 丟 `IllegalStateException`。
`MatchCommandsTest`：
- `mp(List.of("host", "Alexlin86"), 42L, null)` → `"已送出：!mp host Alexlin86"`
- `mp(List.of(), 42L, null)` → `"錯誤：請輸入 !mp 之後的指令，例如 mp host Alexlin86"`，不送
- `mp(List.of("make", "x"), 42L, null)` 與 `mp(List.of("MakePrivate", "x"), 42L, null)` → `"錯誤：開房請用 make 指令"`，不送
- `mp(List.of("password", "secret"), 42L, null)` → `"已送出：!mp password ***"`，回傳值不含 `secret`，送出 `"msg #mp_42 !mp password secret"`
`ShellCommandParsingTest`：`mp host "-SH-" --room 42` → `"msg #mp_42 !mp host -SH-"`；`mp kick Mr Tester --room 42` → `"msg #mp_42 !mp kick Mr Tester"`。
`ShellCompletionsTest`：`typing("mp", "h")` → `containsExactly("host")`；`typing("mp", "host", "")` → 空（第二個字以後不補）；`--room` 照常補房號。

- [ ] **Step 2: 執行，確認失敗**

Run: `./mvnw test -Dtest='MatchRoomTest,MatchCommandsTest,ShellCommandParsingTest,ShellCompletionsTest'`
Expected: 編譯失敗（`raw`、`mp`、`mpCompletion` 不存在）。

- [ ] **Step 3: 實作**

`mp` 用 `InputGuard.join(words)` 組字串，guard 上限 `ANY`、允許 `room`。`description`：`轉送任意 !mp 指令到房間（例如 mp host 玩家）；以 - 開頭的字請用雙引號包起來`。CLAUDE.md shell 段落的指令列表加入 `mp <指令…>`，補全清單加 `mpCompletion`。

- [ ] **Step 4: 執行，確認通過** — 同 Step 2 指令 PASS；`./mvnw test` 全部通過。

- [ ] **Step 5: Commit** — `feat: mp 指令轉送任意 !mp 子指令`

---

### Task 5: `-` 開頭名稱的跳脫提示

**Files:**
- Modify: `src/main/java/idv/alexlin7/tw/bancho/shell/InputGuard.java`
- Modify: `src/main/java/idv/alexlin7/tw/bancho/shell/MatchCommands.java`（invite、addref）
- Modify: `src/main/java/idv/alexlin7/tw/bancho/shell/ShellCompletions.java`（userCompletion）
- Test: `ShellCommandParsingTest.java`、`ShellCompletionsTest.java`、`MatchCommandsTest.java`

**Interfaces:**
- Produces: `InputGuard.DASH_HINT`（`static final String`，下面的文字）；`InputGuard.ESCAPE_HELP`（`help` 用的多行文字）；`static String rejectRoomAfterDoubleDash(List<String> words)`：任何字等於 `--room` 或以 `--room=` 開頭時回 `"錯誤：-- 之後不能再加 --room 或 --room=…；要指定房間請用雙引號，例如 addref \"-SH-\" --room 42"`，否則 null。

固定文字：
- `DASH_HINT` = `（以 - 開頭的文字會被當成選項：用雙引號包起來，例如 "-SH-"；或緊接在指令後面加 --，例如 addref -- -SH-）`
- `ESCAPE_HELP` =
  ```
  名稱以 - 開頭（例如 -SH-）會被當成選項，需要跳脫：
    用雙引號包起來：addref "-SH-"
    或緊接在指令後面加 --：addref -- -SH-（-- 之後的字全部當成名稱，不能再加 --room 或 --room=…）
    要指定房間時用雙引號：addref "-SH-" --room 42
    單引號沒有作用：'-SH-' 會連引號一起送出
  看到「Option '-SH-' requires a value」就是這個原因。
  ```

- [ ] **Step 1: 驗證 JLine 是否會跳脫補全值裡的引號**

（`--` 的行為已在規劃時以 `CommandParser` 實測：`addref -- -SH-` → `[-SH-]`；`addref -- -SH- --room 42` → `[-SH-, --room, 42]`；`addref --room 42 -- -SH-` → `IllegalArgumentException: Option '-SH-' requires a value`。`--` 只有緊接在指令後面才有效。）

讀 `spring-shell-jline` 4.0.3 把 `CompletionProposal` 轉成 JLine `Candidate` 的程式（`find ~/.m2 -name "spring-shell-jline-4.0.3*.jar"`，用 `javap -c` 或 sources jar），確認 `dontQuote(true)` 時 value 是否原樣交給 JLine、`complete` 旗標的作用。若 value 中的 `"` 會被跳脫，則**不做**補全帶引號，刪掉 Step 2 的對應測試並記 Ruling。

- [ ] **Step 2: 寫失敗的測試**

`ShellCommandParsingTest`：
- `quotedDashNameIsSent`：`addref "-SH-" --room 42` → `"msg #mp_42 !mp addref -SH-"`；`invite "-SH-" --room 42` → `"msg #mp_42 !mp invite -SH-"`
- `doubleDashEscapesNameInCurrentRoom`：`session` 先 `use(42)`，`addref -- -SH-` → `"msg #mp_42 !mp addref -SH-"`
- `roomAfterDoubleDashIsRejected`：`session` 先 `use(7)`，`addref -- -SH- --room 42` → 回傳含 `addref "-SH-" --room 42`，`sender.calls` 為空（invite 同樣）。目前房間與輸入的房號刻意不同，確認不會誤送到目前房間。
- `roomEqualsAfterDoubleDashIsRejected`：`session` 先 `use(7)`，`addref -- -SH- --room=42` → 同樣回錯誤、提示雙引號寫法，`sender.calls` 為空；`invite -- -SH- --room=42` 同樣拒絕。另測 `--room=`（空值）也被拒絕，不得當成名稱送出。
- `unknownOptionMentionsBothEscapes`：`say score was -5 lol --room 42` 的回傳含 `"-SH-"` 與 `-- -SH-`
- `helpExplainsEscaping`：`run("help addref")` 與 `run("help invite")` 的輸出含 `addref "-SH-"` 與 `addref -- -SH-`；若 `help <指令>` 不顯示 `help` 屬性，改從 `registry` 取該 `Command` 檢查其 help 文字，並記 Ruling。
`ShellCompletionsTest`：`dashNameIsProposedQuoted`：`knownUsers` 加入 `-SH-`（經由它既有的事件入口，例如 `PlayerJoined`），`typing("addref", "")` 的 proposals 中有 value `"\"-SH-\""` 且 `dontQuote()` 為 true；`typing("addref", "\"-S")` 也補得出來（前綴比對忽略開頭的 `"`）。

- [ ] **Step 3: 執行，確認失敗**

Run: `./mvnw test -Dtest='ShellCommandParsingTest,ShellCompletionsTest'`
Expected: `roomAfterDoubleDashIsRejected`、`roomEqualsAfterDoubleDashIsRejected`、`unknownOptionMentionsBothEscapes`、`helpExplainsEscaping`、`dashNameIsProposedQuoted` FAIL；`quotedDashNameIsSent`、`doubleDashEscapesNameInCurrentRoom` 可能已 PASS（屬於現有行為的回歸測試）。

- [ ] **Step 4: 實作**

`InputGuard.check` 的未知選項訊息改為 `"錯誤：不認得的選項：" + display(option) + DASH_HINT`。`invite`、`addref` 的 `@Command` 加 `help = InputGuard.ESCAPE_HELP`（需為編譯期常數），`description` 結尾加 `；名稱以 - 開頭時見 help <指令>`，並在送出前呼叫 `rejectRoomAfterDoubleDash`；其判斷須同時涵蓋 `word.equals("--room")` 與 `word.startsWith("--room=")`。`userCompletion` 對 `-` 開頭的名稱產生 `new CompletionProposal("\"" + name + "\"").dontQuote(true)`；`filter` 比對前把 proposal value 與目前的字開頭的 `"` 去掉。`ChatCommands.QUOTE_HINT` 若與 `DASH_HINT` 重複，改用 `DASH_HINT`。CLAUDE.md shell 段落「以 `-` 開頭的聊天文字要用雙引號包起來」擴充為兩種跳脫方式，並註明 `--` 只在緊接指令時有效、之後不能再加選項（含 `--room=…`），指定房間時要用雙引號。

- [ ] **Step 5: 執行，確認通過** — 同 Step 3 指令 PASS；`./mvnw test` 全部通過（`chatWordStartingWithDashIsReportedNotDropped` 仍只檢查 `不認得的選項：-5`，應不受影響）。

- [ ] **Step 6: Commit** — `feat: 以 - 開頭的名稱提示雙引號與 -- 兩種跳脫方式`

---

### Task 6: 終端機只顯示 WARN，完整 log 寫檔

**Files:**
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/idv/alexlin7/tw/bancho/observability/LoggingPropertiesTest.java`（新）
- Modify: `CLAUDE.md`

（`.gitignore` 已有 `logs/` 與 `*.log`，不用改。）

- [ ] **Step 1: 寫失敗的測試**

```java
class LoggingPropertiesTest {

    /** 測試 classpath 上的 application.properties 會遮蔽 main 的同名檔，所以直接讀原始檔。 */
    private static MockEnvironment mainProperties(Map<String, Object> env) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
            properties.load(reader);
        }
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("main", properties));
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        return environment;
    }

    @Test
    void consoleShowsOnlyWarnings() throws IOException {
        assertThat(mainProperties(Map.of()).getProperty("logging.threshold.console")).isEqualTo("WARN");
    }

    @Test
    void logFileDefaultsToLogsDirectory() throws IOException {
        assertThat(mainProperties(Map.of()).getProperty("logging.file.name")).isEqualTo("logs/bancho.log");
    }

    @Test
    void logFileCanBeSetByEnvironmentVariable() throws IOException {
        assertThat(mainProperties(Map.of("BANCHO_LOG_FILE", "D:/bancho/bancho.log")).getProperty("logging.file.name"))
                .isEqualTo("D:/bancho/bancho.log");
    }
}
```
（`env` 放在最前面會讓 `logging.file.name` 的查詢先看 env；它不含該鍵，所以會落到 `main` 並解析 placeholder。）

- [ ] **Step 2: 執行，確認失敗**

Run: `./mvnw test -Dtest=LoggingPropertiesTest`
Expected: FAIL（兩個屬性為 null）。

- [ ] **Step 3: 實作**

`application.properties` 加：
```properties
# 互動 shell 開著時，INFO log 會插進輸入行；終端機只顯示 WARN 以上，完整 log 寫到檔案（與 otel profile 的 OTel）
logging.threshold.console=WARN
# 完整 log（INFO 以上）的檔案位置；相對路徑以啟動時的工作目錄為準
logging.file.name=${BANCHO_LOG_FILE:logs/bancho.log}
```
CLAUDE.md「指令」段落加：log 檔位置與 `BANCHO_LOG_FILE`、`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE`（預設 10MB）、`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY`（預設 7 天）；`spring.main.keep-alive` 那段的「日後不開 shell 的執行方式」補一句：那時也要把 `logging.threshold.console` 改回 INFO。

- [ ] **Step 4: 執行，確認通過** — `./mvnw test -Dtest=LoggingPropertiesTest` PASS；`./mvnw test` 全部通過。另以非互動指令實跑一次（規劃時已驗證：把設定放命令列參數會被當成 shell 指令而 `CommandNotFoundException`、exit 1；只給 `rooms` 而不關互動模式時參數會被忽略）：
  ```bash
  ./mvnw -q package -DskipTests
  review_run_dir=$(mktemp -d)
  review_log_file="$review_run_dir/bancho.log"
  # Windows 的 Java 需要可辨識的絕對路徑；Git Bash 下轉為 Windows 路徑。
  if command -v cygpath >/dev/null 2>&1; then
    review_log_file=$(cygpath -m "$review_log_file")
  fi
  SPRING_SHELL_INTERACTIVE_ENABLED=false OSU_IRC_AUTO_START=false OSU_IRC_USERNAME=x OSU_IRC_PASSWORD=x \
    BANCHO_LOG_FILE="$review_log_file" \
    java -jar target/bancho-0.0.1-SNAPSHOT.jar rooms > "$review_run_dir/console.txt" 2>&1
  review_exit=$?
  printf 'exit=%s\n驗證輸出目錄：%s\n' "$review_exit" "$review_run_dir"
  grep -c " INFO " "$review_run_dir/console.txt"
  grep -c " INFO " "$review_run_dir/bancho.log"
  ```
  Expected：`exit=0`、暫存目錄的 `console.txt` 含 `目前沒有開著的房間` 且 INFO 行數為 0、同一目錄的 `bancho.log` 的 INFO 行數大於 0。`grep -c` 沒有符合行時會印出 `0` 並回傳 1，這是 console 檢查的預期結果。每次驗證使用新的暫存目錄，保留輸出供檢查；不刪除或覆寫專案既有的 `logs/`。預設 `logs/bancho.log` 路徑由 `logFileDefaultsToLogsDirectory` 測試確認。

- [ ] **Step 5: Commit** — `feat: 終端機只顯示 WARN，完整 log 寫到 logs/bancho.log`

---

## 完成後

- spec（2026-10-17）狀態改為「已實作」，第 8 節填入 Task 5 Step 1 的驗證結果。
- 請使用者在真正的終端機實測：`map 2453358 3` 的 BanchoBot 回覆、`addref "-SH-" --room 42` 與 `addref --help`、`logs/bancho.log` 內容，以及一場正常打完的比賽訊息。名稱補全依 Task 5 備案不自動加引號。
