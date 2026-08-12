package com.ohbsy.currencyapi.business.concretes;

import com.ohbsy.currencyapi.entities.ApiKeyRecord;

import java.time.Instant;

/**
 * Admin listelemesi için bir {@link ApiKeyRecord} + o anki hız-sınırı kullanımı. {@code
 * usageLimit}/{@code usageRemaining} {@code RateLimiter.peek()} ile OKUNUR, tüketilmez —
 * listeleme sayacı asla artırmaz.
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
        int usageRemaining
) {

    public boolean isActive() {
        return revokedAt == null;
    }
}
