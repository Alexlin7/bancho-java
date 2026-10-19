# 指令 review 修正計畫

依使用者列出的十點修正，沿用目前未提交的指令實作；不變更 .git、不連真實 Bancho。

- [x] 先以既有 API 測試重現：範本設定範圍與裁判漏驗證、無 scheme 網址、模式網址、addref 網址。
- [x] PlayerTarget 接受 osu.ppy.sh 無 scheme 網址與模式尾碼，名稱網址明確說明需數字 ID。
- [x] MatchRoom 共用範本指令組裝供啟動驗證及 apply 使用，避免驗證清單再次分歧；addref 共用玩家解析並保留含空白名稱拒絕規則。
- [x] MatchPreset 的 team-mode / score-mode 改收 String 並交給 TeamMode / ScoreMode；設定檔保留數字用法。
- [x] MatchRoom.invite / host / addRef 回傳實際入列指令，shell 不重複解析，回覆仍為「已送出」。
- [x] 三種 mode 共用解析 helper；set 補全共用產生候選的 helper。
- [x] 更新用法、mp 範例、CLI 文件與 CLAUDE.md 清單。
- [x] 執行完整測試與 diff 檢查，獨立 review 本次修正。

實作決定：MatchPreset 維持單一 canonical constructor。額外的整數建構子會讓 Spring 無法自動選擇綁定建構子，且純 Java 模組不能依賴 Spring 的 ConstructorBinding；Java 呼叫端改傳字串，設定檔數字仍會綁定為文字。

## 驗證結果

- 先以既有 API 重現範本漏驗證、模式網址與裁判目標問題，觀察測試失敗，再完成修正。
- 最終 mvnw.cmd test -B -ntp：8 個模組 SUCCESS，450 tests，0 failures / errors / skipped。
- git diff --check 通過。獨立 reviewer 確認十項問題已處理，沒有其他實質問題。
- 名稱型個人頁網址提供明確錯誤，未加入網路查帳號行為。
