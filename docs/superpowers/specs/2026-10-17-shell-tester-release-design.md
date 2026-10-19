# 建房名額與結果未知、shell 測試版發布設計

- 日期：2026-10-17
- 狀態：設計待使用者檢閱
- 相關文件：[2026-10-15-osu-bot-lifecycle-permissions-design.md](2026-10-15-osu-bot-lifecycle-permissions-design.md)（Discord 版的帳號、房間生命週期與權限設計，以下簡稱「生命週期設計」）

## 1. 目的

目前 BanchoBot 的實測樣本太少，Discord 版的許多規則（建房被拒、referee 新增／移除、禁言等）都卡在「來源為第三方原始碼，實作前須實測」。這一輪要把 Spring Shell 包成朋友拿到就能用的測試版，讓他們**用自己的 osu! 帳號實際當裁判開房**，再把 log 傳回來補樣本。

發版前先補兩項不受 Discord 技術選型影響的核心行為：建房名額、建房結果未知。

分成兩部分，依序實作：

1. 核心補強（`bancho-irc`、`bancho-match`、`bancho-shell`）
2. 測試版發布（登入流程、樣本檔、打包、GitHub Actions）

## 2. 目標、非目標、反目標

**目標**
- 朋友解壓縮後雙擊就能用，不必安裝 Java、不必設定環境變數。
- 朋友正常使用一場比賽後，傳回來的檔案可以直接變成 `bancho-match/src/test/resources/banchobot/` 的樣本。
- 建房逾時不再被當成失敗，名額不會算少。

**非目標**
- 資料庫與跨重啟復原：狀態仍只存在記憶體，重開程式即清空。
- 傳輸白名單：生命週期設計的「只能發給 BanchoBot 與 `#mp_`」是 Discord 前端的規則，不放進 IRC 核心，也不套用到 shell。shell 只有本機使用者能操作，私訊由使用者自己負責。
- 禁言偵測：沒有樣本，不憑記憶寫規則；改由樣本檔收集 Bancho 的 NOTICE 與數字回覆，等有人遇到再補。
- macOS／Linux 的打包版本（jar 仍可在任何平台用）。
- 不往 Maven Central 發布任何新版本。`bancho-irc` 這輪的變更（`send-interval` 預設值、延後登入、觀察原始行）先留在 main，等下一次函式庫發版再上 Central。

**反目標（做了就算失敗）**
- 密碼出現在任何 log、樣本檔或指令歷史。
- 樣本檔記下房間玩家的聊天或其他人的私訊。
- 每發一次測試版就得往 Central 發一個永遠刪不掉的函式庫版本。
- 結果未知讓朋友卡死，只能重開程式。

## 3. 第一部分：建房名額與結果未知

### 3.1 名額

- 已開的房間、建房中的請求、結果未知的請求共用名額，上限 4（官方每帳號 4 間錦標賽房）。超過時 `make` 直接在本地拒絕，不送 `!mp make`。
- 已開的房間包括 `make` 建立的、`attach` 接手的，以及收到事件才被動登記的房間：它們都佔用同一個帳號的名額。
- 同一個請求轉成房間時不可重複計數。
- 收到 `Closed the match`（`MatchClosed`）才釋放房間的名額。

### 3.2 結果未知

