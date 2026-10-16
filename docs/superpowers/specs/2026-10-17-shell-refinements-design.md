# Shell 實測後的修正（特殊字元名稱、map 遊玩模式、新 BanchoBot 樣本、mp 轉送、log 輸出）設計

- 日期：2026-10-17
- 狀態：待使用者審閱
- 前一份設計：`2026-10-16-match-room-design.md`（本文件只寫差異）
- 分支：`feature/match-room-phase2`（完成後與階段二一起合回 `main`）

## 1. 背景

使用者 2026-10-16 至 10-17 在真正的終端機實測階段二，發現：

1. 名稱以 `-` 開頭的玩家（例如 `-SH-`）無法直接 `addref -SH-`。
2. `map` 不能指定遊玩模式，無法把 std 圖轉成其他模式。
3. 多則 BanchoBot 回覆沒有規則，被記成「未辨識」，其中 `Disabled all mods` 讓房間狀態留著舊的 mod。
4. 想直接下 `!mp ...` 的指令，但 shell 沒有對應的指令。
5. Spring 的 INFO log 直接寫到終端機，把 `shell:>` 提示切斷。

## 2. 特殊字元名稱

### 實測結果

用 Spring Shell 的 `CommandParser` 實際解析：

| 輸入 | 解析結果 |
|---|---|
| `addref "-SH-"` | 參數 `-SH-` ✅ |
| `addref -- -SH-` | 參數 `-SH-` ✅ |
| `addref '-SH-'` | 參數 `'-SH-'`（單引號留在名稱裡）❌ |
| `invite "-SH-" --room 1` | 參數 `-SH-`、`room=1` ✅ |
| `addref -SH-` | Spring Shell 回 `Option '-SH-' requires a value`，指令不會執行 |

最後一種錯誤發生在 Spring Shell 的 parser，早於我們的指令，`InputGuard` 攔不到。要攔就得替換 Spring Shell 的 `CommandParser`，代價和風險都比問題本身大，所以不做。

### 決定

- `invite`、`addref` 的說明寫清楚兩種跳脫方式與一個不能用的寫法：

  ```
  名稱以 - 開頭（例如 -SH-）會被當成選項，需要跳脫：
    用雙引號包起來：addref "-SH-"
    或在名稱前加 --：addref -- -SH-（-- 之後的字都當成名稱，--room 要寫在 -- 前面）
    單引號沒有作用：'-SH-' 會連引號一起送出
  看到「Option '-SH-' requires a value」就是這個原因。
  ```

- `InputGuard` 拒絕未知選項時的錯誤訊息（例如打成 `addref --SH-` 這類被當成長選項的情況）也改成同樣的兩種寫法。
- CLAUDE.md 的 shell 段落補上同一段說明。
- `userCompletion` 補出以 `-` 開頭的名稱時，候選值本身帶雙引號（`"-SH-"`），Tab 補完就是可以直接執行的寫法。實作時先驗證 JLine 會不會再跳脫引號；會的話就只改說明。
- 補測試：`ShellCommandParsingTest` 加上 `addref "-SH-" --room 42` → `!mp addref -SH-`。

底線、數字開頭等其他字元不受影響。實測時的 `User not found: _AppleCat_` 是 BanchoBot 的回覆，表示 Bancho 上查無此帳號，不是程式問題。

## 3. `map` 的遊玩模式

BanchoBot 的格式是 `!mp map <beatmapId> [<playmode>]`，playmode 為 0 osu!、1 osu!taiko、2 osu!catch、3 osu!mania。std 圖可以轉成其他三種模式；其他模式的圖不能轉成 std。

### 參數設計（建議）

shell：`map <beatmapId> [模式]`，模式同時接受**數字與名稱**，不分大小寫：

| 送出的值 | 可輸入 |
|---|---|
| 0 | `0`、`osu`、`std` |
| 1 | `1`、`taiko` |
| 2 | `2`、`catch`、`fruits`、`ctb` |
| 3 | `3`、`mania` |

- 第二個參數的 Tab 補全列出 `osu`、`taiko`、`catch`、`mania`（描述寫上對應數字）。
- 不給模式時送 `!mp map <id>`，與現在相同（BanchoBot 會用圖本身的模式）。
- 其他值回 `錯誤：不認得的遊玩模式：xxx（可用 osu、taiko、catch、mania 或 0–3）`。

考慮過的其他寫法：

