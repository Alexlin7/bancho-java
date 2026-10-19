# 模組依賴宣告與是否提供 starter

- 日期：2026-10-19
- 範圍：`bancho-irc`、`bancho-irc-spring`、`bancho-match`、`bancho-match-spring`、`bancho-shell` 的 pom 依賴；是否另外提供 Spring Boot starter
- 結論已落實於 commit `8aa6918`（`build: 直接 import 的模組明確宣告依賴，不靠遞移帶入`），規則寫在 `CLAUDE.md`「Maven 模組」
- 相關文件：[帳號、房間生命週期與權限設計](2026-10-15-osu-bot-lifecycle-permissions-design.md)（lazer 不在本期範圍）

## 1. 起因

shell 同時宣告 `bancho-match` 與 `bancho-match-spring`，但後者已經依賴前者，看起來重複。

依賴會遞移：

```
bancho-match-spring
├── bancho-match ──► bancho-irc
└── bancho-irc-spring ──► bancho-irc
```

shell 只宣告 `bancho-match-spring` 也能編譯與執行，多宣告的那幾條不是功能上必要的。

另外確認：Lombok 不是經由 irc 遞移帶入的。Lombok 宣告在根 pom 的 `<dependencies>`（`optional`），每個子模組各自從 parent 繼承；`optional` 依賴本來就不會遞移。

## 2. 宣告規則

採用 Maven 的慣例：**程式碼直接 import 了哪個 artifact 的類別，就在自己的 pom 宣告它**，不依賴別的模組遞移帶入。

理由：

- 被依賴的模組調整自己的依賴（例如改成 `optional`、拿掉某條）時，使用端不會突然編譯失敗。
- pom 本身就能看出模組用到哪些 API。

例外：

- Spring 本身的 jar（`spring-context`、`spring-beans`、`spring-boot`）視為 `spring-boot-autoconfigure` 的一部分，不逐一宣告；Spring 模組的 `slf4j-api` 同樣由 Boot 帶入。
- 純 Java 模組（irc、match）要自己宣告 `slf4j-api`：它們自己記 log，而且沒有 Boot 可以依靠。

## 3. `dependency:analyze` 的結果

以 `./mvnw -pl bancho-shell -am compile dependency:analyze` 檢查，修改前與本專案相關的警告：

| 模組 | 用到但沒宣告 | 宣告了但沒用到 |
| --- | --- | --- |
| `bancho-match` | `slf4j-api` | — |
| `bancho-match-spring` | `bancho-irc` | Lombok |
| `bancho-shell` | `bancho-irc` | `bancho-irc-spring` |

- match-spring 的 `MatchAutoConfiguration` 直接 import `IrcSender`、`OsuIrcClient`；shell 有 14 個 import 來自 `irc` 套件。兩者都是靠遞移拿到 `bancho-irc`。
- shell 原本照慣例宣告了 `bancho-match`，卻沒有宣告 `bancho-irc`，規則只做了一半。
- shell 的 `bancho-irc-spring` 是**誤報**：analyze 只看 bytecode，看不出它在執行期由 auto-configuration 建立 client。保留宣告，表達「shell 需要自動建立 client」，不依賴 match-spring 剛好會帶進來。
- match-spring 的 Lombok 來自根 pom，只是這個模組沒用到，`optional` 不會傳給使用者，不處理。
- Spring jar 的警告依第 2 節的例外不處理。

修改：

| 檔案 | 修改 |
| --- | --- |
| `bancho-match/pom.xml` | 加上 `slf4j-api` |
| `bancho-match-spring/pom.xml` | 加上 `bancho-irc`；`bancho-irc-spring` 的註解改為「排在 `OsuIrcAutoConfiguration` 之後」（原本寫「需要 IrcSender」，但它在 `bancho-irc`） |
| `bancho-shell/pom.xml` | 加上 `bancho-irc`；註明 `bancho-irc-spring` 會被 analyze 誤報 |

修改後各模組之間沒有 used undeclared；全部測試通過。

## 4. 是否提供 starter

### 4.1 結論：目前不提供

