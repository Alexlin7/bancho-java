# AGENTS.md

給 coding agent（Claude Code、Codex）的共用規則。Claude Code 經由 `CLAUDE.md` 匯入本檔。

## 規則檔的分工

- 本檔放全專案共用的內容：專案目標、建置、模組規則、發布、架構總覽、通用慣例。
- 每個模組目錄有自己的 `AGENTS.md`，記錄只跟該模組有關的類別行為與地雷；**修改某個模組的檔案前，先讀該模組的 `AGENTS.md`**（Claude Code 會經由模組的 `CLAUDE.md` 自動載入）。
- 新增或修改規則時寫進範圍最小的 `AGENTS.md`；`CLAUDE.md` 只放 Claude Code 專屬的內容，不要在兩邊重複。

## 專案目標

以 osu! Bancho IRC（`cho.ppy.sh:6667`）為基礎的機器人：先實作 IRC 連線行為，最終用來**開多人房（`!mp make` 等 BanchoBot 指令）並擔任比賽裁判**。後續會在 IRC 核心之上擴充 Discord 功能等模組，目標是演變成 **monorepo**（IRC 核心與 Discord 等整合應拆成獨立模組，避免讓 IRC 客戶端依賴特定前端）。

設計文件與實作計畫在 `docs/superpowers/specs/`、`docs/superpowers/plans/`。

## 指令

技術棧：Java 25、Spring Boot 4.1.1、Maven（使用 wrapper）、Lombok。Windows 環境用 `mvnw.cmd`（Git Bash 下用 `./mvnw`）。

```bash
./mvnw compile                                   # 編譯
./mvnw test                                      # 跑全部測試
./mvnw -pl bancho-irc test -Dtest=OsuIrcClientTest                      # 跑單一模組的單一測試類別
./mvnw -pl bancho-irc test -Dtest=OsuIrcClientTest#answersServerPing    # 跑單一測試方法
./mvnw -pl bancho-shell -am test -Dtest=MatchCommandsTest -Dsurefire.failIfNoSpecifiedTests=false   # 連同依賴模組一起編譯
./mvnw package                                   # 打包；可執行 jar 在 bancho-shell/target/
java -jar bancho-shell/target/bancho-shell-*.jar                    # 啟動（終端機輸入要在真正的終端機裡）
./mvnw -pl bancho-shell -am compile spring-boot:run                 # 開發時啟動（帶 --enable-native-access=ALL-UNNAMED，工作目錄為專案根目錄）
pwsh bancho-shell/src/dist/package-windows.ps1 -Version X.Y.Z -Jar bancho-shell/target/bancho-shell-<版本>.jar -OutDir dist   # jpackage 打成內含 runtime 的 Windows zip（附使用說明.txt；log 與指令歷史寫在解壓縮資料夾內）
```

## Maven 模組

根目錄 `pom.xml`（`cc.alexlin7.bancho:bancho-parent`，packaging `pom`）繼承 `spring-boot-starter-parent`，dependency 與 plugin 版本都由 Boot 管理，升級 Boot 只要改 parent 版本。發布的函式庫也沿用它：parent 沒有宣告 `<dependencies>`，不會增加使用者的依賴，`bancho-irc` 的 runtime 依賴仍只有 `slf4j-api`；日後若要讓發布的 pom 不帶 parent 鏈，把 `flatten-maven-plugin` 改成 `ossrh` 模式，不要拔掉 starter-parent 自己維護 plugin 版本。根 pom 集中管理內部模組與 Boot 不管理的版本（Spring Shell、OTel appender），所有模組共用 Lombok 與 annotation processor 設定。子模組不會繼承 `<name>`，每個模組都要自己寫。

