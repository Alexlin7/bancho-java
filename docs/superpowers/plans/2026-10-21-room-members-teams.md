# 房間成員與隊伍追蹤 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正 addref 多目標、正確追蹤 TeamVs 房的成員／隊伍／host，並在 shell 加上 `team`、`players`、host 成員補全與 `set` 分段輸入。

**Architecture:** `bancho-match` 新增 `Team` enum、三種事件變化（`PlayerJoined` 帶隊伍、`PlayerTeamChanged`、`HostChanged`）與 `SettingsChanged` 的部分更新，`MatchState` 的 `Slot` 帶隊伍、整間房帶 host。`bancho-shell` 讀 `MatchState` 呈現名單與補全，`set` 透過新的 `LinePrompt`（能分辨空白 Enter 與取消）逐項提示。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring Shell、JLine、JUnit 5、AssertJ、Maven wrapper。

**Spec:** `docs/superpowers/specs/2026-10-21-room-members-teams-design.md`

## Global Constraints

- `bancho-match` 純 Java，compile 依賴只有 `bancho-irc` 與 `slf4j-api`，不可加入 Spring。
- 每條 parser regex 必須比對整行；沒有真實樣本的格式不寫規則。
- 程式註解、log、使用者看到的訊息一律繁體中文；log 用 `@Slf4j`，前綴 `[match]`／`[shell]`。
- 測試不連真的 Bancho；不用 `Thread.sleep`。
- 修改模組前先讀該模組 `AGENTS.md`；行為改變要更新該模組 `AGENTS.md`。
- 在新分支 `feature/room-members-teams` 上工作，完成後以 rebase + fast-forward 合回 `main`。
- 驗證指令：`./mvnw -pl bancho-match test`、`./mvnw -pl bancho-shell -am test -Dsurefire.failIfNoSpecifiedTests=false`，最後 `./mvnw test`。

## Review Focus

1. 名稱含空白的玩家換隊／進房（`- S H - joined in slot 2 for team red.`、`Tomari Mari changed to Blue`）要取出完整名稱 → Task 2 parser 測試含空白名稱。
2. `joined in slot N.`（h2h）與 `joined in slot N for team X.` 兩條規則互不吞掉；`X joined in slot 1 for team blue.` 不可產生名為 `X joined in slot 1 for team blue` 之類的錯誤狀態 → Task 2。
3. `--room` 指定其他房間時，host／team 補全要列**那間房**的成員，不是目前房間 → Task 4 測試。
4. `set` 分段時目前設定只有一部分已知（只收到 `Changed match settings to TagTeamVs`，人數與計分未知）→ 已知項可 Enter、未知項 Enter 取消 → Task 6 測試。
5. 換 slot 不可遺失隊伍（`moved to slot` 只改 slot）→ Task 2 `MatchState` 測試。

---

### Task 1: addref 每位目標各送一條

**Files:**
- Modify: `bancho-match/src/main/java/cc/alexlin7/bancho/match/MatchRoom.java`（`addRef`、`addRefCommand`、`presetCommands`）
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/MatchCommands.java`（`addref`）
- Modify: `bancho-match/AGENTS.md`、`bancho-shell/AGENTS.md`（addref 說明）
- Test: `bancho-match/src/test/java/cc/alexlin7/bancho/match/MatchRoomTest.java`、`MatchRoomPresetTest.java`、`bancho-shell/src/test/java/cc/alexlin7/bancho/shell/MatchCommandsTest.java`

**Interfaces:**
- Produces: `public List<String> MatchRoom.addRef(String... usernames)`，回傳實際入列的指令，順序與輸入相同。

- [ ] **Step 1: 開分支** `git switch -c feature/room-members-teams`

- [ ] **Step 2: 寫失敗測試**

```java
// MatchRoomTest
@Test
void addRefSendsOneCommandPerTarget() {
    assertThat(room.addRef("Saru86", "#3538446", "https://osu.ppy.sh/users/2548278"))
            .containsExactly("!mp addref Saru86", "!mp addref #3538446", "!mp addref #2548278");
    assertThat(sender.calls).containsExactly("msg #mp_42 !mp addref Saru86",
            "msg #mp_42 !mp addref #3538446", "msg #mp_42 !mp addref #2548278");
}

