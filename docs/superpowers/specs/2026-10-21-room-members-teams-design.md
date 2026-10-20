# 房間成員與隊伍追蹤、換隊、host 補全、set 分段輸入、addref 修正 設計

- 日期：2026-10-21
- 狀態：設計中
- 前一份設計：`2026-10-17-shell-refinements-design.md`、`2026-10-16-match-room-design.md`（本文件只寫差異）

## 1. 背景與目的

使用者 2026-10-19 至 10-21 實測（樣本 `logs/samples-2026-10-19.irc`、`10-20.irc`、`10-21.irc`）後提出：

1. 派 host 時不知道房裡有誰、在哪一隊，名字也難打。
2. `set` 的用法 `0~3 0~3 1~16` 看不懂，希望像 `password` 一樣分段提示。

同時從樣本發現：

- **TeamVs／TagTeamVs 房的成員追蹤是壞的**：進房訊息是 `Alexlin86 joined in slot 1 for team blue.`，現有規則 `(.+?) joined in slot (\d+)\.` 比對整行失敗，記成「未辨識」，玩家沒有進到 `MatchState.slots`。`Alexlin86 changed to Red`／`changed to Blue` 也未辨識。
- **addref 多個目標是壞的**：`!mp addref Saru86 #3538446` → `User not found: Saru86 #3538446`；`!mp addref #2548278 #3538446` 也一樣。BanchoBot 一次只收一位。
- `Changed match settings to TagTeamVs`（只改隊伍模式時的回覆，10-19 樣本）沒有規則。
- `became the host`、`Changed match host to …`（10-19 樣本）沒有規則，房間不知道誰是 host。

### 使用情境

- 隊伍**以玩家選的顏色為準**，不要求玩家坐到特定 slot。過去要求定位是為了 osu! 遊戲內的直播排版，目前不打算開直播。
- 裁判在 shell 上主持時，host 通常由裁判當下指定，主要用在暖手圖丟給隊伍；其餘圖由裁判發。全由玩家輪流選圖時，玩家習慣依 slot 順序一棒接一棒，不一定需要裁判派 host。
- 裁判需要能把玩家換到另一隊（BanchoBot `!mp team <玩家> <red|blue>`）。

## 2. 目標、非目標、反目標

### 目標

1. addref 多個目標時每位各送一條 `!mp addref`（shell 指令與範本 `referees` 都是）。
2. `match` 正確追蹤每個 slot 的玩家與隊伍，以及目前的 host。
3. shell 新增換隊指令。
4. `host` 的 Tab 補全只列目前房間的成員，附隊伍與 slot。
5. shell 新增列出兩隊名單的指令。
6. `set` 不帶參數時逐項提示，Enter 沿用目前設定；一行打完的寫法保留。

### 非目標（之後再做）

- ban/pick 偵測、選圖輪次、自動輪流派 host、隊長設定。這些需要圖池與比分的概念，之後一起設計。
- Discord 的呈現。`match` 的成員／隊伍／host 資料保持前端無關，Discord spec 再決定怎麼用（slash 指令的名單補全、embed 列兩隊等）。
- 要求或檢查玩家坐到特定 slot。
- `removeref`、`listrefs`（多行回覆）、`User not found: …`、`You have too many referees!` 不產生事件，維持記「未辨識」INFO log。（我的決定：與成員追蹤無關，加入會擴大範圍。）

### 反目標

- 把附加資訊誤當成玩家名稱（例如把 `Alexlin86 joined in slot 1 for team blue` 整串當成名字）。寧可未辨識，也不要錯誤的狀態。
- 沒有樣本就憑記憶寫 BanchoBot 格式（`bancho-match/AGENTS.md` 的規則）。

## 3. 解析與狀態（`bancho-match`）

### 新規則（都有真實樣本，先把樣本加進 `src/test/resources/banchobot/`）

| 原文 | 事件 |
|---|---|
| `{user} joined in slot {n} for team {blue\|red}.` | `PlayerJoined` 帶隊伍 |
| `{user} changed to {Red\|Blue}` | 新事件：玩家換隊 |
| `{user} became the host.` | 新事件：host 變更 |
| `Changed match host to {user}` | 同上（host 變更） |
| `Changed match settings to {teamMode}` | `SettingsChanged`，只更新隊伍模式 |

- 隊伍原文大小寫不一（`team blue` vs `changed to Red`）。這裡要轉成有限值（藍／紅），因為前端要依它分組；`MatchEvent` 其他欄位「維持原文字串」的慣例不適用於隊伍。（我的決定）
- 注意規則順序與比對整行：`joined in slot (\d+)\.` 與 `joined in slot (\d+) for team (\w+)\.` 要互不吃掉對方；名稱含空白時也要正確。
- `Changed match settings to …` 的其他部分格式（例如只有隊伍與計分、沒有人數）目前沒有樣本，不寫規則。

### `MatchState`

- `Slot` 加上隊伍（未知時為 null）。
- `MatchState` 加上目前 host 的名稱（未知時為 null）。
- `Settings` 允許部分更新：只收到隊伍模式時，保留原本的人數與計分模式。
- `PlayerMoved`：保留該玩家原本的隊伍、userId、status，只改 slot。現在的實作會換成全 null 的 `Slot`，要修。
- 換隊事件：找到該玩家所在的 slot 並更新隊伍；找不到（例如房間是中途 attach、還沒看過他）就忽略。
- `PlayerLeft`：若離開的是 host，host 改為 null。（我的決定：BanchoBot 是否會另外送 host 變更訊息沒有樣本；若之後收到，事件會再覆蓋。）
- 隊伍模式改成非隊伍模式（HeadToHead／TagCoop）時，清掉所有 slot 的隊伍；改成隊伍模式時不猜測各人的隊伍，維持原值或 null：10-19 樣本從 HeadToHead 改成 TeamVs 時房裡有兩位玩家，BanchoBot 沒有送任何 `changed to`。