- `!mp make` **實際寫出到 IRC 連線後** 15 秒（`bancho.match.create-timeout`，維持現有設定）沒收到同名回覆，請求轉為**結果未知**，繼續佔用名額。
- 計時從實際送出開始（與生命週期設計相同）。為此 `IrcSender` 新增可帶「送出後通知」的送訊方法；還在佇列裡（例如斷線中）的請求維持**建房中**，不會逾時，也不能 drop。
- **與生命週期設計方案 A 的差異**：結果未知**不暫停其他房名的 `make`**，只拒絕**同一個房名**的新請求。理由：方案 A 全面暫停，是因為 bancho.js 只抓 match ID，晚到的回覆可能配給下一筆請求；我們以房名配對，4 筆實測樣本（含中文、日文房名）都確認回覆帶房名，不同房名不會誤配。同名仍要擋，否則重送會開出兩間同名房，其中一間沒人管又佔名額。這等於提前在 shell 採用方案 B 的配對方式；Discord 版是否沿用，到時再決定。
- 晚到的同名成功回覆照常接上：登記房間、加入頻道，請求轉為成功，名額改由房間計算。
- 建房被拒的回覆沒有樣本，看不懂時照現有規則記成未辨識訊息；請求維持結果未知，由使用者手動釋放。

### 3.3 手動釋放

- 新增 shell 指令（**我的決定**：名稱 `drop <房名>`），移除一筆結果未知的請求並釋放名額；房名的空白處理和 `make` 相同。
- 使用時機寫進說明：先在遊戲裡確認這間房沒有開起來。
- 只能 drop 結果未知的請求；它的 `!mp make` 一定已經送出（見 3.2），不存在「取消未送出指令」的情況。
- **drop 過的房名在本次執行中不能再用**：`make` 同名時拒絕並提示換一個房名，重開程式後才解除。理由：已送出的 `!mp make` 仍可能晚到，若允許同名重開，舊回覆會配給新請求，新的 `!mp make` 又會多開一間。
- 釋放後若同名回覆才到，視同「非 `createMatch` 建立的房間」照常登記追蹤（現有的 `onCreated` 行為），並佔用名額；使用者可用 `close` 關掉。
- 已登記但其實在斷線期間被關掉的房間會一直佔名額，這輪不處理；重開程式即可。在說明中寫明。

### 3.4 shell 顯示

- `make` 逾時時改顯示結果未知，並提示：若遊戲裡沒有這間房，用 `drop <房名>` 釋放名額。
- 晚到的成功回覆要在畫面上通知使用者（房號與房名），因為原本等待的 `make` 已經結束。
- `rooms` 除了已開的房間，也列出建房中與結果未知的請求，並顯示已用名額（例如 `名額 3/4`）。

### 3.5 送訊速率

`osu.irc.timing.send-interval` 預設從 500ms 改為 625ms：一般帳號的上限是每 5 秒 10 則，保留生命週期設計要求的 20% 餘裕。CLAUDE.md 中 bot 帳號建議值同步改為 240ms。

## 4. 第二部分：測試版

### 4.1 分階段登入

- 沒有設定帳號／密碼時，shell 照常啟動但不連線，啟動後提示先執行 `login`。
- `login`：以 `TextPrompt` 詢問帳號，以 `SecretPrompt` 詢問 IRC 密碼（遮蔽、不進歷史）。提示文字附上取得 IRC 密碼的網址 `https://osu.ppy.sh/home/account/edit#legacy-api`，並說明這不是帳號密碼。
- 有設定 `OSU_IRC_USERNAME`／`OSU_IRC_PASSWORD` 時維持現狀，啟動即自動登入。
- 只有**已登入（收到 001）**時才放行會送 IRC 的指令（房間、聊天類）。尚未提供帳密或認證失敗時回 `錯誤：請先 login`；連線中或斷線重連中回 `錯誤：正在連線 Bancho，請等登入完成`，避免指令留在佇列、帳密被拒後重試時意外送出。`help`、`quit`、`rooms`、`drop` 等不送訊的指令照常可用。
- 認證失敗（464／Bad authentication）後可以再次 `login`；已登入時再次 `login` 回錯誤（**我的決定**：不支援在同一次執行中切換帳號）。
- 不把密碼或帳號寫到硬碟（**我的決定**）。
- 需要顯示登入狀態：登入成功、認證失敗都要在畫面上告知（終端機只顯示 WARN 以上，不能只靠 log）。

