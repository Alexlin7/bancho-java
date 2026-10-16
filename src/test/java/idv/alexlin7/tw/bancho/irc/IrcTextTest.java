package idv.alexlin7.tw.bancho.irc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IrcTextTest {

    @Test
    void stripLineBreaksReplacesCrLfAndNul() {
        assertThat(IrcText.stripLineBreaks("a\r\nQUIT\nb\rc\0d")).isEqualTo("a  QUIT b c d");
    }

    @Test
    void truncateKeepsShortTextUnchanged() {
        assertThat(IrcText.truncateUtf8("hello", 5)).isEqualTo("hello");
    }

    @Test
    void truncateCountsUtf8BytesAndNeverSplitsCharacters() {
        // 「あ」是 3 bytes：7 bytes 只放得下 2 個
        assertThat(IrcText.truncateUtf8("ああああ", 7)).isEqualTo("ああ");
        // 🎵 是 4 bytes（surrogate pair），不能被切成半個
        assertThat(IrcText.truncateUtf8("a🎵", 4)).isEqualTo("a");
        assertThat(IrcText.truncateUtf8("a🎵", 5)).isEqualTo("a🎵");
        assertThat(IrcText.truncateUtf8("é", 1)).isEmpty();
    }

    @Test
    void utf8LengthCountsBytes() {
        assertThat(IrcText.utf8Length("aé中🎵")).isEqualTo(1 + 2 + 3 + 4);
    }
}
