package com.ohbsy.currencyapi.api.dtos;

import java.time.Instant;

/** Admin listelemesindeki bir satır. {@code keyPreview} dışında ham anahtara dair hiçbir iz yok. */
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
        int usageRemaining
) {
}
