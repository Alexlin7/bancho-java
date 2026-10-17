package cc.alexlin7.bancho.shell;

import java.util.Optional;

import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import cc.alexlin7.bancho.irc.IrcConnectionState;
import cc.alexlin7.bancho.irc.OsuIrcClient;

/**
 * 以互動提示登入 Bancho（未設定 {@code OSU_IRC_USERNAME} / {@code OSU_IRC_PASSWORD} 時使用）。
 * 帳密只交給 client，不寫到硬碟；密碼以 {@link SecretPrompt} 遮蔽輸入，不會留在指令歷史。
 */
@Component
public class LoginCommands {

    /** 用環境變數登入的人可能忘了自己有設，提示這個可能性與換帳號的方法。 */
    private static final String ENV_HINT = "（有設定 OSU_IRC_USERNAME／OSU_IRC_PASSWORD 環境變數時，啟動就會自動登入）；"
            + "要換帳號請修改或清除環境變數後重開程式";
    private static final String PASSWORD_PROMPT =
            "IRC 密碼（不是帳號密碼，到 https://osu.ppy.sh/home/account/edit#legacy-api 取得）：";

    private final OsuIrcClient client;
    private final TextPrompt textPrompt;
    private final SecretPrompt secretPrompt;

    public LoginCommands(OsuIrcClient client, TextPrompt textPrompt, SecretPrompt secretPrompt) {
        this.client = client;
        this.textPrompt = textPrompt;
        this.secretPrompt = secretPrompt;
    }

    @Command(name = "login", group = "帳號", description = "登入 Bancho：依提示輸入 osu! 帳號與 IRC 密碼（不是帳號密碼）")
    public String login(CommandContext context) {
        String invalid = InputGuard.check(context, 0);
        if (invalid != null) {
            return invalid;
        }
        String account = client.username().orElse("");
        IrcConnectionState state = client.state();
        if (state == IrcConnectionState.CONNECTING) {
            return "錯誤：正在以 " + account + " 連線 Bancho" + ENV_HINT;
        }
        if (state == IrcConnectionState.REGISTERED) {
            return "錯誤：已登入為 " + account + ENV_HINT;
        }
        return RoomResolver.guarded(() -> {
            Optional<String> username = textPrompt.read("osu! 帳號：");
            if (username.isEmpty()) {
                return "已取消登入";
            }
            Optional<String> password = secretPrompt.read(PASSWORD_PROMPT);
            if (password.isEmpty()) {
                return "已取消登入";
            }
            client.login(username.get(), password.get());
            return "登入中：" + username.get();
        });
    }
}
