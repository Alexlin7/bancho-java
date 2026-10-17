package cc.alexlin7.bancho.match;

import java.util.concurrent.CompletableFuture;

/**
 * 一筆開房請求的結果。
 *
 * @param name    房名
 * @param room    收到同名的開房回覆時完成（逾時後晚到也會完成）；被 {@link MatchService#drop} 時以 cancel 結束
 * @param unknown {@code !mp make} 送出後逾時、轉為結果未知時完成；在逾時前成功就永遠不會完成
 */
public record MatchCreation(String name, CompletableFuture<MatchRoom> room, CompletableFuture<Void> unknown) {
}
