package idv.alexlin7.tw.bancho.match;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import idv.alexlin7.tw.bancho.irc.IrcMessage;
import idv.alexlin7.tw.bancho.match.event.MatchEvent;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.BeatmapChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchClosed;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchCreated;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchStarted;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchAborted;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.ModsChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerInvited;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerJoined;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerLeft;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerMoved;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.SettingsChanged;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.SlotStatus;

/**
 * 把 BanchoBot 的 PRIVMSG 解析成 {@link MatchEvent}。每條規則都必須比對整行，
 * 名稱類欄位以後面的固定文字收尾，所以含空白的玩家名稱也能完整取出。
 */
final class BanchoBotParser {

    static final String BANCHO_BOT = "BanchoBot";

    private static final Pattern MP_CHANNEL = Pattern.compile("#mp_(\\d+)");
    private static final Pattern MATCH_CREATED =
            Pattern.compile("Created the tournament match https://osu\\.ppy\\.sh/mp/(\\d+) (.+)");

    /** 已知但刻意不產生事件的頻道回覆。 */
    private static final List<Pattern> IGNORED = List.of(
            Pattern.compile("Room name: .+"),
            Pattern.compile("Team mode: .+"),
            Pattern.compile("Players: \\d+"),
            Pattern.compile("Started the match"),
            Pattern.compile("Countdown ends in \\d+ seconds?"),
            Pattern.compile("Countdown finished"),
            Pattern.compile("Changed the match password"),
            Pattern.compile("Active mods: .+"));

    private static final List<Rule> CHANNEL_RULES = List.of(
            new Rule("Closed the match", (id, m) -> new MatchClosed(id)),
            new Rule("The match has started!", (id, m) -> new MatchStarted(id)),
            new Rule("Aborted the match", (id, m) -> new MatchAborted(id)),
            new Rule("Disabled all mods, (enabled|disabled) FreeMod",
                    (id, m) -> new ModsChanged(id, List.of(), "enabled".equals(m.group(1)))),
            new Rule("Invited (.+) to the room", (id, m) -> new PlayerInvited(id, m.group(1))),
            new Rule("(.+?) joined in slot (\\d+)\\.",
                    (id, m) -> new PlayerJoined(id, m.group(1), Integer.parseInt(m.group(2)))),
            new Rule("(.+?) moved to slot (\\d+)",
                    (id, m) -> new PlayerMoved(id, m.group(1), Integer.parseInt(m.group(2)))),
            new Rule("(.+?) left the game\\.", (id, m) -> new PlayerLeft(id, m.group(1))),
            new Rule("Changed match settings to (\\d+) slots, (\\S+), (\\S+)",
                    (id, m) -> new SettingsChanged(id, Integer.parseInt(m.group(1)), m.group(2), m.group(3))),
            new Rule("(?:Changed beatmap to|Beatmap:) https://osu\\.ppy\\.sh/b/(\\d+) (.+)",
                    (id, m) -> new BeatmapChanged(id, Long.parseLong(m.group(1)), m.group(2))),
            new Rule("Enabled (.+), (enabled|disabled) FreeMod",
                    (id, m) -> new ModsChanged(id, Arrays.asList(m.group(1).split(", ")), "enabled".equals(m.group(2)))),
            // 名稱後若有「兩個以上空白 + [...]」的附加資訊（例如 host、隊伍），目前沒有真實樣本：
            // 寧可當成未辨識（記 log 蒐集樣本），也不要把附加資訊當成使用者名稱
            new Rule("Slot (\\d+)\\s+(.+?)\\s+https://osu\\.ppy\\.sh/u/(\\d+) (?!.*\\s{2}\\[)(.+)",
                    (id, m) -> new SlotStatus(id, Integer.parseInt(m.group(1)), m.group(2),
                            Long.parseLong(m.group(3)), m.group(4))));

    private BanchoBotParser() {
    }

    static Result parse(IrcMessage message) {
        if (!BANCHO_BOT.equals(message.nick())) {
            return new Result.NotFromBanchoBot();
        }
        String target = message.param(0);
        String text = message.trailing().strip();
        if (!target.startsWith("#")) {
            Matcher created = MATCH_CREATED.matcher(text);
            return created.matches()
                    ? new Result.Parsed(new MatchCreated(Long.parseLong(created.group(1)), created.group(2)))
                    : new Result.Unrecognized(null, text);
        }
        Matcher channel = MP_CHANNEL.matcher(target);
        if (!channel.matches()) {
            return new Result.Unrecognized(null, text);
        }
        return parseChannel(Long.parseLong(channel.group(1)), text);
    }

    private static Result parseChannel(long matchId, String text) {
        for (Rule rule : CHANNEL_RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            if (matcher.matches()) {
                return new Result.Parsed(rule.factory().apply(matchId, matcher));
            }
        }
        if (IGNORED.stream().anyMatch(pattern -> pattern.matcher(text).matches())) {
            return new Result.Ignored();
        }
        return new Result.Unrecognized(matchId, text);
    }

    private record Rule(Pattern pattern, BiFunction<Long, Matcher, MatchEvent> factory) {

        Rule(String regex, BiFunction<Long, Matcher, MatchEvent> factory) {
            this(Pattern.compile(regex), factory);
        }
    }

    sealed interface Result {

        record Parsed(MatchEvent event) implements Result {
        }

        /** 已知但刻意不產生事件的行。 */
        record Ignored() implements Result {
        }

        /** 看不懂的 BanchoBot 訊息；matchId 在私訊或非房間頻道時為 null。 */
        record Unrecognized(Long matchId, String text) implements Result {
        }

        record NotFromBanchoBot() implements Result {
        }
    }
}
