# 第二次 shell 實測回饋（必填參數、密碼輸入、新 BanchoBot 樣本、JVM 警告）設計

- 日期：2026-10-17
- 狀態：已實作（自動測試與 jar 驗證完成；遮蔽輸入的實際操作待使用者在真正的終端機實測）
- 前一份設計：`2026-10-17-shell-refinements-design.md`（本文件只寫差異）
- 分支：`feature/match-room-phase2`（完成後與階段二一起合回 `main`）

## 1. 背景

使用者 2026-10-17 在真正的終端機（`spring-boot:run`）實測，發現：

1. `set`、`set 2`、`set --room 122022753 2` 都只得到 Spring Shell 的
   `Unable to run command set : A null value cannot be assigned to a primitive type`，看不出少了什麼。
2. 想設密碼時先按了 Enter，`password` 不帶參數等於「移除密碼」，房間變成公開，路人就進來了。
3. 密碼以明文留在指令歷史：Spring Shell 把歷史存在 `<工作目錄>/${spring.application.name}.log`，
   也就是專案根目錄的 `bancho.log`（已被 `*.log` ignore，沒有進版控）。這個檔名也很容易和真正的 log `logs/bancho.log` 搞混。
4. 新的 BanchoBot 回覆被記成「未辨識」：遊玩模式變更、玩家完成、比賽結束、全員準備、移除密碼。
5. JVM 在 stderr 印出 `WARNING:`，不經過 logback，會插進輸入行。

## 2. 必填參數的錯誤訊息

`set`、`map`、`attach`、`use` 的位置參數用 `int` / `long`，少給時 Spring Shell 在呼叫方法前就因為 null 無法放進 primitive 而失敗。

- 改成 `Integer` / `Long`，在 `InputGuard` 檢查之後、執行之前判斷 null，回 `錯誤：用法：<用法>`：
  - `set <team mode> <score mode> <人數> [--room <房號>]`
  - `map <beatmap id> [模式] [--room <房號>]`
  - `attach <房號>`、`use <房號>`
- 非數字（例如 `set a b c`）時 Spring Shell 的錯誤訊息是什麼，實作時實測；若同樣看不懂，就改收 `String` 自行解析，錯誤訊息同上。

## 3. 密碼改成提示輸入

| 輸入 | 結果 |
|---|---|
| `password` | 顯示 `新密碼：` 提示，輸入以 `*` 回顯（與 sudo-rs 相同，看得出打了幾個字）。空白輸入或 Ctrl+C／Ctrl+D 取消，不送任何指令，回 `已取消，密碼沒有變更`。 |
| `password --clear` | 送 `!mp password`（移除密碼），回 `已送出：!mp password（移除密碼）`。 |
| `password <任何文字>` | 拒絕：`錯誤：密碼請在提示中輸入，不要寫在指令裡（會留在指令歷史）`。 |
| 非互動模式（沒有 `LineReader`） | `錯誤：非互動模式無法輸入密碼`。範本的 `password` 不受影響。 |

- 讀取密碼抽成 shell 層的介面 `SecretPrompt`（`Optional<String> read(String prompt)`，空 = 取消）。
  正式實作用 JLine `LineReader.readLine(prompt, '*')`；測試注入假的實作。
- 實作時要先驗證：
  1. Spring Shell 4.0.3 在指令執行中再呼叫一次 `LineReader.readLine` 沒有問題。
  2. 遮蔽輸入（mask 不為 null）**不會**寫進歷史；若會，就在讀取前後暫時設 `LineReader.Option.DISABLE_HISTORY`。
  3. Ctrl+C／Ctrl+D 分別丟出 `UserInterruptException`／`EndOfFileException`，都轉成「取消」。
- `mp password …` 也會把密碼留在歷史，改成拒絕：`錯誤：設定密碼請用 password 指令`（`mp password` 不帶參數同樣拒絕，移除請用 `password --clear`）。

### 歷史檔

