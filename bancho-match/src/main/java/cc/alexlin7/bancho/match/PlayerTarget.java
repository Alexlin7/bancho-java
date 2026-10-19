package cc.alexlin7.bancho.match;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 玩家名稱保留原字元；明確的 #ID 與 osu! 個人頁網址轉為 BanchoBot 的 #ID。 */
public final class PlayerTarget {

    private static final Pattern PROFILE_PATH = Pattern.compile("/(?:users|u)/([^/]+)(?:/(?:osu|taiko|fruits|mania))?/?");

    private PlayerTarget() {
    }

    public static String commandArgument(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("玩家目標不可為空白；可用名稱、#ID 或 osu! 個人頁網址");
        }
        if (input.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("玩家目標不可含控制字元");
        }
        String target = input.strip();
        String[] parts = target.split("(?U)\\s+", 2);
        // 名稱可含空白，只有明確以 ID 或網址開頭時才判斷多餘目標。
        if (parts.length == 2 && (parts[0].matches("#[0-9]+") || parts[0].contains("/"))) {
            throw new IllegalArgumentException("一次只能指定一位玩家；多位玩家請分別執行指令");
        }
        // 玩家名稱不能含 /；看起來像網址的輸入一律驗證，不能退回當成名稱送出。
        if (target.contains("/")) {
            return profileArgument(target.toLowerCase(Locale.ROOT).startsWith("osu.ppy.sh/")
                    ? "https://" + target : target);
        }
        // 網址的 fragment 可含 #，所以在網址解析之後才檢查名稱與 ID 的 #。
        if (target.indexOf('#', 1) >= 0) {
            throw new IllegalArgumentException("玩家名稱不可含 #；ID 請用 #數字，且一次只能指定一位玩家");
        }
        if (target.startsWith("#")) {
            return idArgument(target.substring(1));
        }
        // 純數字也可能是名稱；只有明確寫 #ID 或網址時才按 ID 處理。不做全半形轉換。
        return target;
    }

    private static String profileArgument(String target) {
        try {
            URI uri = new URI(target);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"osu.ppy.sh".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw invalidProfile();
            }
            Matcher path = PROFILE_PATH.matcher(uri.getRawPath());
            if (!path.matches()) {
                throw invalidProfile();
            }
            if (!path.group(1).matches("[0-9]+")) {
                throw new IllegalArgumentException("個人頁網址需要數字 ID，不支援名稱網址；請直接輸入玩家名稱或 #ID");
            }
            return idArgument(path.group(1));
        } catch (URISyntaxException _) {
            throw invalidProfile();
        }
    }

    private static String idArgument(String id) {
        try {
            if (!id.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            long value = Long.parseLong(id);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return "#" + value;
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("玩家 ID 必須是正整數，例如 #2548278");
        }
    }

    private static IllegalArgumentException invalidProfile() {
        return new IllegalArgumentException("玩家網址必須是 osu.ppy.sh/users/<數字 ID> 或 osu.ppy.sh/u/<數字 ID>"
                + "（可省略 https://，可帶 /osu、/taiko、/fruits、/mania）");
    }
}
