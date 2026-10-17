package cc.alexlin7.bancho.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BanchoRedactionTest {

    @Test
    void masksPassword() {
        assertThat(BanchoRedaction.redactOutbound("PRIVMSG #mp_1 :!mp password hunter2"))
                .isEqualTo("PRIVMSG #mp_1 :!mp password ***");
    }

    @Test
    void masksPasswordTypedThroughMsg() {
        assertThat(BanchoRedaction.redactOutbound("PRIVMSG BanchoBot :!MP PASSWORD hunter 2"))
                .isEqualTo("PRIVMSG BanchoBot :!MP PASSWORD ***");
    }

    @Test
    void keepsClearPasswordAndOtherCommands() {
        assertThat(BanchoRedaction.redactOutbound("PRIVMSG #mp_1 :!mp password"))
                .isEqualTo("PRIVMSG #mp_1 :!mp password");
        assertThat(BanchoRedaction.redactOutbound("PRIVMSG #mp_1 :!mp start 10"))
                .isEqualTo("PRIVMSG #mp_1 :!mp start 10");
        assertThat(BanchoRedaction.redactOutbound("PRIVMSG #mp_1 :my password is secret"))
                .isEqualTo("PRIVMSG #mp_1 :my password is secret");
    }
}
