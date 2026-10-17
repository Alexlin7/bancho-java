package cc.alexlin7.bancho.irc;

/**
 * {@link OsuIrcClient} 的連線狀態，以 {@link IrcConnectionListener} 通知。
 */
public enum IrcConnectionState {

    /** 尚未開始連線：沒有帳密，或還沒呼叫 {@link OsuIrcClient#start()}。 */
    IDLE,
    /** 連線中、登入中，或斷線後等待重連。 */
    CONNECTING,
    /** 收到 001，登入完成。 */
    REGISTERED,
    /** 收到 464 或 "Bad authentication"，已停止重連；可再以 {@link OsuIrcClient#login} 重試。 */
    AUTH_FAILED,
    /** 已呼叫 {@link OsuIrcClient#close()}。 */
    CLOSED
}