### `!mp settings` 的 slot 行

隊伍模式房間的 slot 行應該在名稱後面附 `[...]`（隊伍、host、mods），目前沒有樣本，現有規則刻意把它當成未辨識。**本次不寫這條規則**；在 shell 的測試說明裡請測試者在 TeamVs 房、有玩家時送一次 `settings`，取得樣本後再補。（取得前，中途 `attach` 的房間在隊伍模式下只能靠之後的進房／換隊訊息得知隊伍。）

### `MatchRoom`

- `addRef(…)` 每位目標各送一條 `!mp addref`；回傳值改成能表示多條實際入列指令的形式，前端逐條顯示。範本 `referees` 同樣拆開，仍維持「全部檢查通過才開始送」。
- 新增換隊：`!mp team <玩家> <red|blue>`，玩家目標以 `PlayerTarget` 解析（與 `host`、`invite` 一致），回傳實際入列的指令。BanchoBot 對 `!mp team` 的回覆格式沒有樣本：實測時先以樣本檔蒐集，回覆若就是 `changed to …` 則已涵蓋。

## 4. shell（`bancho-shell`）

### 換隊指令：`team`

```
team <blue|red> <玩家目標…> [--room <房號>]
```

- 顏色放在前面，後面全部是玩家目標（名稱可含空白，跟 `host` 一樣一次一位）。（我的決定：顏色放後面會跟含空白的名稱分不出界線，`msg` 曾因此送錯人。）
- 顏色接受 `blue`／`red`，不分大小寫；補全：第一個位置補顏色，之後補目前房間成員。
- 受 `LoginGuard`、`InputGuard` 規則約束，與其他房間指令一致。

### 兩隊名單：`players`

```
> players
#mp_122042973 test123  TeamVs  4 slots  host: Alexlin86
  藍隊
    [1] Alexlin86 ★
    [2] - S H -
  紅隊
    [3] Tomari Mari
  無隊伍
    [4] Saru86
```

- 指令名稱、版面為我的決定，可在實作時微調。★ 表示 host。
- 非隊伍模式（HeadToHead 等）時只依 slot 列一份清單。
- 支援 `--room`。名單是我們追蹤的狀態，可能與實際不同步；結尾提示可用 `settings` 重新整理（等 slot 行規則補上後才完整）。

### `host` 補全

- 只列**目標房間**（目前房間或 `--room` 指定的房間）的成員，說明欄附隊伍與 slot（例如 `藍 · slot 1`）。
- 房間沒有成員資料時回空清單。`invite`、`addref` 仍用 `KnownUsers`。
- 名稱含空白時的引號行為沿用現況（`ShellCompletions` 不自動加引號）。

### `set` 分段輸入

```
> set
隊伍模式（h2h / tag / teamvs / tagvs，Enter 維持 TeamVs）：teamvs
計分模式（score / accuracy / combo / scorev2，Enter 維持 ScoreV2）：
人數（1–16，Enter 維持 4）：8
已送出：!mp set 2 3 8
```

- `set` 不帶任何位置參數時進入分段輸入；帶齊三個參數時維持現行一行寫法。只給一或兩個參數時維持現行的用法錯誤。（我的決定）
- 提示的模式名稱用各 enum 的第一個別名；輸入接受所有別名與數字，錯誤時顯示與一行寫法相同的錯誤並結束（不重問）。
- **Enter 沿用目前值**：目前值取自 `MatchState.settings`（BanchoBot 原文，例如 `TeamVs`），以 `TeamMode.parse`／`ScoreMode.parse` 轉成編號。送出時三個值都明確寫出，不依賴 BanchoBot 的省略規則。
- 某項目前值未知（例如剛 `attach`、還沒收到設定回覆）：該項提示不顯示「Enter 維持」，Enter 視為取消整個 `set`。
- Ctrl+C 一律取消。目前 `TextPrompt.read` 對空白輸入與 Ctrl+C 都回 `Optional.empty()`，無法區分；需要讓 prompt 能分辨「空白 Enter」與「取消」，介面怎麼改由實作者決定（同時維持 `msg` 現有的行為：空白即取消）。
- 房間與登入檢查在提示之前完成，與 `password` 相同。

## 5. 測試與樣本

- 新規則都要先有樣本與 parser 測試；把 10-19、10-21 樣本中相關的行（以及 10-20 的 addref `User not found` 行）匯入 `src/test/resources/banchobot/`（nick 改成 `test_user`），讓 `MatchReplayIntegrationTest` 回放。
- `MatchState` 的狀態轉換（換 slot 保留隊伍、host 離開、部分設定更新、換到 h2h 清隊伍）各有單元測試。
- shell：`team`、`players`、`set` 分段（Enter 沿用、未知值取消、Ctrl+C 取消、一行寫法不變）、`host` 補全只列目標房間成員、addref 拆成多條。
- 待使用者實測蒐集的樣本：TeamVs 房的 `!mp settings` slot 行、`!mp team` 的回覆、host 離開房間時的訊息。

## 6. 文件

- 更新 `bancho-match/AGENTS.md`（新事件、`Slot` 隊伍、host、addref 拆送）與 `bancho-shell/AGENTS.md`（`team`、`players`、`set` 分段、`host` 補全）。
- `bancho-shell/src/dist/使用說明.txt` 補上新指令與 `set` 的兩種寫法。