| 模組 | 內容 |
|---|---|
| `bancho-irc` | `irc` 套件，純 Java，compile 依賴只有 `slf4j-api`（不可加入 Spring 或 log 後端）；另外產生 test-jar（`FakeIrcServer`、`RecordingIrcSender`），其他模組以 `<type>test-jar</type>` 依賴。test-jar 不能拆成獨立模組：它依賴 irc，而 irc 自己的測試也要用它，會形成循環 |
| `bancho-irc-spring` | `irc.spring` 套件：`osu.irc.*` 綁定與 auto-configuration；依賴 `spring-boot-autoconfigure` 而不是 starter |
| `bancho-match` | `match` 套件與 `banchobot/` 樣本，純 Java，compile 依賴只有 `bancho-irc` 與 `slf4j-api`（不可加入 Spring） |
| `bancho-match-spring` | `match.spring` 套件：`bancho.match.*` 綁定、範本檢查與 auto-configuration，把房間事件轉成 Spring 事件 |
| `bancho-observability` | `observability` 套件、`logback-spring.xml`、`application-otel.properties`。目前沒有前端依賴它（shell 已移除，避免把 OTLP 與 protobuf 帶進給測試者的版本），留給日後需要匯出的前端（例如 Discord） |
| `bancho-shell` | 唯一可執行的模組：`BanchoShellApplication`、`shell` 套件、`application.properties` |
| `bancho-discord` | 尚未實作 |

- 程式碼直接 import 的內部模組要在自己的 pom 明確宣告，不靠遞移帶入（`dependency:analyze` 的 used undeclared）；純 Java 模組（irc、match）也要自己宣告 `slf4j-api`。Spring 模組的 `slf4j-api` 與 Spring 本身的 jar（`spring-context`、`spring-boot` 等）視為 Boot 的一部分，不逐一宣告。前端的 `*-spring` 模組只在執行期作用，analyze 報 unused declared 是誤報。
- `spring-boot-maven-plugin` 只能宣告在可執行的模組（函式庫被 repackage 後就無法被依賴）；根 pom 設 `spring-boot.run.skip=true`，可執行模組改成 `false`，`-am` 時其他模組才不會被 `spring-boot:run`。
- package 名稱維持 `cc.alexlin7.bancho.<feature>`，`BanchoShellApplication` 的元件掃描會涵蓋其他模組的 bean；同一個 package 不要分散在多個模組。
- `application.properties` 不能放在函式庫模組，會和前端的同名檔互相遮蔽；各模組測試的 `application.properties` 也會遮蔽 main 的同名檔。
- 所有模組共用一個版本：根 pom 的 `<revision>` 屬性，子模組的 `<parent><version>` 寫 `${revision}`，自己不寫版本。`flatten-maven-plugin`（`resolveCiFriendliesOnly`）在 install／deploy 時把 pom 中的 `${revision}` 換成實際版本（產生 `.flattened-pom.xml`，已 ignore），不能移除，否則發布的 pom 會留下使用者解析不了的 `${revision}`。

## 發布

### Maven Central（函式庫）

只發布 `bancho-parent`、`bancho-irc`（含 test-jar）、`bancho-irc-spring`；match 的 API（`MatchEvent` 的欄位等）還會隨新樣本調整，暫不發布。其他模組設 `bancho.publish.skip=true`（同時略過 sources、javadoc、簽章與 `skipPublishing`）。namespace `cc.alexlin7` 以 `alexlin7.cc` 的 DNS TXT 驗證。授權 MIT（根目錄 `LICENSE`）。

發布一律從 main 進行，Central 上的版本必須對應一個 tag（已發布的版本永久存在，不能刪除或覆蓋）：

```bash
# 把根 pom 的 <revision> 改成 X.Y.Z（Central 不收 -SNAPSHOT）
git commit -am "build: 版本 X.Y.Z" && git tag -a vX.Y.Z -m "bancho X.Y.Z"
./mvnw -P release -DskipTests -Dgpg.skip -Dbancho.central.skip-upload=true deploy          # 選擇性：只產生 target/central-publishing/central-bundle.zip 檢查內容
./mvnw -P release deploy -DskipTests "-Dgpg.executable=C:/Program Files/GnuPG/bin/gpg.exe" # 簽章並上傳，停在 VALIDATED
# 到 https://central.sonatype.com/publishing/deployments 按 Publish（有問題就 Drop，版本號可重用），再 git push origin vX.Y.Z
# 把 <revision> 改成 X.Y.(Z+1)-SNAPSHOT 並 commit
```

