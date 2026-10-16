package idv.alexlin7.tw.bancho.shell;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * shell 的目前房間。由 shell thread 設定，也會在 make 完成時由 IRC dispatcher thread 設定。
 */
@Component
public class ShellSession {

    private volatile Long currentRoom;

    public Optional<Long> currentRoom() {
        return Optional.ofNullable(currentRoom);
    }

    public void use(long matchId) {
        currentRoom = matchId;
    }
}
