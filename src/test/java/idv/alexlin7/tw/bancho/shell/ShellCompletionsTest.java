package idv.alexlin7.tw.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.shell.core.command.CommandOption;
import org.springframework.shell.core.command.completion.CompletionContext;
import org.springframework.shell.core.command.completion.CompletionProposal;
import org.springframework.shell.core.command.completion.CompletionProvider;

import idv.alexlin7.tw.bancho.irc.RecordingIrcSender;
import idv.alexlin7.tw.bancho.match.MatchService;
import idv.alexlin7.tw.bancho.match.preset.MatchPreset;
import idv.alexlin7.tw.bancho.match.preset.MatchPresetProperties;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchClosed;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.MatchCreated;
import idv.alexlin7.tw.bancho.match.event.MatchEvent.PlayerJoined;

class ShellCompletionsTest {

    private final MatchService matchService = new MatchService(new RecordingIrcSender(), Duration.ofSeconds(1));
    private final KnownUsers knownUsers = new KnownUsers(new MatchPresetProperties(Map.of()));
    private final ShellCompletions completions = new ShellCompletions();

    /** 主要參數：最後一個字是正在輸入的字。 */
    private static CompletionContext typing(String... words) {
        String last = words[words.length - 1];
        return new CompletionContext(List.of(words), words.length - 1, last.length(), null, null);
    }

    private static CompletionContext typingRoomOption(String... words) {
        String last = words[words.length - 1];
        return new CompletionContext(List.of(words), words.length - 1, last.length(), null,
                CommandOption.with().longName("room").build());
    }

    private static List<String> values(CompletionProvider provider, CompletionContext context) {
        return provider.apply(context).stream().map(CompletionProposal::value).toList();
    }

    @Test
    void filtersByCurrentWordPrefixIgnoringCase() {
        knownUsers.onMatchEvent(new PlayerJoined(1, "Alexlin86", 1));
        knownUsers.onMatchEvent(new PlayerJoined(1, "alice", 2));
        knownUsers.onMatchEvent(new PlayerJoined(1, "Bob", 3));

        assertThat(values(completions.userCompletion(knownUsers, matchService), typing("invite", "al")))
                .containsExactlyInAnyOrder("Alexlin86", "alice");
    }

    @Test
    void roomCompletionListsOpenRoomsWithNames() {
        matchService.onMatchEvent(new MatchCreated(1, "a"));
        matchService.onMatchEvent(new MatchCreated(2, "b"));
        matchService.onMatchEvent(new MatchCreated(3, "c"));
        matchService.onMatchEvent(new MatchClosed(3));

        List<CompletionProposal> proposals = completions.roomCompletion(matchService).apply(typing("use", ""));

        assertThat(proposals).extracting(CompletionProposal::value).containsExactly("1", "2");
        assertThat(proposals).extracting(CompletionProposal::description).containsExactly("a", "b");
    }

    @Test
    void modsCompletionOffersModList() {
        assertThat(values(completions.modsCompletion(matchService), typing("mods", "h")))
                .containsExactly("HT", "HR", "HD");
    }

    @Test
    void roomOptionIsCompletedWithRoomIdsEverywhere() {
        matchService.onMatchEvent(new MatchCreated(7, "x"));

        assertThat(values(completions.userCompletion(knownUsers, matchService), typingRoomOption("invite", "A", "--room", "")))
                .containsExactly("7");
        assertThat(values(completions.roomOptionCompletion(matchService), typingRoomOption("map", "1", "--room", "")))
                .containsExactly("7");
    }

    @Test
    void presetOptionIsCompletedWithPresetNames() {
        var presets = new MatchPresetProperties(Map.of("practice",
                new MatchPreset(null, null, null, null, List.of("HD"), null, null, null, null)));
        var context = new CompletionContext(List.of("make", "r", "--preset", "pr"), 3, 2, null,
                CommandOption.with().longName("preset").build());

        assertThat(values(completions.makeCompletion(presets), context)).containsExactly("practice");
    }

    @Test
    void mapCompletionOffersModeNamesForSecondArgument() {
        var provider = completions.mapCompletion(matchService);
        var proposals = provider.apply(typing("map", "2453358", ""));
        assertThat(proposals).extracting(CompletionProposal::value).containsExactly("osu", "taiko", "catch", "mania");
        assertThat(proposals).extracting(CompletionProposal::description).containsExactly("0", "1", "2", "3");
        assertThat(values(provider, typing("map", "2453358", "MA"))).containsExactly("mania");
    }

    @Test
    void mapCompletionCountsOnlyPositionalWords() {
        var provider = completions.mapCompletion(matchService);
        assertThat(values(provider, typing("map", "--room", "42", "2453358", "")))
                .containsExactly("osu", "taiko", "catch", "mania");
        assertThat(values(provider, typing("map", "--room=42", "2453358", "")))
                .containsExactly("osu", "taiko", "catch", "mania");
        assertThat(values(provider, typing("map", "--room", "42", ""))).isEmpty();
    }

    @Test
    void mapCompletionOffersNothingForBeatmapId() {
        assertThat(values(completions.mapCompletion(matchService), typing("map", ""))).isEmpty();
    }

    @Test
    void mapCompletionStillCompletesRoomOption() {
        matchService.attach(7);
        assertThat(values(completions.mapCompletion(matchService), typingRoomOption("map", "1", "--room", "")))
                .containsExactly("7");
    }

    @Test
    void mpCompletesOnlyFirstPositionalArgument() {
        var provider = completions.mpCompletion(matchService);
        assertThat(values(provider, typing("mp", "h"))).containsExactly("host");
        assertThat(values(provider, typing("mp", "host", ""))).isEmpty();
        assertThat(values(provider, typing("mp", "--room", "42", "h"))).containsExactly("host");
        matchService.attach(7);
        assertThat(values(provider, typingRoomOption("mp", "host", "--room", ""))).containsExactly("7");
    }

    @Test
    void roomOptionCompletionSuggestsNothingForPrimaryArgument() {
        matchService.onMatchEvent(new MatchCreated(7, "x"));

        assertThat(values(completions.roomOptionCompletion(matchService), typing("map", ""))).isEmpty();
    }
}
