package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.shell.core.command.CommandOption;
import org.springframework.shell.core.command.completion.CompletionContext;
import org.springframework.shell.core.command.completion.CompletionProposal;
import org.springframework.shell.core.command.completion.CompletionProvider;

import cc.alexlin7.bancho.irc.RecordingIrcSender;
import cc.alexlin7.bancho.match.MatchService;
import cc.alexlin7.bancho.match.Team;
import cc.alexlin7.bancho.match.event.MatchEvent.HostChanged;
import cc.alexlin7.bancho.match.preset.MatchPreset;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchClosed;
import cc.alexlin7.bancho.match.event.MatchEvent.MatchCreated;
import cc.alexlin7.bancho.match.event.MatchEvent.PlayerJoined;
import cc.alexlin7.bancho.match.spring.MatchPresetProperties;

class ShellCompletionsTest {

    private final MatchService matchService = new MatchService(new RecordingIrcSender(), Duration.ofSeconds(1));
    private final KnownUsers knownUsers = new KnownUsers(new MatchPresetProperties(Map.of()));
    private final ShellCompletions completions = new ShellCompletions();
    private final ShellSession session = new ShellSession();

    @Test
    void memberCompletionSortsSlotsWithTeamAndHostDescriptions() {
        matchService.onMatchEvent(new PlayerJoined(42, "A", 1, Team.BLUE));
        matchService.onMatchEvent(new PlayerJoined(42, "B", 3, Team.RED));
        matchService.onMatchEvent(new PlayerJoined(42, "C", 2, null));
        matchService.onMatchEvent(new HostChanged(42, "A"));
        session.use(42);
        var proposals = completions.memberCompletion(matchService, session).apply(typing("host", ""));
        assertThat(proposals).extracting(CompletionProposal::value).containsExactly("A", "C", "B");
        assertThat(proposals).extracting(CompletionProposal::description)
                .containsExactly("藍 · slot 1 · host", "slot 2", "紅 · slot 3");
    }

    @Test
    void memberAndTeamCompletionUseExplicitRoomInsteadOfCurrentRoom() {
        matchService.onMatchEvent(new PlayerJoined(42, "A", 1));
        matchService.onMatchEvent(new PlayerJoined(43, "X", 1));
        session.use(42);
        var members = completions.memberCompletion(matchService, session);
        var teams = completions.teamCompletion(matchService, session);
        assertThat(values(members, typing("host", "--room", "43", ""))).containsExactly("X");
        assertThat(values(members, typing("host", "--room=43", ""))).containsExactly("X");
        assertThat(values(teams, typing("team", "--room", "43", "blue", ""))).containsExactly("X");
        assertThat(values(teams, typing("team", "--room=43", "red", ""))).containsExactly("X");
        assertThat(values(teams, typing("team", "--room=43", ""))).containsExactly("blue", "red");
    }

    @Test
    void memberCompletionIsEmptyWithoutRoomOrMembers() {
        var members = completions.memberCompletion(matchService, session);
        assertThat(values(members, typing("host", ""))).isEmpty();
        matchService.attach(42);
        session.use(42);
        assertThat(values(members, typing("host", ""))).isEmpty();
        assertThat(values(members, typing("host", "--room=999", ""))).isEmpty();
    }

