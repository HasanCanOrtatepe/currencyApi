package com.ohbsy.currencyapi.business.concretes;

import com.ohbsy.currencyapi.entities.ApiKeyRecord;

import java.time.Instant;

/**
 * Admin listelemesi için bir {@link ApiKeyRecord} + kullanımı.
 *
 * <h2>İki farklı soru, iki farklı sayı</h2>
 * <ul>
 *   <li>{@code usageLimit}/{@code usageRemaining} — <b>şu anki 1 dakikalık pencerede</b> kaç
 *       hak kaldı. {@code RateLimiter.peek()} ile OKUNUR, tüketilmez. Pencere dolunca
 *       sıfırlanır, dolayısıyla seyrek çağıran bir tüketici için pratikte <b>hep doludur</b>.</li>
 *   <li>{@code usageToday}/{@code usageTotal} — <b>birikmeli</b> kullanım. "Bu anahtar
 *       kullanılıyor mu, ne kadar" sorusunu cevaplayan sayı budur; azalmaz, artar.</li>
 * </ul>
 * İkisi bir arada durur çünkü farklı işler görürler: ilki "şu an kısıtlanıyor mu", ikincisi
 * "genel olarak ne kadar kullanıyor". Yalnız ilki gösterildiğinde panel donmuş görünüyordu.
 */
public record ApiKeyUsageView(
        String id,
        String consumerName,
        String keyPreview,
        Instant createdAt,
        Instant revokedAt,
        Integer rateLimitOverride,
        Instant lastUsedAt,
        int usageLimit,
        int usageRemaining,
        long usageToday,
        long usageTotal
) {

    public boolean isActive() {
        return revokedAt == null;
    }
}
