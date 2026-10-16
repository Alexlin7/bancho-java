package idv.alexlin7.tw.bancho.shell;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.core.command.CommandOption;
import org.springframework.shell.core.command.completion.CompletionContext;
import org.springframework.shell.core.command.completion.CompletionProposal;
import org.springframework.shell.core.command.completion.CompletionProvider;

import idv.alexlin7.tw.bancho.match.MatchRoom;
import idv.alexlin7.tw.bancho.match.MatchService;

/**
 * Tab 補全。Spring Shell 會自己補選項名稱（例如 --room），這裡只提供選項值與主要參數的候選。
 * bean 名稱即為 {@code @Command(completionProvider = ...)} 使用的名稱。
 */
@Configuration
class ShellCompletions {

    /** osu! wiki「Game modifier」的 osu!stable 縮寫；Freemod 是 !mp mods 的特殊值。 */
    static final List<String> MODS = List.of(
            "EZ", "NF", "HT", "HR", "SD", "PF", "DT", "NC", "HD", "FL", "RL", "AP", "SO", "SV2", "Freemod");

    private static final String ROOM_OPTION = "room";

    /** use、attach：主要參數就是房號。 */
    @Bean
    CompletionProvider roomCompletion(MatchService matchService) {
        return context -> filter(rooms(matchService), context);
    }

    /** 其他房間指令：只補 --room 的值。 */
    @Bean
    CompletionProvider roomOptionCompletion(MatchService matchService) {
        return withRoomOption(matchService, context -> List.of());
    }

    /** invite、addref：使用者名稱。 */
    @Bean
    CompletionProvider userCompletion(KnownUsers knownUsers, MatchService matchService) {
        return withRoomOption(matchService,
                context -> filter(knownUsers.all().stream().map(CompletionProposal::new).toList(), context));
    }

    @Bean
    CompletionProvider modsCompletion(MatchService matchService) {
        return withRoomOption(matchService,
                context -> filter(MODS.stream().map(CompletionProposal::new).toList(), context));
    }

    private static CompletionProvider withRoomOption(MatchService matchService,
                                                     Function<CompletionContext, List<CompletionProposal>> primary) {
        return context -> isRoomOption(context.getCommandOption())
                ? filter(rooms(matchService), context)
                : primary.apply(context);
    }

    private static boolean isRoomOption(CommandOption option) {
        return option != null && ROOM_OPTION.equals(option.longName());
    }

    private static List<CompletionProposal> rooms(MatchService matchService) {
        return matchService.openRooms().stream()
                .map(ShellCompletions::roomProposal)
                .toList();
    }

    private static CompletionProposal roomProposal(MatchRoom room) {
        String name = room.state().name();
        return new CompletionProposal(String.valueOf(room.matchId())).description(name == null ? "" : name);
    }

    /** 依游標所在的字做前綴比對，不分大小寫。 */
    static List<CompletionProposal> filter(List<CompletionProposal> proposals, CompletionContext context) {
        String word = context.currentWord();
        if (word == null || word.isEmpty()) {
            return proposals;
        }
        String prefix = word.toLowerCase(Locale.ROOT);
        return proposals.stream()
                .filter(proposal -> proposal.value().toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