目前的限制，實作時要解決：
- `OsuIrcSettings` 在建構時檢查帳密非空白，`OsuIrcAutoConfiguration` 在啟動時就建立 client。
- `OsuIrcClient` 認證失敗後 connection thread 結束，`started` 仍為 true，無法再次啟動。
- `MatchAutoConfiguration` 以 `@ConditionalOnBean(IrcSender.class)` 註冊，`MatchService`、`BanchoBotListener` 必須在登入前就存在，listener 也要在登入後照常收到訊息。

實作方式留給實作者，限制如下：
- `bancho-irc` 仍不得依賴 Spring；變更以新增 API 為主，不破壞現有「建構時給齊帳密、`start()` 連線」的用法（它已發布在 Central）。
- 不用 Spring 的使用者也要能做到「先建立 client、之後才給帳密」，這個能力屬於 `bancho-irc`，不是 shell 專屬的繞路。
- 帳密空白時仍要有明確錯誤，只是時間點從建立 client 移到連線。
- 延後登入必須明確開啟（`osu.irc.deferred-login=true`，shell 會開）。未開啟時，不論帳密是空字串或完全沒設定，啟動都要和現在一樣失敗。
- 已發布型別的建構子維持可用：例如 `OsuIrcProperties` 新增欄位時，保留原本的五參數建構子並委派到新建構子（`deferredLogin=false`）。

### 4.2 log 中的密碼遮蔽

房間密碼經由 `!mp password <密碼>` 送出，目前會出現在兩個 log 路徑：client 在 DEBUG 記錄的完整出站行，以及訊息超過 512 bytes 截斷時的 WARN。不論密碼是從 `password` 指令或 `msg BanchoBot` 手打送出，都要遮蔽。

- `bancho-irc` 不認識 `!mp`，所以只提供「出站行遮蔽」的掛點：設定後套用在出站 DEBUG log、截斷 WARN 與原始行觀察者（4.3）收到的出站行；實際送到 Bancho 的內容不變。
- 遮蔽規則（`!mp password <x>` → `!mp password ***`，不分大小寫）屬於 `match`，由 `bancho-match-spring` 在有 `OsuIrcClient` 時自動接上，shell 不必另外設定。

### 4.3 樣本檔

- 預設開啟，寫到 log 資料夾下的 `samples-<日期>.irc`，和 `bancho.log` 放在同一個資料夾（跟著 `BANCHO_LOG_FILE` 的目錄走）。
- 格式與現有 `.irc` 樣本相同：每行一則原始 IRC 行，**保留行尾空白**（slot 行的空白是原文）。
- 只收：
  - BanchoBot 送來的訊息（私訊與 `#mp_` 頻道）
  - Bancho 伺服器的 NOTICE 與數字回覆（禁言等未知系統訊息）
  - 我們送出的、對象是 BanchoBot 或 `#mp_` 頻道的訊息（對照「送了什麼、回了什麼」）
- 不收：PING／PONG、`PASS`、房間玩家的聊天、其他人的私訊、我們送給 BanchoBot 與 `#mp_` 以外對象的訊息。
- `!mp password <密碼>` 必須把密碼遮蔽，寫成 `!mp password ***`（由 4.2 的遮蔽掛點處理）。
- 每行前面加上時間戳記的註解行，或以其他不破壞 `.irc` 格式的方式保留時間（**留給實作者**；回放測試需要能直接讀取，必要時由匯入的人刪掉）。
- 樣本檔照原文保留使用者自己的 nick；匯入測試資源時由匯入的人改成 `test_user`（和現有樣本相同），程式不做匿名化（**我的決定**）。
- 目前 `IrcMessageListener` 只收得到 PRIVMSG，NOTICE、數字回覆與出站行都只寫在 DEBUG log。實作者需要在 `bancho-irc` 加入觀察原始進出站行的方式；`bancho-irc` 不認識 BanchoBot，**篩選規則放在 `match` 或 shell**。`PASS` 行不得交給任何觀察者。