shell 維持宣告四條：`bancho-irc`、`bancho-irc-spring`、`bancho-match`、`bancho-match-spring`。

### 4.2 理由

依 Spring Boot 參考文件〈Developing Auto-configuration › Creating Your Own Starter〉：

1. **正式的 starter 必須帶 `spring-boot-starter`。**
   > your starter must reference the core Spring Boot starter (`spring-boot-starter`) directly or indirectly

   `spring-boot-starter` 會帶進 logback。irc-spring、match-spring 刻意只依賴 `spring-boot-autoconfigure`，不替使用者決定 log 後端；做成 starter 會推翻這個決定。

2. **現有的 `*-spring` 模組已經是合併型。** 官方的分法是 autoconfigure 模組把函式庫標成 `optional`（函式庫不在時自動退出），再由空的 starter 把函式庫帶齊。我們的 `*-spring` 把本體設為一般 compile 依賴，加一條就帶進全部；官方也說沒有可選功能時，合併「is definitely an option」。再包一層空 starter 不會多帶任何東西。

3. **對 shell 沒有好處。** shell 直接 import irc 與 match，照第 2 節的規則仍要宣告兩個本體，starter 只省兩條 `*-spring`，卻多出兩個空模組要維護。

4. **命名。** 官方建議合併型模組命名為 `xxx-spring-boot-starter`，但 `bancho-irc-spring` 已發布到 Central，改名對既有使用者是破壞性變更，維持現名。

### 4.3 starter 什麼時候才有意義

官方文件指出，函式庫有「several flavors, options or optional features」時，才值得拆開 autoconfigure 與 starter：autoconfigure 把各選項標成 `optional`，每個 starter 代表一種選擇。

在本專案中，最可能出現的「選項」是傳輸方式，例如 match 除了經由 IRC 與 BanchoBot 互動，也能用在 lazer：

```
bancho-match            房間模型、事件、MatchService（只認識抽象的傳輸介面）
bancho-match-irc        IRC + BanchoBot 的傳輸實作
bancho-match-lazer      lazer 協定的傳輸實作
bancho-match-spring     auto-configuration；irc、lazer 標 optional，依 classpath 接上

bancho-match-irc-spring-boot-starter     = match-spring + match-irc
bancho-match-lazer-spring-boot-starter   = match-spring + match-lazer
```

到這一步，使用者選一個 starter 就等於選一種傳輸方式，starter 才有存在的理由。

### 4.4 解耦合的實際工作量

目前 match 與 IRC／BanchoBot 的綁定不只在 pom，API 的形狀也是：

- **`MatchRoom` 的操作就是 `!mp` 指令**：`raw(arguments)` 直接轉送 `!mp` 子指令；`mods` 擋 `NFHD` 這類連寫，是因為 BanchoBot 看不懂。要支援其他傳輸，這些要改成語意操作（例如「設定 mod 為 HD、HR」），由各傳輸方式翻譯。
- **`MatchEvent` 來自 BanchoBot 文字的解析**：team mode、mod 名稱刻意保留 BanchoBot 原文、不轉 enum。lazer 的事件應該是結構化資料，兩者要先統一成同一套模型。
- **`MatchService` 的限制來自 stable**：名額上限 4 是官方 `!mp` 的限制；`!mp make` 的逾時與「結果未知」是為了 BanchoBot 回覆不可靠而設計的。

### 4.5 重新評估的時機

不為 lazer 預先拆模組：只有一種實作時抽出的介面，通常會猜錯形狀。出現下列任一情況時再重新評估：

- 某個功能需要變成**可選**（例如 match-spring 要在沒有 IRC client 時也能載入），autoconfigure 模組必須把依賴標成 `optional`，就需要另一個模組把依賴帶齊。
- 有外部使用者，而且要替他們決定一組預設依賴（例如附上 log 後端），讓他們加一行就能執行。
- 要支援第二種傳輸方式（例如 lazer）。做法沿用樣本優先的原則：
  1. 先取得該協定的真實資料或樣本；
  2. 依兩種實作的共同點抽出傳輸介面；
  3. 再依第 4.3 節拆出 starter。
