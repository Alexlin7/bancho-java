# bancho-match-spring

`match.spring` 套件：`bancho.match.*` 設定綁定、範本檢查與 auto-configuration。`MatchProperties`、`MatchPresetProperties` 都在這個套件。

## `MatchAutoConfiguration`

- 在有 `IrcSender` bean 時註冊 `MatchService`（使用者自訂時退出）與 `BanchoBotListener`，listener 清單是 `MatchService` → `ApplicationEventPublisher`，所以應用程式以 `@EventListener` 接收 `MatchEvent`，不必實作 `MatchEventListener`。
- `MatchService` 以 `ObjectProvider` 延後取得：直接注入會形成 client → `BanchoBotListener` → `MatchService` → client 的循環。
- 在有 `OsuIrcClient` 時以 `setOutboundRedactor(BanchoRedaction::redactOutbound)` 遮蔽 `!mp password <x>`，DEBUG log、截斷 WARN 與樣本檔都不會留下房間密碼。
- 啟動時對全部範本執行 `MatchRoom.validatePreset`，避免開房後才失敗。
- Spring 事件預設同步在發布者的 thread（IRC dispatcher）上執行，所以所有 `@EventListener` 依訊息到達順序處理；耗時的 listener 要自行轉交其他 thread。

## 範本設定（`bancho.match.presets.<名稱>.*`）

欄位與檢查規則見 `bancho-match/AGENTS.md` 的 preset 一節；`team-mode`／`score-mode` 在設定檔中可寫數字或別名。

```yaml
bancho:
  match:
    presets:
      practice:
        team-mode: 0
        score-mode: 3
        size: 2
        mods: [HD, HR]
        invite: [Alexlin86]
```
