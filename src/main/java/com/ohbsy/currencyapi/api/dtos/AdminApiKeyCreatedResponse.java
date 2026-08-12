package com.ohbsy.currencyapi.api.dtos;

import java.time.Instant;

/**
 * Anahtarın {@code rawKey} alanıyla göründüğü <b>tek yer</b>. Bu cevap kaybolduktan sonra
 * anahtar bir daha hiçbir uçtan okunamaz — depoda yalnız hash'i tutulur.
 */
public record AdminApiKeyCreatedResponse(
        String id,
        String rawKey,
        String consumerName,
        Instant createdAt,
        Integer rateLimitOverride
) {
}
