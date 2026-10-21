package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TeamTest {

    @ParameterizedTest
    @CsvSource({"blue, BLUE", "BLUE, BLUE", "BlUe, BLUE", "red, RED", "RED, RED", "rEd, RED"})
    void parsesOnlyTeamNamesIgnoringCase(String text, Team expected) {
        assertThat(Team.parse(text)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"green", "0", "1", " blue", "red "})
    void rejectsOtherValues(String text) {
        assertThat(Team.parse(text)).isEmpty();
    }

    @Test
    void onlyVersusTeamModesHaveTeams() {
        assertThat(TeamMode.HEAD_TO_HEAD.hasTeams()).isFalse();
        assertThat(TeamMode.TAG_TEAM.hasTeams()).isFalse();
        assertThat(TeamMode.TEAM_VS.hasTeams()).isTrue();
        assertThat(TeamMode.TAG_TEAM_VS.hasTeams()).isTrue();
    }
}
