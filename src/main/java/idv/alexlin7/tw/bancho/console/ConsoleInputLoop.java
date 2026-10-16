package idv.alexlin7.tw.bancho.console;

import java.io.BufferedReader;
import java.io.IOException;

import lombok.extern.slf4j.Slf4j;

/**
 * 以一條 virtual thread 逐行讀取終端機輸入並交給 {@link ConsoleCommandInterpreter}。
 */
@Slf4j
public class ConsoleInputLoop implements AutoCloseable {

    private final BufferedReader input;
    private final ConsoleCommandInterpreter interpreter;
    private Thread thread;

    public ConsoleInputLoop(BufferedReader input, ConsoleCommandInterpreter interpreter) {
        this.input = input;
        this.interpreter = interpreter;
    }

    public synchronized void start() {
        if (thread == null) {
            thread = Thread.ofVirtual().name("console-input").start(this::run);
        }
    }

    private void run() {
        try {
            String line;
            while ((line = input.readLine()) != null) {
                interpreter.execute(line);
            }
            log.info("[console] 輸入已結束（EOF），停止讀取終端機");
        } catch (IOException e) {
            log.warn("[console] 讀取終端機輸入失敗", e);
        }
    }

    /**
     * 讀取 System.in 時無法真正中斷；virtual thread 是 daemon，不會擋住 JVM 結束。
     */
    @Override
    public synchronized void close() {
        if (thread != null) {
            thread.interrupt();
        }
    }
}
