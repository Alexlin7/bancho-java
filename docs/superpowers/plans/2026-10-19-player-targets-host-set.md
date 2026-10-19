# 玩家識別、host 與 set 別名實作計畫

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** invite / host 接受完整 Unicode 名稱、#ID 與 osu! 個人頁網址；set 支援核准的模式別名與補全。

**Architecture:** bancho-match 提供共用玩家目標解析與 TeamMode / ScoreMode；shell 負責參數、防呆、提示與補全。保留現有數字 API、範本結構與原始 Unicode 名稱。

**Tech Stack:** Java 25、Spring Shell、JUnit、Maven wrapper，無新依賴。

**Spec:** 本次對話核准的介面：invite / host <名稱|#ID|https://osu.ppy.sh/users/ID> [--room ID]；set <team mode> <score mode> <人數> [--room ID]。

## Constraints

- 不將全形名稱轉半形、不猜測相似字；純數字仍視為名稱，ID 要有 #。
- 隊伍：0 h2h/headtohead、1 tag/tagteam/tagcoop、2 teamvs、3 tagvs/tagteamvs。
- 計分：0 score/scorev1/v1、1 accuracy/acc、2 combo、3 scorev2/v2。不分大小寫並保留 0–3。
- 不改遊玩模式 map、不改其他 log 問題、不連真實 Bancho。
- 目前 .git 為唯讀；在使用者指定的工作目錄修改檔案，不建立分支、提交或合併。

## Tasks

### 1. 玩家目標與 host
- [x] 先加實際 shell parser 測試：ID、網址、全形名稱、host、--room、防呆與取消登入狀態；跑測試確認失敗。
- [x] 新增 PlayerTarget 解析；MatchRoom.invite 與 host 共用，範本 invite 同步使用。
- [x] 新增 shell host，沿用 guard 與 userCompletion，提供跳脫說明。
- [x] 加解析邊界測試：無效 ID、錯誤網址、控制字元、關房後不能送出。

### 2. set 模式別名
- [x] 先加 set 別名的真實 parser 測試並確認失敗。
- [x] 新增 TeamMode / ScoreMode，shell 轉數字再呼叫既有 API；錯誤列出合法值。
- [x] 補 set 的位置參數補全及 --room 補全測試。

### 3. 文件與驗證
- [x] 更新 CLI help、使用說明、CLAUDE.md 的指令與介面說明。
- [x] 執行 mvnw.cmd test，確認所有模組通過；檢查 git diff --check。
- [x] 以獨立 reviewer 檢查本次差異，修正實質問題後完成。

## 驗證結果

- 新功能測試先確認網址邀請、host 與 set 別名缺失，再完成實作。
- 獨立 review 發現範本 invite 需在開房前驗證；新增失敗測試重現後，將檢查加入 validatePreset 與啟動驗證測試。
- 最終 `mvnw.cmd test -B -ntp`：8 個模組 SUCCESS，440 tests，0 failures / errors / skipped。
- `git diff --check` 通過；未連真實 Bancho，未建立分支、提交或改動 .git。