| 寫法 | 不採用的原因 |
|---|---|
| 只接受數字（`map 2453358 3`） | 和 BanchoBot 一致，但要記數字；名稱也接受就涵蓋了這種用法 |
| `--mode mania` 選項 | 要多打字；其他指令的位置參數都是「BanchoBot 指令的參數順序」，維持一致 |

### match 層

- 新增 `match.GameMode` enum：`OSU(0)`、`TAIKO(1)`、`CATCH(2)`、`MANIA(3)`，提供 `int id()` 與 `static Optional<GameMode> parse(String)`（數字或名稱）。
  前一份設計說「BanchoBot 原文不轉 enum」，那是針對 BanchoBot **回覆**中沒看過完整值域的字串；遊玩模式是我們**送出**的參數，值域由 wiki 明確定義，所以用 enum。
- `MatchRoom` 新增 `void map(long beatmapId, GameMode mode)`，送 `!mp map <id> <mode.id()>`；原本的 `map(long)` 保留。
- 房間範本加 `mode` 欄位（`bancho.match.presets.<名稱>.mode`，值同上，例如 `mode: mania`）。只填 `mode` 沒填 `beatmap` 時，啟動時就驗證失敗（與 team-mode 三者必須一起設定的做法相同）。

### 尚未有樣本的部分

指定模式後 BanchoBot 會不會多回一行（例如模式變更的訊息），目前沒有樣本。這次**不**在 `MatchState` 記錄遊玩模式；實作後請實測 `map 2453358 3`，把 BanchoBot 的回覆貼回來，若有新格式再加入解析器。

## 4. 新的 BanchoBot 樣本

2026-10-17 實測的原文存成 `src/test/resources/banchobot/session-2026-10-17.irc`，由 `MatchReplayIntegrationTest` 回放。

| 原文 | 處理 |
|---|---|
| `Disabled all mods, disabled FreeMod` | `ModsChanged(id, [], false)`。regex 寫成 `Disabled all mods, (enabled\|disabled) FreeMod`（enabled 的版本沒有樣本，但與既有的 `Enabled ..., (enabled\|disabled) FreeMod` 同一結構） |
| `The match has started!` | 新事件 `MatchStarted(matchId)` |
| `Aborted the match` | 新事件 `MatchAborted(matchId)` |
| `Started the match` | 略過（`Ignored`）。這是對裁判 `!mp start` 的回覆，與廣播 `The match has started!` 同時出現，只用後者產生事件，避免同一件事出現兩次 |
| `Countdown ends in 10 seconds`、`Countdown ends in 1 second`、`Countdown finished` | 略過 |
| `Changed the match password` | 略過 |
| `Active mods: NoFail, Hidden` | 略過（`!mp settings` 的一行；沒 mod 時的格式與 FreeMod 的寫法都沒有樣本，不據此更新狀態） |
| `User not found: alexlin7` | 維持未辨識（使用者會在畫面上看到原文） |
| `You have too many referees! Remove some to add new ones.` | 維持未辨識 |

`MatchStarted` / `MatchAborted` 這次**不**加進 `MatchState`：比賽正常結束的訊息還沒有樣本，若用「進行中」旗標，正常打完後會一直停在進行中。等蒐集到結束訊息（之後做裁判流程時）再一起加。

`MatchEvent` 的 `permits` 加上兩個新 record；`BanchoBotListener` 的 log 不用改（事件名稱來自 record）。

## 5. `mp` 轉送指令

```
mp <BanchoBot 指令與參數…> [--room <房號>]
```

- 把參數接起來，前面加上 `!mp `，送到目前房間（或 `--room` 指定的房間）。例：`mp host Alexlin86` → `!mp host Alexlin86`。
- 用途是還沒包成專屬指令的 `!mp` 子指令（`host`、`kick`、`move`、`team`、`clearhost`、`aborttimer` 等）。
- 透過 `MatchRoom` 新增的 `void raw(String arguments)` 送出，享有「房間已關閉時拒絕」的檢查。
- 沒有參數時回錯誤。以 `-` 開頭的參數要用雙引號包起來，與 `say` 相同。
- `mp make ...` 回錯誤，請使用者改用 `make`（`!mp make` 的回覆要由 `MatchService` 對應房名，轉送不會切換房間）。
- `mp password ...` 的回覆文字遮成 `!mp password ***`，與 `password` 指令相同。
- 補全：第一個參數列出常用子指令名稱，其餘交給 `--room`。

## 6. log 不再切斷輸入行，完整 log 寫到檔案

