package idv.alexlin7.tw.bancho.shell;

import java.util.Optional;

/**
 * 以遮蔽方式讀取一行機密文字（例如房間密碼），輸入不會留在指令歷史。
 */
@FunctionalInterface
public interface SecretPrompt {

    /**
     * @return 輸入的文字；空白輸入、Ctrl+C、Ctrl+D 時為 empty
     * @throws IllegalStateException 無法提示輸入（非互動模式）
     */
    Optional<String> read(String prompt);
}
