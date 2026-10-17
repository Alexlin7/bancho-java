package cc.alexlin7.bancho.shell;

/**
 * shell 的輸出管道。實作會把文字印在輸入行上方，不打亂使用者正在輸入的內容。
 */
@FunctionalInterface
public interface ShellOutput {

    void println(String text);
}
