package cc.alexlin7.bancho.shell;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import cc.alexlin7.bancho.irc.IrcMessage;
import cc.alexlin7.bancho.irc.IrcMessageListener;
import cc.alexlin7.bancho.match.event.MatchEvent;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerInvited;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerMoved;
import cc.alexlin7.bancho.match.event.MatchEvent.SlotStatus;
import cc.alexlin7.bancho.match.spring.MatchPresetProperties;

/**
 * 這次執行中看過的使用者名稱，供 invite / addref 的 Tab 補全使用。
 * 只收房間事件裡的玩家、私訊對象與範本裡的名單，不收頻道裡所有發言者。
 */
@Component
public class KnownUsers implements IrcMessageListener {

    private static final String BANCHO_BOT = "BanchoBot";

    private final Set<String> names = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    public KnownUsers(MatchPresetProperties presets) {
        presets.presets().values().forEach(preset -> {
            preset.invite().forEach(this::add);
            preset.referees().forEach(this::add);
        });
    }

    public List<String> all() {
        return List.copyOf(names);
    }

    private void add(String name) {
        if (name != null && !name.isBlank()) {
            names.add(name.strip());
        }
    }

    @EventListener
    public void onMatchEvent(MatchEvent event) {
        switch (event) {
            case PlayerInvited invited -> add(invited.username());
            case PlayerJoined joined -> add(joined.username());
            case PlayerMoved moved -> add(moved.username());
            case SlotStatus status -> add(status.username());
            default -> {
                // 其他事件沒有玩家名稱
            }
        }
    }

    @Override
    public void onMessage(IrcMessage message) {
        boolean privateMessage = !message.param(0).startsWith("#");
        if (privateMessage && !BANCHO_BOT.equals(message.nick())) {
            add(message.nick());
        }
    }
}