- `spring.shell.history.name=bancho-history`：歷史改存到 `<工作目錄>/bancho-history`，不再和 `logs/bancho.log` 混淆。`.gitignore` 加上 `bancho-history`。
- 舊的根目錄 `bancho.log`、`bancho-test.log` 是歷史檔，裡面有明文密碼；由使用者自行刪除（不在程式裡刪）。

## 4. 新的 BanchoBot 樣本

新增樣本檔 `src/test/resources/banchobot/session-2026-10-17b.irc`，內容取自本次終端機紀錄中 BanchoBot 的回覆（依原順序，房號 122022753）。
終端機紀錄沒有保留行尾空白，這些訊息也不像 slot 行有補空白，照原樣寫入。

| 訊息 | 處理 |
|---|---|
| `Changed match mode to CatchTheBeat` | 新事件 `GameModeChanged(matchId, String mode)`，mode 保留原文。`MatchState` 新增 `gameMode`（未知為 null），`rooms` 顯示 `mode <原文>`。目前只有 `CatchTheBeat` 的樣本，不對應到 `GameMode`。 |
| `Alexlin86 finished playing (Score: 9245186, PASSED).` | 新事件 `PlayerFinished(matchId, username, long score, String result)`，result 保留原文（目前只有 `PASSED` 樣本）。不改狀態。 |
| `The match has finished!` | 新事件 `MatchFinished(matchId)`。不改狀態。 |
| `All players are ready` | 新事件 `AllPlayersReady(matchId)`。不改狀態（之後可用來自動開始）。 |
| `Removed the match password` | 加入略過清單。 |

規則（都比對整行）：

- `Changed match mode to (\S+)`
- `(.+?) finished playing \(Score: (\d+), (\w+)\)\.`
- `The match has finished!`
- `All players are ready`
- `Removed the match password`

回放測試預期：房名 `test123房間`、beatmap 4199355、mods `[NoFail, Hidden]`、mode `CatchTheBeat`、settings `6 / TagTeamVs / ScoreV2`、
slot 只剩 Alexlin86（BMOBMO 已離開）；`PlayerFinished` 3 個、`MatchFinished` 2 個、`AllPlayersReady` 2 個、`MatchStarted` 3 個、`MatchAborted` 1 個；沒有任何未辨識的行。

## 5. `mods` 連寫提示

BanchoBot 收到 `!mp mods NFHD` 會回 `Disabled all mods`（看不懂就清空）。shell 送出前檢查：
只要有一個字長度大於 2、而且不是 `Freemod`（不分大小寫）這類完整名稱，就**不送**並回
`錯誤：mod 要用空白分開，例如 mods NF HD`。完整名稱清單只放 `Freemod`（目前唯一確定 BanchoBot 接受的長名稱）；
不自動拆字，以免把長名稱拆錯。範本的 `mods` 走同一個檢查（放在 `MatchRoom`，與既有的空白檢查一起）。

## 6. JVM 警告

| 警告 | 處理 |
|---|---|
| JLine 的 `restricted method … MemorySegment`（JEP 472） | JLine 需要 FFM 操作 Windows 主控台，屬正當需求，明確授權：`spring-boot-maven-plugin` 的 `jvmArguments` 加 `--enable-native-access=ALL-UNNAMED`（`spring-boot:run`），打包的 jar manifest 加 `Enable-Native-Access: ALL-UNNAMED`（`java -jar`）。實作時以 `java -jar` 實跑確認警告消失（fat jar 內層 jar 的類別由 Boot 的 class loader 載入，需實測）。 |
| protobuf 的 `sun.misc.Unsafe::arrayBaseOffset`（JEP 498） | 來自 `micrometer-registry-otlp` → `opentelemetry-proto` → `protobuf-java`，要等函式庫修正。**不處理**，CLAUDE.md 記一筆：日後 JDK 預設改成 deny 時，需升級 protobuf 或加 `--sun-misc-unsafe-memory-access=allow`。 |

