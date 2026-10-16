package idv.alexlin7.tw.bancho.shell;

import org.jline.reader.LineReader;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.shell.jline.ExtendedDefaultParser;
import org.springframework.stereotype.Component;

/**
 * 調整 Spring Shell 建立的 JLine 元件：
 * <ul>
 *   <li>補全不分大小寫：JLine 會再過濾一次候選，預設區分大小寫（{@code mods hd} 補不出 {@code HD}）</li>
 *   <li>只把雙引號當引號：預設單引號也算，{@code say I'm ready} 會停在等待補上引號的提示，訊息送不出去；
 *       Spring Shell 的指令 parser 本來就只認得雙引號</li>
 * </ul>
 */
@Component
class ShellLineEditing implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof LineReader reader) {
            reader.setOpt(LineReader.Option.CASE_INSENSITIVE);
        }
        if (bean instanceof ExtendedDefaultParser parser) {
            parser.setQuoteChars(new char[] {'"'});
        }
        return bean;
    }
}