- 發布時改檔案並 commit，不要用 `-Drevision=X.Y.Z` 從命令列傳入：tag 指向的 commit 裡的版本會和 Central 對不上。應用程式模組（shell、discord）的版本只影響 jar 檔名，不需要為了它們發布新的函式庫版本。
- `deploy` 預設不自動 Publish（沒有設 `autoPublish`）。
- GPG 金鑰（ed25519，Central 接受）在 Gpg4win 的 `%APPDATA%\gnupg`；Git Bash 自帶的 gpg 讀的是另一個空的 `~/.gnupg`，所以要以 `-Dgpg.executable` 指定 Gpg4win 的 gpg。passphrase 由 Gpg4win 的 pinentry 視窗輸入。
- `release` profile 用 `central-publishing-maven-plugin`（0.12.0 修正了 Maven 3.10.0 被拒的問題；不支援 Maven 4；它與 `maven-gpg-plugin` 不受 Boot 管理，版本寫在根 pom），憑證是 `~/.m2/settings.xml` 中 id 為 `central` 的 server（Central Portal 產生的 user token）。
- skip 類設定以屬性串接（`maven.javadoc.skip`、`gpg.skip` 等），不要寫死在 plugin configuration，否則命令列的 `-D` 無法覆寫。
- javadoc 以 `doclint=all,-missing` 執行，格式錯誤（例如 record 類別上的 `@throws`）會讓發布失敗。

### shell 測試版（GitHub）

以 `shell-vX.Y.Z` tag 發布：`git tag -a shell-vX.Y.Z -m "bancho-shell X.Y.Z" && git push origin shell-vX.Y.Z`。`.github/workflows/shell-release.yml` 在 Windows runner 上跑測試、打包，建立 GitHub pre-release，附 `bancho-shell-X.Y.Z.jar` 與 `bancho-shell-X.Y.Z-windows.zip`（`package-windows.ps1` 產生）。版本取自 tag，不改 pom、不 commit，與 `<revision>` 及 Central 無關（jar 內的 Maven 版本可以和檔名不同）；`v*` tag 專用於 Central。tag 必須是三段數字，jpackage 的 `--app-version` 不接受其他格式。

## 架構

依功能分 package，依賴方向只能是 `shell → match → irc`；Spring 整合放在各自的 `*.spring` 模組（`match.spring → match`、`match.spring → irc.spring → irc`），只由前端引入（`irc` 不認識房間，`match` 不認識前端也不認識 Spring）。只在 feature 內使用的類別設為 package-private。

訊息流向：Bancho → connection thread（解析）→ inbound queue → dispatcher → 各 `IrcMessageListener`（`BanchoBotListener` → `MatchService` → Spring 事件 → `KnownUsers`；`ShellMessagePrinter`）→ `IrcSender` → outbound queue → sender thread → Bancho。

跨模組的 Spring 規則：

- 實作 `IrcMessageListener` 的 Spring bean 會自動註冊進 client。不是 listener 的 bean 可以直接注入 `OsuIrcClient`／`IrcSender`；listener 要回話時則注入 `ObjectProvider<IrcSender>`，因為直接注入會和 client 的 `@Bean` 方法形成循環依賴，Boot 預設禁止。
- 應用程式以 `@EventListener` 接收 `MatchEvent`。Spring 事件同步在 IRC dispatcher thread 上執行，依訊息到達順序處理；耗時的 listener 要自行轉交其他 thread。

## 慣例

- 一律用 Lombok `@Slf4j` 記 log，不要用 `System.out` / `System.err`。log 前綴依 feature：`[osu! IRC]`、`[match]`、`[shell]`。需要可查詢的欄位時用 SLF4J key-value API（`log.atInfo().addKeyValue(...)`），OTel 會轉成 attribute。
- 程式註解與 log 訊息使用繁體中文。
- 會被 SonarQube 掃描：`InterruptedException` 要重設中斷旗標，資源用 try-with-resources 關閉，測試避免 `Thread.sleep`（改用 `FakeIrcServer.awaitLine` / `awaitConnection` 這類有時限的等待）。
- 測試一律不連到真的 Bancho：連線行為用 `irc.FakeIrcServer`（本機假 IRC server），記錄送訊內容用 `irc.RecordingIrcSender`；會載入 `OsuIrcAutoConfiguration` 的測試要設 `osu.irc.auto-start=false`（細節見 `bancho-irc-spring/AGENTS.md`）。
