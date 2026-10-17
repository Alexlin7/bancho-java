package cc.alexlin7.bancho.irc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class OsuIrcSettingsTest {

    @Test
    void appliesDefaults() {
        OsuIrcSettings settings = new OsuIrcSettings(null, 0, "someone", "secret", null);

        assertThat(settings.host()).isEqualTo("cho.ppy.sh");
        assertThat(settings.port()).isEqualTo(6667);
        assertThat(settings.timing().sendInterval()).isEqualTo(Duration.ofMillis(625));
        assertThat(settings.timing().readTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsBlankUsername() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OsuIrcSettings(null, 0, " ", "secret", null))
                .withMessageContaining("username");
    }

    @Test
    void rejectsMissingPassword() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OsuIrcSettings(null, 0, "someone", null, null))
                .withMessageContaining("password");
    }

    @Test
    void toStringHidesPassword() {
        assertThat(new OsuIrcSettings(null, 0, "someone", "secret", null).toString())
                .contains("someone").doesNotContain("secret");
    }
}