不用 `--illegal-native-access=allow` 之類的全域開關。

## 7. 測試

- `MatchCommandsTest`：`set` 少參數（0、1、2 個）回用法且不送；`map`、`attach`、`use` 不帶參數回用法；
  `password` 讀到密碼 → `!mp password abc`、回應遮蔽；讀到空值 → 取消不送；`password --clear` → `!mp password`；
  `password abc` → 拒絕不送；沒有 `SecretPrompt`（非互動）→ 錯誤；`mp password x` → 拒絕；`mods NFHD` → 拒絕不送；`mods Freemod` → 送出。
- `ShellCommandParsingTest`：經真正 parser 的 `set`、`set 2`、`set --room 42 2` 回用法（重現本次錯誤）。
- `BanchoBotParserTest`：各新規則與略過的行。
- `MatchStateTest`：`GameModeChanged` 更新 `gameMode`。
- `MatchReplayIntegrationTest`：第 4 節的回放預期。
- `MatchRoomPresetTest`：範本 `mods: [NFHD]` 一條都不送。
- 打包驗證：`./mvnw package` 後檢查 manifest 有 `Enable-Native-Access`，並以 `java -jar` 非互動執行 `rooms` 確認 stderr 沒有 `restricted method` 警告。

## 8. 待驗證事項

| 項目 | 結果 |
|---|---|
| 指令執行中呼叫 `readLine(prompt, '*')` | 以 dumb terminal 的單元測試確認可讀取、空白與 EOF 回 empty；真正終端機的操作待使用者實測 |
| 遮蔽輸入是否寫入歷史 | ✅ 不會：JLine 的 `SimpleMaskingCallback.history()` 回 null，`LineReaderImpl` 只在 historyLine 非空時加入歷史；`JLineSecretPromptTest.secretIsNotAddedToHistory` 釘住 |
| `set a b c` 的錯誤訊息 | Spring Shell 只拋 `CommandExecutionException: Unable to execute command set`（cause 為 `ConversionFailedException ... for value [a]`），看不懂 → 四個指令改收 `String` 自行解析 |
| fat jar 的 `Enable-Native-Access` | ✅ 有效：正式 jar 以 `java -jar` 非互動執行沒有 `restricted method` 警告；移除該條目的對照 jar 會出現 JLine `FfmTerminalProvider` 的警告 |
| 其他遊玩模式、`FAILED` 的原文 | 仍待使用者實測，先保留原文字串 |

## 9. Ctrl+C 連按兩次才離開（實測後追加）

Spring Shell 4 的 `InteractiveShellRunner` 在讀取輸入時遇到任何例外都會跳出迴圈，所以在 `shell:>` 按一次 Ctrl+C 就會結束程式。裁判誤按會讓 bot 斷線、房間無人管理。改成參考 Claude Code 的做法：

| 狀況 | 行為 |
|---|---|
| 輸入到一半按 Ctrl+C | 清掉這一行，不離開，也不算第一下 |
| 空的 `shell:>` 按 Ctrl+C | 印出 `（再按一次 Ctrl+C 離開，或輸入 quit）`，不離開 |
| 提示後 2 秒內（含）再按一次 | 正常離開（回傳 null，Spring Shell 印 `Exiting the shell`） |
| 超過 2 秒、或中間已送出一行指令 | 重新從第一下算 |
| `新密碼：` 提示中按 Ctrl+C | 只取消輸入（第 3 節，不變） |
| Ctrl+D | 維持 Spring Shell 原本的直接離開 |

- 以 `ShellLineEditing`（BeanPostProcessor）把 `JLineInputProvider` 換成子類別 `InterruptAwareInputProvider`，攔下 `UserInterruptException`。判斷邏輯放在純類別 `CtrlCGuard`（注入 `Clock`，可單元測試）。
- `mvn spring-boot:run` 時 Maven 也會收到 Ctrl+C 並結束子程序，這個行為只在 `java -jar` 下完整有效；CLAUDE.md 註明。