    @Test
    void teamCompletionOffersColoursThenRoomMembers() {
        matchService.onMatchEvent(new PlayerJoined(42, "Tomari Mari", 1));
        session.use(42);
        var provider = completions.teamCompletion(matchService, session);
        assertThat(values(provider, typing("team", ""))).containsExactly("blue", "red");
        assertThat(values(provider, typing("team", "R"))).containsExactly("red");
        assertThat(values(provider, typing("team", "blue", "To"))).containsExactly("Tomari Mari");
        assertThat(values(provider, typing("team", "blue", "Tomari", ""))).containsExactly("Tomari Mari");
        assertThat(values(provider, typingRoomOption("team", "--room", ""))).containsExactly("42");
    }

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
        assertThat(proposals).extracting(CompletionProposal::value).containsExactly("osu", "std", "taiko", "catch", "fruits", "ctb", "mania");
        assertThat(proposals).extracting(CompletionProposal::description).containsExactly("0", "0", "1", "2", "2", "2", "3");
        assertThat(values(provider, typing("map", "2453358", "MA"))).containsExactly("mania");
    }

    @Test
    void mapCompletionOffersAcceptedAliasesWithModeIds() {
        var provider = completions.mapCompletion(matchService);
        assertThat(values(provider, typing("map", "2453358", "ST"))).containsExactly("std");
        assertThat(values(provider, typing("map", "2453358", "fr"))).containsExactly("fruits");
        assertThat(values(provider, typing("map", "--room", "42", "2453358", "ct"))).containsExactly("ctb");
        assertThat(provider.apply(typing("map", "2453358", "ct")))
                .extracting(CompletionProposal::description).containsExactly("2");
    }

    @Test
    void mapCompletionCountsOnlyPositionalWords() {
        var provider = completions.mapCompletion(matchService);
        assertThat(values(provider, typing("map", "--room", "42", "2453358", "")))
                .containsExactly("osu", "std", "taiko", "catch", "fruits", "ctb", "mania");
        assertThat(values(provider, typing("map", "--room=42", "2453358", "")))
                .containsExactly("osu", "std", "taiko", "catch", "fruits", "ctb", "mania");
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
    void setCompletesTeamAndScoreAliasesAtTheirPositions() {
        var provider = completions.setCompletion(matchService);
        assertThat(values(provider, typing("set", ""))).containsExactly(
                "h2h", "headtohead", "tag", "tagteam", "tagcoop", "teamvs", "tagvs", "tagteamvs");
        assertThat(values(provider, typing("set", "teamvs", ""))).containsExactly(
                "score", "scorev1", "v1", "accuracy", "acc", "combo", "scorev2", "v2");
        assertThat(values(provider, typing("set", "TAG"))).containsExactly("tag", "tagteam", "tagcoop", "tagvs", "tagteamvs");
        assertThat(values(provider, typing("set", "teamvs", "V"))).containsExactly("v1", "v2");
        assertThat(values(provider, typing("set", "teamvs", "v2", ""))).isEmpty();
        assertThat(provider.apply(typing("set", "teamvs", "v2")))
                .extracting(CompletionProposal::description).containsExactly("3");
    }

    @Test
    void setCompletionSkipsRoomOptionsAndCompletesTheirValues() {
        var provider = completions.setCompletion(matchService);
        assertThat(values(provider, typing("set", "--room", "42", "teamvs", "a")))
                .containsExactly("accuracy", "acc");
        assertThat(values(provider, typing("set", "--room=42", "te"))).containsExactly("teamvs");
        matchService.attach(7);
        assertThat(values(provider, typingRoomOption("set", "teamvs", "v2", "4", "--room", "")))
                .containsExactly("7");
    }

    @Test
    void hostCompletionPreservesUnicodeNamesAndSupportsRoomOption() {
        matchService.onMatchEvent(new PlayerJoined(7, "- Ｓ Ｈ -", 1));
        session.use(7);
        var provider = completions.memberCompletion(matchService, session);
        assertThat(values(provider, typing("host", ""))).containsExactly("- Ｓ Ｈ -");
        matchService.attach(7);
        assertThat(values(provider, typingRoomOption("host", "A", "--room", ""))).containsExactly("7");
    }

    @Test
    void mpCompletesOnlyFirstPositionalArgument() {
        var provider = completions.mpCompletion(matchService);
        assertThat(values(provider, typing("mp", "k"))).containsExactly("kick");
        assertThat(values(provider, typing("mp", "h"))).isEmpty();
        assertThat(values(provider, typing("mp", "te"))).isEmpty();
        assertThat(values(provider, typing("mp", "host", ""))).isEmpty();
        assertThat(values(provider, typing("mp", "--room", "42", "k"))).containsExactly("kick");
        matchService.attach(7);
        assertThat(values(provider, typingRoomOption("mp", "host", "--room", ""))).containsExactly("7");
    }

    @Test
    void roomOptionCompletionSuggestsNothingForPrimaryArgument() {
        matchService.onMatchEvent(new MatchCreated(7, "x"));

        assertThat(values(completions.roomOptionCompletion(matchService), typing("map", ""))).isEmpty();
    }
}
