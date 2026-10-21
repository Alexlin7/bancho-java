package cc.alexlin7.bancho.shell;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.core.command.CommandOption;
import org.springframework.shell.core.command.completion.CompletionContext;
import org.springframework.shell.core.command.completion.CompletionProposal;
import org.springframework.shell.core.command.completion.CompletionProvider;

import cc.alexlin7.bancho.match.BanchoMode;
import cc.alexlin7.bancho.match.GameMode;
import cc.alexlin7.bancho.match.MatchRoom;
import cc.alexlin7.bancho.match.MatchService;
import cc.alexlin7.bancho.match.MatchState;
import cc.alexlin7.bancho.match.MatchState.Slot;
import cc.alexlin7.bancho.match.ModNames;
import cc.alexlin7.bancho.match.ScoreMode;
import cc.alexlin7.bancho.match.Team;
import cc.alexlin7.bancho.match.TeamMode;
import cc.alexlin7.bancho.match.spring.MatchPresetProperties;

/**
 * Tab 補全。Spring Shell 會自己補選項名稱（例如 --room），這裡只提供選項值與主要參數的候選。
 * bean 名稱即為 {@code @Command(completionProvider = ...)} 使用的名稱。
 */
@Configuration
class ShellCompletions {

    static final List<String> MP_SUBCOMMANDS = List.of(
            "aborttimer", "clearhost", "kick", "listrefs", "lock", "move", "removeref", "size",
            "unlock");

    private static final Set<String> OPTIONS_WITH_VALUE = Set.of(
            "--room"
    );

    private static final String ROOM_OPTION = "room";
    private static final String PRESET_OPTION = "preset";

    /**
     * make：只補 --preset 的值（範本名稱）。
     */
    @Bean
    CompletionProvider makeCompletion(MatchPresetProperties presets) {
        return context -> {
            CommandOption option = context.getCommandOption();
            if (option == null || !PRESET_OPTION.equals(option.longName())) {
                return List.of();
            }
            return filter(presets.presets().keySet().stream().sorted().map(CompletionProposal::new).toList(), context);
        };
    }

    /**
     * use、attach：主要參數就是房號。
     */
    @Bean
    CompletionProvider roomCompletion(MatchService matchService) {
        return context -> filter(rooms(matchService), context);
    }

    /**
     * 其他房間指令：只補 --room 的值。
     */
    @Bean
    CompletionProvider roomOptionCompletion(MatchService matchService) {
        return withRoomOption(matchService, context -> List.of());
    }

    /**
     * invite、addref：所有已知使用者名稱。
     */
    @Bean
    CompletionProvider userCompletion(KnownUsers knownUsers, MatchService matchService) {
        return withRoomOption(matchService,
                context -> filter(knownUsers.all().stream().map(CompletionProposal::new).toList(), context));
    }

    /** host：只補目標房間中的成員。 */
    @Bean
    CompletionProvider memberCompletion(MatchService matchService, ShellSession session) {
        return withRoomOption(matchService, context -> filter(members(matchService, session, context), context));
    }

    /** team：先選顏色，再補目標房間成員。 */
    @Bean
    CompletionProvider teamCompletion(MatchService matchService, ShellSession session) {
        return withRoomOption(matchService, context -> filter(positionalIndex(context) == 0
                ? List.of(new CompletionProposal("blue"), new CompletionProposal("red"))
                : members(matchService, session, context), context));
    }

    private static List<CompletionProposal> members(MatchService matchService, ShellSession session,
                                                   CompletionContext context) {
        return targetRoom(context, session).flatMap(matchService::room).map(room -> {
            MatchState state = room.state();
            return state.slots().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> new CompletionProposal(entry.getValue().username())
                            .description(memberDescription(entry.getKey(), entry.getValue(), state.host())))
                    .toList();
        }).orElse(List.of());
    }

    private static Optional<Long> targetRoom(CompletionContext context, ShellSession session) {
        List<String> words = context.getWords();
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals("--")) {
                break;
            }
            String value = null;
            if (word.equals("--room") && i + 1 < words.size()) {
                value = words.get(i + 1);
            } else if (word.startsWith("--room=")) {
                value = word.substring("--room=".length());
            }
            if (value != null) {
                try {
                    return Optional.of(Long.valueOf(value));
                } catch (NumberFormatException _) {
                    return Optional.empty();
                }
            }
        }
        return session.currentRoom();
    }

    static String memberDescription(int slot, Slot member, String host) {
        String team = member.team() == null ? "" : (member.team() == Team.BLUE ? "藍" : "紅") + " · ";
        return team + "slot " + slot + (member.username().equals(host) ? " · host" : "");
    }

    @Bean
    CompletionProvider modsCompletion(MatchService matchService) {
        return withRoomOption(matchService,
                context -> filter(ModNames.KNOWN.stream().map(CompletionProposal::new).toList(), context));
    }

    @Bean
    CompletionProvider mapCompletion(MatchService matchService) {
        return withRoomOption(matchService, context -> positionalIndex(context) == 1
                ? filter(modeProposals(GameMode.values()), context)
                : List.of());
    }

    @Bean
    CompletionProvider setCompletion(MatchService matchService) {
        return withRoomOption(matchService, context -> {
            List<CompletionProposal> proposals = switch (positionalIndex(context)) {
                case 0 -> modeProposals(TeamMode.values());
                case 1 -> modeProposals(ScoreMode.values());
                default -> List.of();
            };
            return filter(proposals, context);
        });
    }

    private static List<CompletionProposal> modeProposals(BanchoMode[] modes) {
        return Arrays.stream(modes).flatMap(mode -> mode.aliases().stream()
                .map(alias -> new CompletionProposal(alias).description(Integer.toString(mode.id())))).toList();
    }

    @Bean
    CompletionProvider mpCompletion(MatchService matchService) {
        return withRoomOption(matchService, context -> positionalIndex(context) == 0
                ? filter(MP_SUBCOMMANDS.stream().map(CompletionProposal::new).toList(), context)
                : List.of());
    }

    /**
     * 選項及其值不佔位置參數；words[0] 是指令名稱。
     */
    private static int positionalIndex(CompletionContext context) {
        int position = 0;
        List<String> words = context.getWords();
        boolean skipNext = false;

        for (int i = 1; i < context.getWordIndex(); i++) {
            if (skipNext) {
                skipNext = false;
                continue;
            }

            String word = words.get(i);
            if (OPTIONS_WITH_VALUE.contains(word)) {
                skipNext = true;
            } else if (!word.startsWith("-")) {
                position++;
            }
        }
        return position;
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

    /**
     * 依游標所在的字做前綴比對，不分大小寫。
     */
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
