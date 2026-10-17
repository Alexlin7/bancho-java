package cc.alexlin7.bancho.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import cc.alexlin7.bancho.irc.IrcSender;
import cc.alexlin7.bancho.irc.RecordingIrcSender;
import cc.alexlin7.bancho.match.MatchService;

class ChatCommandsTest {

    private final RecordingIrcSender sender = new RecordingIrcSender();
    /** RecordingIrcSender 的紀錄分不出對象與內容的界線；私訊另外記成 (對象, 內容)。 */
    private final List<Map.Entry<String, String>> privateMessages = new ArrayList<>();
    private final IrcSender privateSender = new IrcSender() {
        @Override
        public void sendMessage(String target, String message) {
            privateMessages.add(Map.entry(target, message));
        }

        @Override
        public void join(String channel) {
            sender.join(channel);
        }

        @Override
        public void part(String channel) {
            sender.part(channel);
        }
    };
    private final Deque<Optional<String>> answers = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();
    private final TextPrompt prompt = text -> {
        prompts.add(text);
        return answers.removeFirst();
    };
    private final MatchService matchService = new MatchService(sender, Duration.ofSeconds(1));
    private final ShellSession session = new ShellSession();
    private final ChatCommands commands = new ChatCommands(sender, session, matchService, prompt);

    private ChatCommands msgCommands() {
        return new ChatCommands(privateSender, session, matchService, prompt);
    }

    @Test
    void sayJoinsWordsAndSendsToRoom() {
        matchService.attach(42);

        assertThat(commands.say(List.of("gl", "hf"), 42L, null)).isEqualTo("已送出：gl hf");
        assertThat(sender.calls).contains("msg #mp_42 gl hf");
    }

    @Test
    void sayUsesCurrentRoom() {
        matchService.attach(42);
        session.use(42);

        commands.say(List.of("hi"), null, null);

        assertThat(sender.calls).contains("msg #mp_42 hi");
    }

    @Test
    void msgTreatsAllWordsAsTargetAndPromptsForMessage() {
        // osu! 的使用者名稱可以有空白：指令後面的字全部是對象，訊息另外輸入，不再猜兩者的界線
        answers.add(Optional.of("我看看用這個能不能發訊息"));

        assertThat(msgCommands().msg(List.of("Minato", "Yukina"), null))
                .isEqualTo("已送出給 Minato Yukina：我看看用這個能不能發訊息");
        assertThat(privateMessages).containsExactly(Map.entry("Minato Yukina", "我看看用這個能不能發訊息"));
        assertThat(prompts).containsExactly("訊息：");
    }

    @Test
    void msgWithoutTargetPromptsForTargetThenMessage() {
        answers.add(Optional.of("BanchoBot"));
        answers.add(Optional.of("!mp make \"A\" vs B"));

        assertThat(msgCommands().msg(List.of(), null)).isEqualTo("已送出給 BanchoBot：!mp make \"A\" vs B");
        assertThat(privateMessages).containsExactly(Map.entry("BanchoBot", "!mp make \"A\" vs B"));
        assertThat(prompts).containsExactly("對象：", "訊息：");
    }

    @Test
    void cancellingTargetSendsNothing() {
        answers.add(Optional.empty());

        assertThat(msgCommands().msg(List.of(), null)).isEqualTo("已取消，沒有送出訊息");
        assertThat(prompts).containsExactly("對象：");
        assertThat(privateMessages).isEmpty();
    }

    @Test
    void cancellingMessageSendsNothing() {
        answers.add(Optional.empty());

        assertThat(msgCommands().msg(List.of("BanchoBot"), null)).isEqualTo("已取消，沒有送出訊息");
        assertThat(privateMessages).isEmpty();
    }

    @Test
    void msgWithoutTerminalExplains() {
        ChatCommands nonInteractive = new ChatCommands(privateSender, session, matchService, text -> {
            throw new IllegalStateException("非互動模式無法提示輸入，請在互動 shell 中使用");
        });

        assertThat(nonInteractive.msg(List.of("BanchoBot"), null)).isEqualTo("錯誤：非互動模式無法提示輸入，請在互動 shell 中使用");
        assertThat(privateMessages).isEmpty();
    }
}