@Test
void addRefRejectsAnyInvalidTargetBeforeSending() {
    assertThatThrownBy(() -> room.addRef("Saru86", "Tomari Mari"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("#ID");
    assertThat(sender.calls).isEmpty();
}

// MatchRoomPresetTest：範本 referees = [A, #2] 送出兩條 "!mp addref A"、"!mp addref #2"，仍排在 map 之後、invite 之前

// MatchCommandsTest
assertThat(commands.addref(List.of("A", "#2"), 42L, null))
        .isEqualTo("已送出：!mp addref A" + System.lineSeparator() + "已送出：!mp addref #2");
```

（測試變數名依各測試檔現有寫法調整。）

- [ ] **Step 3: 跑測試確認失敗** `./mvnw -pl bancho-shell -am test -Dtest='MatchRoomTest,MatchRoomPresetTest,MatchCommandsTest' -Dsurefire.failIfNoSpecifiedTests=false` → 編譯失敗或斷言失敗。

- [ ] **Step 4: 實作**
  - `addRefCommand` 改為 `private static List<String> addRefCommands(String... usernames)`：每位目標一條 `!mp addref <PlayerTarget.commandArgument(x)>`；空陣列與含空白名稱的錯誤訊息不變；全部檢查完才回傳。
  - `addRef` 先取得整份清單再逐條 `command(...)`。`presetCommands` 用 `addAll`。
  - shell `addref`：每條前面加 `已送出：`，以 `System.lineSeparator()` 連接。
  - 更新兩個 `AGENTS.md`：`addref` 一次可給多位，每位各送一條（BanchoBot 一次只收一位，多位寫同一行會回 `User not found: A B`）。

- [ ] **Step 5: 跑測試確認通過**（同 Step 3 指令）→ PASS

- [ ] **Step 6: Commit** `git commit -am "fix(match): addref 每位目標各送一條指令"`

---

### Task 2: 隊伍、host 與部分設定的解析與狀態

**Files:**
- Create: `bancho-match/src/main/java/cc/alexlin7/bancho/match/Team.java`
- Modify: `bancho-match/src/main/java/cc/alexlin7/bancho/match/event/MatchEvent.java`
- Modify: `bancho-match/src/main/java/cc/alexlin7/bancho/match/BanchoBotParser.java`
- Modify: `bancho-match/src/main/java/cc/alexlin7/bancho/match/MatchState.java`
- Modify: `bancho-match/src/main/java/cc/alexlin7/bancho/match/TeamMode.java`
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/KnownUsers.java`（`HostChanged`、`PlayerTeamChanged` 的名稱也收）
- Test: `BanchoBotParserTest.java`、`MatchStateTest.java`、`bancho-shell/.../KnownUsersTest.java`

**Interfaces:**
- Produces:
  - `public enum Team { BLUE, RED; public static Optional<Team> parse(String text) }`：不分大小寫，只接受 `blue`／`red`。
  - `public boolean TeamMode.hasTeams()`：`TEAM_VS`、`TAG_TEAM_VS` 為 true。
  - `record PlayerJoined(long matchId, String username, int slot, Team team)`：h2h 進房時 `team` 為 null；另保留三參數建構子（`team` = null），現有測試的 `new PlayerJoined(1, "A", 1)` 不用改。
  - `record PlayerTeamChanged(long matchId, String username, Team team)`
  - `record HostChanged(long matchId, String username)`：`{user} became the host.` 與 `Changed match host to {user}` 都產生它。
  - `record SettingsChanged(long matchId, Integer size, String teamMode, String winCondition)`：`size`、`winCondition` 可為 null（只改隊伍模式時）。
  - `record MatchState.Slot(String username, Long userId, String status, Team team)`，另保留三參數建構子（`team` = null）讓現有呼叫不變。
  - `MatchState` 新增元件 `String host`（最後一個元件；未知為 null）。

- [ ] **Step 1: 寫失敗的 parser 測試**（`BanchoBotParserTest`，比照現有寫法以頻道 `#mp_42` 建 `IrcMessage`）

| 輸入 | 期望事件 |
|---|---|
| `Alexlin86 joined in slot 1 for team blue.` | `PlayerJoined(42, "Alexlin86", 1, BLUE)` |
| `- S H - joined in slot 2 for team red.` | `PlayerJoined(42, "- S H -", 2, RED)` |
| `Alexlin86 joined in slot 1.` | `PlayerJoined(42, "Alexlin86", 1, null)` |
| `Alexlin86 changed to Red` | `PlayerTeamChanged(42, "Alexlin86", RED)` |
| `Tomari Mari changed to Blue` | `PlayerTeamChanged(42, "Tomari Mari", BLUE)` |
| `- S H - became the host.` | `HostChanged(42, "- S H -")` |
| `Changed match host to - S H -` | `HostChanged(42, "- S H -")` |
| `Changed match settings to TagTeamVs` | `SettingsChanged(42, null, "TagTeamVs", null)` |
| `Changed match settings to 6 slots, TagTeamVs, ScoreV2` | `SettingsChanged(42, 6, "TagTeamVs", "ScoreV2")`（不變） |
| `Alexlin86 joined in slot 1 for team green.` | `Unrecognized`（不認得的顏色） |

- [ ] **Step 2: 寫失敗的狀態測試**（`MatchStateTest`）
  - `joinedWithTeamRecordsTeam`：`PlayerJoined(…, "A", 1, BLUE)` → `slots().get(1).team() == BLUE`。
  - `movedKeepsTeamAndDetails`：先 `SlotStatus(42, 1, "Not Ready", 7L, "A")`、`PlayerTeamChanged(…, "A", RED)`，再 `PlayerMoved(…, "A", 3)` → `slots() == Map.of(3, new Slot("A", 7L, "Not Ready", RED))`。
  - `teamChangedUpdatesPlayerSlot`；`teamChangedForUnknownPlayerIsIgnored`（狀態 `isEqualTo` 原狀態）。
  - `hostChangedRecordsHost`；`hostLeavingClearsHost`（A 為 host，`PlayerLeft(A)` → `host()` null）；`otherPlayerLeavingKeepsHost`。
  - `partialSettingsKeepsKnownFields`：先 `SettingsChanged(42, 4, "TeamVs", "ScoreV2")`，再 `SettingsChanged(42, null, "TagTeamVs", null)` → `Settings(4, "TagTeamVs", "ScoreV2")`。
  - `switchingToNonTeamModeClearsTeams`：A 在 slot 1 為 BLUE，`SettingsChanged(42, 2, "HeadToHead", "ScoreV2")` → team 為 null；`switchingToTeamModeKeepsUnknownTeams`：h2h 下的 A 改成 `TeamVs` 後 team 仍為 null。

- [ ] **Step 3: 跑測試確認失敗** `./mvnw -pl bancho-match test -Dtest='BanchoBotParserTest,MatchStateTest'` → 編譯失敗。

- [ ] **Step 4: 實作**
  - `Team`、`TeamMode.hasTeams()` 依 Interfaces。
  - `MatchEvent`：如 Interfaces 修改／新增；把 class javadoc 的「slot 狀態維持原文字串」補一句：隊伍轉成 `Team`，因為前端要依它分組。
  - Parser：在 `CHANNEL_RULES` 中，帶隊伍的進房規則 `(.+?) joined in slot (\d+) for team (blue|red)\.`（`CASE_INSENSITIVE` 只套在顏色也可，以 `Team.parse` 轉換）放在原進房規則之前；`(.+?) changed to (Red|Blue)`；`(.+?) became the host\.`；`Changed match host to (.+)`；`Changed match settings to (\S+)` 放在三段式規則之後。原本的 `(.+?) joined in slot (\d+)\.` 因為比對整行，不會吃到帶隊伍的行。
  - `MatchState.apply`：
    - `PlayerJoined` → `withPlayerAt(slot, new Slot(name, null, null, team))`。
    - `PlayerMoved` → 取出該玩家原本的 `Slot`（找不到就用只有名稱的新 Slot），放到新 slot。
    - `SlotStatus` → 保留原本的 team（目前 slot 行不帶隊伍）。
    - `PlayerTeamChanged` → 找到該名稱的 slot 更新 team，找不到回傳 `this`。
    - `HostChanged` → 設 host。`PlayerLeft` → 名稱等於 host 時 host 改 null。
    - `SettingsChanged` → null 欄位沿用舊值；新 teamMode 經 `TeamMode.parse` 為非隊伍模式時清掉所有 slot 的 team。
  - `KnownUsers`：`HostChanged`、`PlayerTeamChanged` 的名稱也 `add`。

- [ ] **Step 5: 跑測試確認通過** `./mvnw -pl bancho-shell -am test -Dsurefire.failIfNoSpecifiedTests=false` → 全部 PASS（`MatchReplayIntegrationTest` 舊樣本的 `Slot` 比較用三參數建構子仍相等）。

- [ ] **Step 6: Commit** `git commit -am "feat(match): 追蹤隊伍、host 與部分房間設定"`

---

### Task 3: 匯入 10-19、10-21 樣本並回放

**Files:**
- Create: `bancho-match/src/test/resources/banchobot/session-2026-10-19.irc`
- Create: `bancho-match/src/test/resources/banchobot/session-2026-10-21.irc`
- Modify: `bancho-match/src/test/java/cc/alexlin7/bancho/match/MatchReplayIntegrationTest.java`
- Modify: `bancho-match/AGENTS.md`

**Interfaces:**
- Consumes: Task 2 的 `Team`、`HostChanged`、`PlayerTeamChanged`、`MatchState.host()`、四參數 `Slot`。

- [ ] **Step 1: 建立樣本檔**
  - 格式比照 `session-2026-10-17b.irc`：開頭 `#` 註解說明來源與日期，只放 `:BanchoBot!cho@ppy.sh PRIVMSG …` 行，原順序、保留行尾空白；私訊對象 `PRIVMSG Alexlin86` 改成 `PRIVMSG test_user`，訊息內容裡的玩家名稱不改。
  - `session-2026-10-19.irc`：`logs/samples-2026-10-19.irc` 中所有 BanchoBot 行（房間 `122038093`，`test12345`）。
  - `session-2026-10-21.irc`：`logs/samples-2026-10-21.irc` 中 `00:20:04` 之後的 BanchoBot 行（房間 `122042973`，`test123`）。

- [ ] **Step 2: 寫回放測試**

```java
@Test
void replaysHostChangesAndPartialSettings() throws Exception {
    replay("/banchobot/session-2026-10-19.irc", "JOIN #mp_122038093", (server, service, events) -> {
        assertThat(server.awaitLine("PART #mp_122038093", WAIT)).isNotNull();
        MatchState state = service.room(122038093).orElseThrow().state();
        assertThat(state.host()).isEqualTo("- S H -");
        assertThat(state.settings()).isEqualTo(new Settings(2, "TeamVs", "ScoreV2"));
        assertThat(state.slots()).isEqualTo(Map.of(1, new Slot("Alexlin86", null, null),
                2, new Slot("- S H -", null, null)));
        assertThat(events).contains(new SettingsChanged(122038093, null, "TagTeamVs", null));
    });
}

@Test
void replaysTeamChanges(CapturedOutput output) throws Exception {
    replay("/banchobot/session-2026-10-21.irc", "JOIN #mp_122042973", (server, service, events) -> {
        assertThat(server.awaitLine("PART #mp_122042973", WAIT)).isNotNull();
        MatchState state = service.room(122042973).orElseThrow().state();
        assertThat(state.settings()).isEqualTo(new Settings(6, "TagTeamVs", "ScoreV2"));
        assertThat(state.slots()).isEqualTo(Map.of(5, new Slot("Alexlin86", null, null, Team.RED)));
        assertThat(events).contains(new PlayerJoined(122042973, "Alexlin86", 1, Team.BLUE));
    });
    assertThat(output).doesNotContain("未辨識的 BanchoBot 訊息");
}
```

10-19 樣本仍有本次範圍外的未辨識行（`User not found`、`Match starts in …`、`Beatmap changed to: …`、`Host is changing map...` 等），所以該測試不檢查未辨識數量。

- [ ] **Step 3: 跑測試** `./mvnw -pl bancho-match test -Dtest=MatchReplayIntegrationTest` → PASS（若失敗，修 Task 2 的規則，不要改樣本）。

- [ ] **Step 4: 更新 `bancho-match/AGENTS.md`**：`MatchEvent` 段落補「隊伍轉成 `Team`」；`MatchState` 段落補 `Slot.team`、`host` 與 Task 2 的狀態規則（換 slot 保留隊伍、host 離開清空、部分設定沿用、改成非隊伍模式清隊伍、改成隊伍模式不猜隊伍）；`!mp settings` 的隊伍模式 slot 行仍無樣本。

- [ ] **Step 5: Commit** `git commit -am "test(match): 匯入 10-19、10-21 樣本並回放隊伍與 host"`

---

### Task 4: `team` 指令與房間成員補全

**Files:**
- Modify: `bancho-match/src/main/java/cc/alexlin7/bancho/match/MatchRoom.java`（`team`）
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/MatchCommands.java`（`team`；`host` 改用 `memberCompletion`）
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/ShellCompletions.java`
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/InputGuard.java`（`TEAM_HELP`）
- Test: `MatchRoomTest.java`、`MatchCommandsTest.java`、`ShellCompletionsTest.java`

**Interfaces:**
- Consumes: `Team`、`MatchState.slots()`、`Slot.team()`、`MatchState.host()`。
- Produces:
  - `public String MatchRoom.team(String player, Team team)` → `!mp team <PlayerTarget.commandArgument(player)> <blue|red>`（顏色小寫）。
  - `CompletionProvider memberCompletion(MatchService, ShellSession)`、`CompletionProvider teamCompletion(MatchService, ShellSession)`。
  - `static String ShellCompletions.memberDescription(int slot, Slot member, String host)`：`藍 · slot 1`、`紅 · slot 3`、`slot 2`，是 host 時再加 ` · host`（`players` 也可共用隊伍中文名）。

- [ ] **Step 1: 寫失敗測試**
  - `MatchRoomTest.teamSendsColourAfterTarget`：`room.team("- S H -", Team.RED)` 回傳並送出 `!mp team - S H - red`；`room.team("https://osu.ppy.sh/users/2548278", Team.BLUE)` → `!mp team #2548278 blue`。
  - `MatchCommandsTest`：
    - `commands.team(List.of("blue", "Tomari", "Mari"), 42L, null)` → `已送出：!mp team Tomari Mari blue`。
    - `commands.team(List.of("green", "A"), 42L, null)` → `錯誤：用法：team <blue|red> <玩家目標…> [--room <房號>]`，不送出；`commands.team(List.of("red"), 42L, null)` 同一用法錯誤。
    - 未登入時回 `錯誤：請先 login`。
  - `ShellCompletionsTest`：
    - 房 42 有 `PlayerJoined(42,"A",1,BLUE)`、`PlayerJoined(42,"B",3,RED)`、`PlayerJoined(42,"C",2,null)`、`HostChanged(42,"A")`，目前房間 42：`host` 補全值為 `A`、`B`、`C`（依 slot 排序），說明依序 `藍 · slot 1 · host`、`slot 2`、`紅 · slot 3`。
    - `--room 43` 時列房 43 的成員（Review Focus 3）：`typing("host", "--room", "43", "")`。
    - 沒有目前房間或房間沒有成員 → 空清單。
    - `team` 補全：第 0 位置 `blue`、`red`；第 1 位置之後為房間成員。

- [ ] **Step 2: 跑測試確認失敗** `./mvnw -pl bancho-shell -am test -Dtest='MatchRoomTest,MatchCommandsTest,ShellCompletionsTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL

- [ ] **Step 3: 實作**
  - `MatchRoom.team` 依 Interfaces。
  - `MatchCommands.team(@Arguments List<String> words, @Option room, CommandContext)`：`@Command(name = "team", completionProvider = "teamCompletion", description = "把玩家換到藍隊或紅隊；用法見 team --help", help = InputGuard.TEAM_HELP)`；先 `InputGuard.rejectRoomAfterDoubleDash(words, "team")`，第一個字以 `Team.parse` 解析，失敗或沒有玩家時回 `guardedUsage(context, InputGuard.ANY, TEAM_USAGE)`，其餘 `InputGuard.join(words.subList(1, …))` 交給 `r.team(...)`。
  - `TEAM_HELP` 說明兩個例子：`team blue Tomari Mari`、`team red #2548278`，以及名稱以 `-` 開頭時的雙引號寫法（比照 `HOST_HELP`）。
  - `host` 的 `completionProvider` 改成 `memberCompletion`；`invite`、`addref` 不變。
  - `ShellCompletions`：目標房間 = 命令字中 `--room` 後的數字，否則 `session.currentRoom()`（`Optional<Long>`）；以 `matchService.room(id)` 取狀態，依 slot 排序產生候選。
  - 更新 `bancho-shell/AGENTS.md` 指令清單與補全段落。

- [ ] **Step 4: 跑測試確認通過**（同 Step 2）→ PASS

- [ ] **Step 5: Commit** `git commit -am "feat(shell): team 換隊指令與房間成員補全"`

---

### Task 5: `players` 兩隊名單

**Files:**
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/MatchCommands.java`
- Test: `MatchCommandsTest.java`

**Interfaces:**
- Consumes: `MatchState`（`name`、`settings`、`slots`、`host`）、`TeamMode.parse`／`hasTeams()`、`Team`。
- Produces: `public String MatchCommands.players(@Option room, CommandContext)`，`@Command(name = "players", completionProvider = "roomOptionCompletion", description = "列出房間成員；隊伍模式依藍隊、紅隊分組")`。不送 IRC，所以不經 `LoginGuard`（同 `rooms`），但仍經 `InputGuard.check(context, 0, ROOM)` 與 `rooms.onRoom`。

- [ ] **Step 1: 寫失敗測試**（行以 `System.lineSeparator()` 連接）

```java
@Test
void playersGroupsByTeam() {
    // 房 42：test123、SettingsChanged(42, 4, "TeamVs", "ScoreV2")、
    // A slot1 BLUE、C slot2 BLUE、B slot3 RED、D slot4 無隊伍、HostChanged(42, "A")
    assertThat(commands.players(42L, null)).isEqualTo(String.join(System.lineSeparator(),
            "#mp_42 test123  TeamVs  4 slots  host: A",
            "  藍隊",
            "    [1] A ★",
            "    [2] C",
            "  紅隊",
            "    [3] B",
            "  無隊伍",
            "    [4] D",
            "名單依 BanchoBot 訊息推算，可能與遊戲內不同步"));
}
```

  - `playersInHeadToHeadIsSingleList`：`HeadToHead` 時標題後直接列 `  [1] A ★` 等，沒有分組標題。
  - 未知欄位顯示 `?`（`#mp_42 (未知)  ?  ? slots  host: ?`）；沒有成員時第二行 `  （沒有成員）`；空的組不顯示標題。
  - 未登入（`connection = IDLE`）也能用。

- [ ] **Step 2: 跑測試確認失敗** `./mvnw -pl bancho-shell -am test -Dtest=MatchCommandsTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL

- [ ] **Step 3: 實作 `players`**；把分組與格式放在 private static 方法，`describe` 不變。測試事件以 `matchService.onMatchEvent(...)` 注入（房間先 `matchService.attach(42)`）。更新 `bancho-shell/AGENTS.md` 指令清單（`players` 與 `rooms` 一樣不受 `LoginGuard` 限制）。

- [ ] **Step 4: 跑測試確認通過** → PASS

- [ ] **Step 5: Commit** `git commit -am "feat(shell): players 列出兩隊名單"`

---

### Task 6: `set` 分段輸入

**Files:**
- Create: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/LinePrompt.java`
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/JLinePrompt.java`
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/ShellConfiguration.java`（`linePrompt` bean）
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/MatchCommands.java`（建構子、`set`）
- Modify: `bancho-shell/src/main/java/cc/alexlin7/bancho/shell/InputGuard.java`（`SET_HELP` 補分段說明）
- Modify: `bancho-shell/src/dist/使用說明.txt`、`bancho-shell/AGENTS.md`
- Test: `JLinePromptTest.java`、`MatchCommandsTest.java`

**Interfaces:**
- Consumes: `MatchState.settings()`、`TeamMode.parse`、`ScoreMode.parse`、`MatchRoom.set(int, int, int)`。
- Produces:
  - `@FunctionalInterface public interface LinePrompt { Optional<String> read(String prompt); }`：回傳去掉前後空白的輸入，**空白 Enter 回 `Optional.of("")`**；Ctrl+C、Ctrl+D 回 `Optional.empty()`；非互動模式丟 `IllegalStateException`。
  - `static LinePrompt JLinePrompt.line(LineReader reader)`（不遮蔽、與 `text` 相同的暫時設定）。
  - `MatchCommands` 建構子多一個 `LinePrompt linePrompt` 參數（接在 `SecretPrompt` 之後）。

- [ ] **Step 1: 寫失敗測試**
  - `JLinePromptTest`：`line` 讀到空白行回 `Optional.of("")`；輸入 `teamvs` 回 `Optional.of("teamvs")`；EOF 回 empty。`text`／`secret` 的空白取消行為不變。
  - `MatchCommandsTest`（以 `Deque<Optional<String>>` 依序回答的假 `LinePrompt`，並記下提示文字）：
    - 房 42 已收到 `SettingsChanged(42, 4, "TeamVs", "ScoreV2")`；回答 `"teamvs"`、`""`、`"8"` → 回 `已送出：!mp set 2 3 8`；提示依序為
      `隊伍模式（h2h / tag / teamvs / tagvs，Enter 維持 TeamVs）：`、
      `計分模式（score / accuracy / combo / scorev2，Enter 維持 ScoreV2）：`、
      `人數（1–16，Enter 維持 4）：`。
    - 全部 Enter → `已送出：!mp set 2 3 4`。
    - 只收到 `SettingsChanged(42, null, "TagTeamVs", null)`（Review Focus 4）：隊伍 Enter 沿用 3；計分提示為 `計分模式（score / accuracy / combo / scorev2）：`，回答 `""` → `已取消，房間設定沒有變更`，不送出。
    - 任一步回 `Optional.empty()`（Ctrl+C）→ `已取消，房間設定沒有變更`，不送出，之後不再提示。
    - 回答 `"xyz"` 於隊伍 → `錯誤：不認得的隊伍模式：xyz（可用 h2h、tag、teamvs、tagvs 或 0–3）`，不再提示；人數回答 `"abc"` → `錯誤：人數必須是 1–16 的數字：abc`；`"20"` → 沿用 `MatchRoom.set` 的範圍錯誤。
    - `set teamvs v2 8` 一行寫法不提示；`set teamvs` 只給一個 → 仍回 `SET_USAGE`，不提示。
    - 沒有目前房間時不提示（比照 `passwordWithoutRoomDoesNotPrompt`）。

- [ ] **Step 2: 跑測試確認失敗** `./mvnw -pl bancho-shell -am test -Dtest='JLinePromptTest,MatchCommandsTest' -Dsurefire.failIfNoSpecifiedTests=false` → 編譯失敗

- [ ] **Step 3: 實作**
  - `LinePrompt`、`JLinePrompt.line`、`ShellConfiguration.linePrompt`（非互動模式丟 `IllegalStateException("非互動模式無法提示輸入，請在互動 shell 中使用")`）。
  - `set`：三個位置參數都是 null 時，`InputGuard.check(context, 3, ROOM)`、`loginGuard.check()` 之後以 `rooms.onRoom(room, r -> promptSettings(r))` 逐項提示；否則維持原本流程（任一缺少 → `SET_USAGE`）。目前值：`TeamMode.parse(settings.teamMode())`、`ScoreMode.parse(settings.winCondition())`、`settings.size()`；提示中「Enter 維持」後面顯示 BanchoBot 原文（`TeamVs`、`ScoreV2`）或人數。三個值都取得後呼叫 `r.set(team, score, size)`，回 `SENT + 回傳指令`。
  - `SET_HELP` 加一段：「只打 set 會逐項提示，Enter 沿用目前設定」。
  - `使用說明.txt` 補 `set` 兩種寫法、`team`、`players`；`bancho-shell/AGENTS.md` 補 `LinePrompt` 與 `set` 分段規則。

- [ ] **Step 4: 跑測試確認通過**（同 Step 2）→ PASS

- [ ] **Step 5: 全部測試** `./mvnw test` → BUILD SUCCESS

- [ ] **Step 6: Commit** `git commit -am "feat(shell): set 不帶參數時逐項提示"`
