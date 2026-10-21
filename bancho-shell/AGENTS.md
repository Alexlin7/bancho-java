# bancho-shell

`shell` 套件：Spring Shell 互動前端，也是唯一可執行的模組（`BanchoShellApplication`、`application.properties`）。

## 執行

- `mvn spring-boot:run` 時 Maven 自己也會收到 Ctrl+C 並結束子程序，實際使用請用 `java -jar`。
- Tab 補全要在真正的終端機（Windows Terminal、IntelliJ 的 Terminal 分頁），IntelliJ 的 Run 視窗沒有補全。

### IRC 憑證

IRC 密碼從 https://osu.ppy.sh/home/account/edit#legacy-api 取得（不是帳號密碼）。shell 開啟了 `osu.irc.deferred-login`：沒設定帳密時照常啟動並提示「尚未登入，請先執行 login」，在 shell 中以 `login` 依提示輸入（密碼遮蔽、不寫入歷史與硬碟）。也可用環境變數自動登入，不要寫進 `application.properties`：

```
OSU_IRC_USERNAME=...  OSU_IRC_PASSWORD=...
```

其他 `osu.irc.*` 設定見 `bancho-irc-spring/AGENTS.md`。

### Log、樣本檔與指令歷史

- 終端機只顯示 WARN 以上（`logging.threshold.console`），完整 INFO log 寫到 `logs/bancho.log`（相對於啟動工作目錄），以 `BANCHO_LOG_FILE` 可改成其他相對或絕對路徑。沿用 Boot 的滾動檔案設定：`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE` 預設 `10MB`，`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY` 預設 `7` 天。
- 同一個資料夾還有樣本檔 `samples-<日期>.irc`（`bancho.samples.enabled`，預設開啟，測試關閉）：`SampleRecorder` 以 `IrcTrafficListener` 只收 BanchoBot 的 PRIVMSG／NOTICE、伺服器 NOTICE、數字回覆（篩選在 `SampleFilter`），原樣寫入並保留行尾空白；我們送給 BanchoBot 或 `#mp_` 的指令與時間寫成 `#` 註解，回放測試會略過。玩家聊天、其他私訊、PASS 不會寫入，`!mp password` 已遮蔽。
- 指令歷史存在 `<工作目錄>/bancho-history`（`spring.shell.history.name`；預設的 `${spring.application.name}.log` 容易和 log 混淆），測試關閉歷史。`History` bean 換成 **`PasswordSafeHistory`**（Spring Shell 的是 `@ConditionalOnMissingBean`）：任何位置的 `!mp password <x>`、shell 的 `mp password …` 與 `password …` 不寫入歷史（比對前先去掉雙引號與反斜線，`say "!mp" "password" x` 這類 parser 等價寫法也擋），因為 JLine 在指令執行前就記錄整行，指令拒絕也來不及。

### JVM 警告

JVM 直接寫到 stderr 的 `WARNING:` 不經過 logback，會插進輸入行。JLine 以 FFM 操作 Windows 主控台（JEP 472），已明確授權：jar 的 manifest 有 `Enable-Native-Access: ALL-UNNAMED`（實測對 fat jar 內層 jar 有效），`spring-boot:run` 由 plugin 的 `jvmArguments` 帶入。不要用 `--illegal-native-access=allow` 這類全域開關。

### 程序生命週期

- `spring.main.keep-alive=false`：互動 shell 佔住 main thread，設成 `true` 時 `quit` 後 JVM 不會結束。
- `spring.shell.context.close=true` 會讓 Boot 在 context 已關閉後發佈 ready 事件而 exit 1，不要用。
- Spring Shell 的非互動模式（`SPRING_SHELL_INTERACTIVE_ENABLED=false` 加上指令參數）**只用於診斷**（例如打包後以 `rooms` 驗證能啟動）：指令執行完 JVM 就結束，出站訊息要等登入完成才送，所以 `make`、`msg`、`attach` 等送 IRC 的指令都送不出去；房間只存在記憶體，`--room` 也找不到。這是刻意不支援，不開 shell 的執行方式日後由 Discord 等前端負責。
- 日後若有不開 shell 的執行方式（例如只跑 Discord）：`spring.main.keep-alive` 要改回 `true`（否則所有工作都在 daemon virtual thread 上，JVM 啟動完就會結束），`logging.threshold.console` 也要改回 INFO。

## 指令

- **`MatchCommands`**：`make <房名…> [--preset]`、`attach`、`use`、`rooms`（含建房中、結果未知的請求與 `名額 n/4`）、`drop <房名…>`、`invite <玩家目標…>`、`host <玩家目標…>`、`addref <玩家目標…>`、`map <beatmapId> [模式]`、`mods`、`set`、`start [秒]`、`timer [秒]`、`abort`、`close`、`settings`、`password [--clear]`、`mp`。**`ChatCommands`**：`say`、`msg`。**`LoginCommands`**：`login`。內建 `help`、`quit`。
- 房間指令預設作用在 **`ShellSession`** 的目前房間，`--room <房號>` 可指定其他房間；由 `RoomResolver` 解析房間，參數錯誤一律轉成 `錯誤：...` 文字，不讓例外冒到 shell。
- `make` 逾時時印出「開房結果未知」並提示 `drop`；晚到的成功回覆標註「晚到的回覆」並套用範本，但**不切換目前房間**（使用者可能已在另一間房主持），提示以 `use <房號>` 切換；被 drop 的請求不印任何訊息。
- `invite`、`host` 一次一位玩家（名稱可含空白）；`addref` 以空白分隔多個目標，每位各送一條指令、各印一行「已送出：」，含空白的名稱要改用 `#ID` 或網址。解析規則見 `bancho-match/AGENTS.md` 的 `PlayerTarget`。
- 官方文件列有 addref 多目標語法；設計計畫記載 10-20 實測「名稱＋#ID」「多個 #ID」合在一條都回 `User not found: <整串>`，因此統一逐位發送。原始 10-20/21 樣本與裁判 session 回放仍 pending，尚未以本地樣本驗證。
- `set` 接受數字或 `TeamMode`／`ScoreMode` 別名；`catch` 等遊玩模式由 `map` 處理。
- `msg` 後面的字全部是對象（osu! 名稱可以有空白），訊息一律以 **`TextPrompt`** 另外提示輸入；只打 `msg` 時連對象也提示。對象與訊息寫在同一行時無從判斷界線，`msg Minato Yukina 你好` 曾經把訊息送給 `Minato`，不要改回單行。
- `mp <指令…> [--room <房號>]` 轉送未包裝的 `!mp` 子指令；開房請用 `make`，`mp password` 一律拒絕（密碼會留在指令歷史）。
- `password` 先確定房間，再以 **`SecretPrompt`** 提示輸入（JLine 遮蔽、以 `*` 回顯，不寫入歷史）；`--clear` 移除密碼，密碼寫在指令裡會被拒絕。

