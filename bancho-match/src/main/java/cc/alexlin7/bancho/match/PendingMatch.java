package cc.alexlin7.bancho.match;

/**
 * 尚未對應到房間的開房請求，佔用一個名額。
 *
 * @param name   房名
 * @param status 狀態
 */
public record PendingMatch(String name, Status status) {

    public enum Status {
        /** 等待 {@code !mp make} 送出或 BanchoBot 回覆。 */
        CREATING,
        /** 送出後逾時，房間可能已經建立，也可能沒有。 */
        UNKNOWN
    }
}
