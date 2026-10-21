package cc.alexlin7.bancho.shell;

import java.util.Optional;

/** 在指令執行中讀取一行文字，保留空白 Enter，供逐項設定沿用目前值。 */
@FunctionalInterface
public interface LinePrompt {

    /**
     * @return 去掉前後空白的輸入；空白 Enter 為 {@code Optional.of("")}，Ctrl+C、Ctrl+D 為 empty
     * @throws IllegalStateException 無法提示輸入（非互動模式）
     */
    Optional<String> read(String prompt);
}
