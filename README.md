# bancho-java

osu! Bancho IRC（`cho.ppy.sh:6667`）的 Java client，以及建立在它之上的多人房裁判機器人。

**需要 Java 25 以上。**

## 函式庫

發布在 Maven Central 的只有以下兩個函式庫：

| artifact | 內容 |
|---|---|
| `cc.alexlin7.bancho:bancho-irc` | 不依賴任何框架的 IRC client，runtime 依賴只有 `slf4j-api` |
| `cc.alexlin7.bancho:bancho-irc-spring` | Spring Boot 4 auto-configuration，以 `osu.irc.*` 設定 client |

版本見 [Maven Central](https://central.sonatype.com/namespace/cc.alexlin7)。

IRC 密碼要從 [osu! 帳號設定的 Legacy API](https://osu.ppy.sh/home/account/edit#legacy-api) 取得，不是帳號密碼。

### 純 Java

```xml
<dependency>
    <groupId>cc.alexlin7.bancho</groupId>
    <artifactId>bancho-irc</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
var settings = new OsuIrcSettings(null, 0, "your_name", System.getenv("OSU_IRC_PASSWORD"), null); // host、port、timing 用預設值
IrcMessageListener printer = message -> System.out.println(message.nick() + ": " + message.trailing());

try (var client = new OsuIrcClient(settings, List.of(printer))) {
    client.start();
    client.sendMessage("BanchoBot", "!roll"); // 登入完成前送的訊息會排隊，登入後依序送出
    Thread.sleep(Duration.ofMinutes(1));      // client 的工作 thread 都是 virtual thread，main 結束 JVM 就會結束
}
```

要回話的 listener 需要拿 client 當 `IrcSender`，在 client 建好之後以 `client.addListener(...)` 加入。`addListener` 從 0.1.1 開始提供，尚未發布到 Maven Central；在那之前請 clone 本 repo 執行 `./mvnw install`，改用 `0.1.1-SNAPSHOT`。

client 斷線後會自動重連；登入失敗（帳密錯誤）時停止重連。出站訊息預設每 500ms 送一則，符合一般帳號的頻率限制。

### Spring Boot

```xml
<dependency>
    <groupId>cc.alexlin7.bancho</groupId>
    <artifactId>bancho-irc-spring</artifactId>
    <version>0.1.0</version>
</dependency>
```

```properties
osu.irc.username=your_name
osu.irc.password=${OSU_IRC_PASSWORD}
```

context 啟動時自動連線，關閉時送出 QUIT（`osu.irc.auto-start=false` 可關閉自動連線）。所有 `IrcMessageListener` bean 都會註冊到 client；要回話時注入 `ObjectProvider<IrcSender>`，直接注入 `IrcSender` 會和 client 形成循環依賴：

```java
@Component
class PingListener implements IrcMessageListener {

    private final ObjectProvider<IrcSender> sender;

    PingListener(ObjectProvider<IrcSender> sender) {
        this.sender = sender;
    }

    @Override
    public void onMessage(IrcMessage message) {
        if (message.trailing().equals("!ping")) {
            sender.getObject().sendMessage(message.nick(), "pong");
        }
    }
}
```

其他設定：`osu.irc.host`、`osu.irc.port`，以及 `osu.irc.timing.*`（`connect-timeout`、`read-timeout`、`send-interval`、`reconnect-min-delay`、`reconnect-max-delay`）。bot 帳號可把 `send-interval` 調到 `200ms`。

## 授權

[MIT](LICENSE)