## 輸入檢查

- Spring Shell 會悄悄忽略打錯的選項與多餘的參數，所以每個指令先經過 **`InputGuard`**（注入 `CommandContext` 檢查）拒絕它們。
- 數字位置參數（`set`、`map`、`attach`、`use`）收 `String` 自行解析：交給 Spring Shell 轉換時，少給或非數字只會得到看不懂的英文例外。少給或格式錯誤一律回 `錯誤：用法：…`。
- 以 `-` 開頭的文字或名稱要用雙引號（`addref "-SH-" --room 42`），或在目前房間使用 `--`（`addref -- -SH-`、`host -- -SH-`）。`--` 只能緊接指令，之後不能再加選項（`--room`、`--room=…` 都會被拒絕）；要指定房間請用雙引號。單引號不會被移除。
- **`LoginGuard`**：會送 IRC 的指令（`make`、`attach`、房間操作、`mp`、`password`、`say`、`msg`）只在連線狀態為 `REGISTERED` 時放行，在 `InputGuard` 之後、任何提示之前檢查；`IDLE`／`AUTH_FAILED` 回「請先 login」，`CONNECTING`（含斷線重連中）回「正在連線 Bancho，請等登入完成」，避免指令留在佇列、帳密被拒後重試時意外送出。`use`、`rooms`、`drop` 不受限。

## 提示、補全與行編輯

- **`JLinePrompt`** 實作 `TextPrompt` 與 `SecretPrompt`：讀取期間關閉歷史展開（`!mp` 才不會被改寫）、引號與跳脫、Tab 補全與寫入指令歷史，讀完還原；空白輸入或 Ctrl+C 取消。非互動模式下兩者直接丟 `IllegalStateException`。
- **`ShellCompletions`**：`CompletionProvider` bean（`roomCompletion`、`roomOptionCompletion`、`userCompletion`、`modsCompletion`、`makeCompletion`、`mapCompletion`、`setCompletion`、`mpCompletion`），名稱即 `@Command(completionProvider = ...)` 的值。Spring Shell 會自己補選項名稱，provider 只給值。`map`、`set` 的模式補全列出全部別名；`setCompletion` 依前兩個位置參數附編號說明；`mpCompletion` 只補第一個位置參數的未包裝子指令（已有專用指令的如 host 不列）。**`KnownUsers`** 收集房間事件、私訊與範本名單中的使用者名稱。JLine 對已輸入開引號的候選值會重複引號，因此使用者名稱補全暫不自動加引號，跳脫請手動輸入。
  - `KnownUsers` 也收集 `HostChanged`、`PlayerTeamChanged` 的名稱；目前以合成事件驗證。隊伍、host 與部分設定的新 parser 規則仍待真實樣本，尚未接上真實 IRC，相關 session 回放仍 pending。
- **`ShellLineEditing`**（BeanPostProcessor）：JLine 補全改為不分大小寫；parser 只把雙引號當引號（預設單引號也算，`say I'm ready` 會卡在等待補引號）。並把 `JLineInputProvider` 換成 **`InterruptAwareInputProvider`**：Spring Shell 預設按一次 Ctrl+C 就結束 shell，改成輸入到一半只清掉該行、空行按一次提示、2 秒內再按一次才離開（判斷在 `CtrlCGuard`）；Ctrl+D 仍直接離開。

## 輸出

- **`ShellOutput`**：有 JLine `LineReader` 時用 `printAbove`，訊息印在輸入行上方。**`ShellMessagePrinter`** 把收到的 IRC 訊息印出來。
- **`ConnectionStatusPrinter`**：登入成功與認證失敗印在畫面上（終端機只顯示 WARN 以上）。

## 測試

- 測試不得啟動互動 shell：test 的 `application.properties` 設 `spring.shell.interactive.enabled=false` 與 `osu.irc.auto-start=false`，`src/test/.../TestShellRunnerConfiguration` 以空的 `springShellApplicationRunner` 取代 shell runner，並把 JLine 換成 dumb terminal（測試 JVM 沒有主控台，偵測系統 terminal 要約 40 秒）。它放在根 package，所以每個 `@SpringBootTest` 都會掃描到。
- `@SpringBootTest` 的 client 不連線，`TestLoginGuardConfiguration` 以 `@Primary` 的 guard 視為已登入。
- 要經由真正的 parser / registry 測指令時，用 `new ShellTestClient(commandParser, commandRegistry)`。
