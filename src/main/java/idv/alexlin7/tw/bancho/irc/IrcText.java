package idv.alexlin7.tw.bancho.irc;

import java.nio.charset.StandardCharsets;

/**
 * 出站文字的安全處理：防止換行注入、遵守單行長度上限。
 */
final class IrcText {

    /**
     * RFC 1459 §2.3：一行含 CR-LF 最多 512，扣掉 CR-LF 後剩 510。以 UTF-8 bytes 計算較保守。
     */
    static final int MAX_LINE_BYTES = 510;

    private IrcText() {
    }

    /**
     * 把 CR、LF、NUL 換成空白，避免訊息內容被當成新的 IRC 指令。
     */
    static String stripLineBreaks(String text) {
        return text.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
    }

    /**
     * 依 UTF-8 位元組數截斷，不會切斷多位元組字元或 surrogate pair。
     */
    static String truncateUtf8(String text, int maxBytes) {
        if (utf8Length(text) <= maxBytes) {
            return text;
        }
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = utf8Length(codePoint);
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }

    static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
