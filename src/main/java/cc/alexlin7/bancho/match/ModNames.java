package cc.alexlin7.bancho.match;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code !mp mods} 可用的名稱：osu! wiki「Game modifier」的 osu!stable 縮寫，加上 {@code !mp mods} 的特殊值 Freemod。
 * shell 的補全與 {@link MatchRoom} 的連寫檢查都以這份清單為準，兩邊不會不一致。
 */
public final class ModNames {

    public static final List<String> KNOWN = List.of(
            "EZ", "NF", "HT", "HR", "SD", "PF", "DT", "NC", "HD", "FL", "RL", "AP", "SO", "SV2", "Freemod");

    /** 小寫的已知名稱，供不分大小寫比對。 */
    private static final Set<String> KNOWN_LOWER_CASE = KNOWN.stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private ModNames() {
    }

    /**
     * BanchoBot 看不懂 NFHD 這類連寫，會直接清空所有 mod：兩個字以內的縮寫，或清單中的名稱（不分大小寫）才算單一 mod。
     */
    static boolean isSingleMod(String mod) {
        return mod.length() <= 2 || KNOWN_LOWER_CASE.contains(mod.toLowerCase(Locale.ROOT));
    }
}