- `application.properties` 設定 `logging.threshold.console=WARN`：終端機只顯示 WARN 以上；INFO 照樣送到 OTel（`otel` profile）。
- 沒開 OTel 時 INFO 也不能消失，所以同時寫到檔案，使用 Spring Boot 內建的檔案 log 設定（`logging.file.name`，Logback 的滾動檔案）：

  ```properties
  # 完整 log（INFO 以上）的檔案位置；相對路徑以啟動時的工作目錄為準
  logging.file.name=${BANCHO_LOG_FILE:logs/bancho.log}
  ```

  | 環境變數 | 預設 | 說明 |
  |---|---|---|
  | `BANCHO_LOG_FILE` | `logs/bancho.log` | log 檔的路徑，可用絕對路徑，例如 `D:/bancho/logs/bancho.log` |
  | `LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE` | `10MB`（Boot 預設） | 單檔上限，超過就滾成 `bancho.log.2026-10-17.0.gz` |
  | `LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY` | `7`（Boot 預設） | 保留天數 |

  後兩個是 Boot 本來就認得的設定（環境變數會自動對應到 `logging.logback.rollingpolicy.*`），不另外包裝，只寫進 CLAUDE.md。
- `otel` profile 下檔案照寫，兩邊都有；不想要檔案時把 `BANCHO_LOG_FILE` 設成另一個位置即可，不提供關閉開關（YAGNI）。
- `.gitignore` 加上 `logs/`。
- 房間事件、BanchoBot 原文本來就由 `ShellMessagePrinter` 用 `printAbove` 印出，關掉 INFO 不會少看到東西。
- 測試的 `application.properties` 會遮蔽 main 的設定，測試輸出維持 INFO，不受影響。
- 日後有不開 shell 的執行方式（例如只跑 Discord）時，要在那個執行方式改回 INFO。這點寫進 CLAUDE.md。
- 啟動前的 Spring Boot banner 與 WARN 仍可能和提示交錯，只有在出問題時才會出現，接受。

## 7. 測試

| 對象 | 測試 |
|---|---|
| `GameMode.parse` | 數字、名稱、別名、大小寫、未知值、空字串 |
| `MatchRoom` | `map(id, MANIA)` 送 `!mp map <id> 3`；`raw` 的指令字串與已關閉時拒絕 |
| 房間範本 | `mode` 套用順序（跟著 map 送出）；只有 `mode` 沒有 `beatmap` 時綁定失敗 |
| `BanchoBotParser` | 第 4 節每則樣本對到正確的 `Parsed` / `Ignored` / `Unrecognized` |
| 回放 | `session-2026-10-17.irc` 回放後 mod 清空、slot 與圖正確 |
| `MatchCommands` | `map 2453358 mania`、`map 2453358 3`、未知模式、多餘參數；`mp` 的轉送、空參數、`make` 拒絕、密碼遮蔽 |
| `ShellCommandParsingTest` | `map 2453358 2 --room 42` → `msg #mp_42 !mp map 2453358 2`；`addref "-SH-" --room 42` → `!mp addref -SH-`；`mp host "-SH-" --room 42` → `!mp host -SH-` |
| `ShellCompletions` | `map` 第二個參數補出模式名稱；`-` 開頭的名稱補成帶引號的候選值 |
| log | 讀取 `src/main/resources/application.properties`（測試 classpath 上的同名檔會遮蔽它，所以直接讀檔）：`logging.threshold.console` 為 WARN；`logging.file.name` 未設環境變數時解析為 `logs/bancho.log`、設了 `BANCHO_LOG_FILE` 時用它的值 |
| 說明文字 | `invite`、`addref` 的 help 含 `"-SH-"` 與 `-- -SH-` 兩種寫法；未知選項的錯誤訊息含同樣提示 |

## 8. 待驗證事項

| 項目 | 驗證方式 | 結果不如預期時 |
|---|---|---|
| JLine 補全時是否會跳脫候選值裡的雙引號 | 實作時以 dumb terminal 的 `LineReader` 測 | 只保留說明文字，不補引號 |
| `addref --room 42 -- -SH-` 與 `addref -- -SH- --room 42` 各自怎麼解析 | 實作時用 `CommandParser` 測 | 說明文字改成實際可用的寫法 |
| `!mp map <id> <mode>` 後 BanchoBot 的回覆 | 使用者實測 | 有新格式就加入樣本與解析規則 |
| 比賽正常結束的訊息 | 使用者實測一場打完的比賽 | 之後再加 `MatchFinished` 與狀態 |
