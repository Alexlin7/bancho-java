package cc.alexlin7.bancho.shell;

import java.util.Optional;

/**
 * 在指令執行中讀取一行一般文字（例如私訊的對象與內容），輸入原樣保留、不寫入指令歷史。
 */
@FunctionalInterface
public interface TextPrompt {

    /**
     * @return 輸入的文字（去掉前後空白）；空白輸入、Ctrl+C、Ctrl+D 時為 empty
     * @throws IllegalStateException 無法提示輸入（非互動模式）
     */
    Optional<String> read(String prompt);
}
