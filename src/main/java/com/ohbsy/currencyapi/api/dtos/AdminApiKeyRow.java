package com.ohbsy.currencyapi.api.dtos;

import java.time.Instant;

/**
 * Admin listelemesindeki bir satır. {@code keyPreview} dışında ham anahtara dair hiçbir iz yok.
 *
 * <p><b>İki ayrı kullanım ölçüsü taşır ve karıştırılmamalıdır:</b> {@code usageRemaining}
 * <i>şu anki dakikada</i> kalan haktır (pencere dolunca sıfırlanır, seyrek çağıran tüketicide
 * hep dolu görünür); {@code usageToday}/{@code usageTotal} ise <b>birikmeli</b> sayaçlardır ve
 * "bu anahtar kullanılıyor mu" sorusunu cevaplayan sayılar bunlardır.
 */
public record AdminApiKeyRow(
        String id,
        String consumerName,
        String keyPreview,
        Instant createdAt,
        Instant revokedAt,
        boolean active,
        Integer rateLimitOverride,
        Instant lastUsedAt,
        int usageLimit,
        int usageRemaining,
        long usageToday,
        long usageTotal
) {
}
