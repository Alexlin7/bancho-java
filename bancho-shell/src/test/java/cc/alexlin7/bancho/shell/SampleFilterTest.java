package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SampleFilterTest {

    @Test
    void keepsBanchoBotMessages() {
        assertThat(SampleFilter.keepReceived(":BanchoBot!cho@ppy.sh PRIVMSG #mp_1 :Closed the match")).isTrue();
        assertThat(SampleFilter.keepReceived(":BanchoBot!cho@ppy.sh PRIVMSG me :Created the tournament match "
                + "https://osu.ppy.sh/mp/1 r")).isTrue();
        assertThat(SampleFilter.keepReceived(":BanchoBot!cho@ppy.sh NOTICE me :something")).isTrue();
    }

    @Test
    void keepsServerNoticeAndNumerics() {
        assertThat(SampleFilter.keepReceived(":cho.ppy.sh NOTICE * :x")).isTrue();
        assertThat(SampleFilter.keepReceived(":cho.ppy.sh 464 u :Bad authentication token.")).isTrue();
        assertThat(SampleFilter.keepReceived(":cho.ppy.sh 001 u :Welcome")).isTrue();
    }

    @Test
    void dropsPlayerChatAndPing() {
        assertThat(SampleFilter.keepReceived(":Alexlin86!cho@ppy.sh PRIVMSG #mp_1 :gl")).isFalse();
        assertThat(SampleFilter.keepReceived("PING :cho.ppy.sh")).isFalse();
        assertThat(SampleFilter.keepReceived(":cho.ppy.sh PONG cho.ppy.sh :bancho-keepalive")).isFalse();
        assertThat(SampleFilter.keepReceived(":A!cho@ppy.sh PRIVMSG me :hi")).isFalse();
        assertThat(SampleFilter.keepReceived(":A!cho@ppy.sh NOTICE me :hi")).isFalse();
        assertThat(SampleFilter.keepReceived(":A!cho@ppy.sh JOIN :#mp_1")).isFalse();
        assertThat(SampleFilter.keepReceived("")).isFalse();
    }

    @Test
    void keepsSentToBanchoBotAndMpOnly() {
        assertThat(SampleFilter.keepSent("PRIVMSG #mp_1 :!mp start 10")).isTrue();
        assertThat(SampleFilter.keepSent("PRIVMSG banchobot :!mp make r")).isTrue();
        assertThat(SampleFilter.keepSent("PRIVMSG Alexlin86 :hi")).isFalse();
        assertThat(SampleFilter.keepSent("PRIVMSG #osu :hi")).isFalse();
        assertThat(SampleFilter.keepSent("JOIN #mp_1")).isFalse();
    }
}
