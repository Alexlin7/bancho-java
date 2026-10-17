package cc.alexlin7.bancho.shell;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import cc.alexlin7.bancho.match.MatchRoom;
import cc.alexlin7.bancho.match.MatchService;

/**
 * 決定指令作用的房間（--room 優先，否則目前房間），並把參數錯誤轉成給使用者看的文字。
 */
final class RoomResolver {

    static final String NO_ROOM = "請先用 make / use / attach 選擇房間，或加上 --room <房號>";

    private final MatchService matchService;
    private final ShellSession session;

    RoomResolver(MatchService matchService, ShellSession session) {
        this.matchService = matchService;
        this.session = session;
    }

    String onRoom(Long room, Function<MatchRoom, String> action) {
        Optional<Long> target = room != null ? Optional.of(room) : session.currentRoom();
        if (target.isEmpty()) {
            return NO_ROOM;
        }
        Optional<MatchRoom> found = matchService.room(target.get());
        if (found.isEmpty()) {
            return "找不到房間：" + target.get();
        }
        if (found.get().state().closed()) {
            return "房間已關閉：" + found.get().channel();
        }
        return guarded(() -> action.apply(found.get()));
    }

    /** 參數錯誤與狀態錯誤轉成文字，不讓例外冒到 shell。 */
    static String guarded(Supplier<String> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "錯誤：" + e.getMessage();
        }
    }
}
