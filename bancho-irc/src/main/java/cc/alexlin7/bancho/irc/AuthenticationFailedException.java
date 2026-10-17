package cc.alexlin7.bancho.irc;

import java.io.IOException;

/**
 * Bancho 拒絕登入（464 或 "Bad authentication token"），重試也不會成功。
 */
public class AuthenticationFailedException extends IOException {

    public AuthenticationFailedException(String message) {
        super(message);
    }

    public AuthenticationFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuthenticationFailedException(Throwable cause) {
        super(cause);
    }
}