### 4.4 打包

- `bancho-shell` 的可執行 jar 照舊。
- 另以 jpackage 產生 Windows app-image（資料夾內含 Java runtime 與 `.exe`），壓縮成 zip。
  - 使用主控台啟動器（`--win-console`），雙擊就開終端機視窗。
  - 帶入 `--enable-native-access=ALL-UNNAMED`（JLine 以 FFM 操作 Windows 主控台）。
  - log 與樣本檔要落在解壓縮後的資料夾內（例如 `logs/`），不論從哪裡啟動；指令歷史同理。
- zip 內附一份給朋友的說明（繁體中文，純文字或 Markdown）：
  - 怎麼啟動（建議在 Windows Terminal 裡執行 `.exe`，雙擊開出的傳統主控台也能用但顯示較差）
  - 怎麼取得 IRC 密碼（不是帳號密碼）
  - 常用指令與一場比賽的基本流程（`login` → `make` → `invite` → `map` → `start` → `close`）
  - 打完後把整個 `logs/` 資料夾壓縮傳回
  - 樣本檔會記下哪些內容、不會記下哪些內容

### 4.5 GitHub Actions 發版

- 觸發條件：推送 `shell-v*` 格式的 tag（例如 `shell-v0.1.2`）。不使用 `v*`：那是 Maven Central 版本專用的 tag。
- 在 Windows runner 上以 Java 25 跑測試、打包 jar 與 jpackage app-image。
- 建立 GitHub Release，標為 pre-release，附上：
  - `bancho-shell-<版本>.jar`
  - `bancho-shell-<版本>-windows.zip`
- `<版本>` 取自 tag（去掉 `shell-v`），**不修改** pom 的 `<revision>`、不 commit。jar 內部的 Maven 版本可以和檔名不同，因為應用程式模組不發布到 Central。jpackage 的 `--app-version` 只接受數字，同樣取自 tag。
- 這是 repo 的第一個 workflow；不需要 Central 或 GPG 的憑證。

## 5. 驗證

- 名額：第 5 間被拒；`attach` 的房間也算；`MatchClosed` 後釋放；請求轉為房間不重複計數。
- 結果未知：還在佇列裡的請求不會逾時；逾時後名額保留；同名 `make` 被拒、不同名可開；晚到的回覆接上並通知；`drop` 後名額釋放，但同名仍不能再開；`drop` 後才到的回覆照常登記。
- 登入：未登入與連線中時送訊指令都被拒；`login` 成功後可開房；認證失敗後可再 `login`；有環境變數時自動登入；未開啟延後登入時，帳密空白或完全沒設定都啟動失敗。
- 密碼：`password` 指令與 `msg BanchoBot` 手打的 `!mp password` 都不出現在 DEBUG log、截斷 WARN、樣本檔與指令歷史。
- 樣本檔：用 `FakeIrcServer` 驗證只收規定的行、`PASS` 不在檔內、`!mp password` 已遮蔽、行尾空白保留。
- 打包：本機實際跑一次 jpackage，在沒有設定 `JAVA_HOME` 的情況下雙擊 `.exe` 能進到 shell，`logs/` 出現在解壓縮資料夾內。
- workflow：推一個測試用的 `shell-v` tag，確認 Release 與兩個檔案產生（成功後可刪除該 tag 與 Release）。

## 6. 留給實作者

- `bancho-irc` 延後登入與觀察原始行的 API 形狀。
- 名額與結果未知的狀態放在 `MatchService` 內或拆成獨立類別，以及 `createMatch` 回傳值如何表達「結果未知但之後可能成功」。
- 樣本檔的寫入方式（獨立 writer 或 logback appender）與時間戳記格式。
- jpackage 的呼叫方式（Maven plugin 或 workflow 內直接執行）。
- CLAUDE.md 的對應更新（`send-interval` 預設值、`login`、`drop`、樣本檔、發版流程）。
