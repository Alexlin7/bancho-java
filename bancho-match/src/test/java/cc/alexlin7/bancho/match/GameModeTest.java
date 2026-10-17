package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GameModeTest {

    @ParameterizedTest
    @CsvSource({"0,OSU", "osu,OSU", "STD,OSU", "1,TAIKO", "Taiko,TAIKO", "2,CATCH", "catch,CATCH",
            "fruits,CATCH", "CTB,CATCH", "3,MANIA", "mania,MANIA", "' mania ',MANIA"})
    void parsesNumbersAndNames(String text, GameMode expected) {
        assertThat(GameMode.parse(text)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"4", "-1", "abc", " ", "osu!"})
    void rejectsUnknown(String text) {
        assertThat(GameMode.parse(text)).isEmpty();
    }

    @Test
    void idsMatchBanchoBot() {
        assertThat(Arrays.stream(GameMode.values()).map(GameMode::id)).containsExactly(0, 1, 2, 3);
    }
}
