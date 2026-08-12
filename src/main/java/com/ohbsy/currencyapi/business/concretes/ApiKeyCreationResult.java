package com.ohbsy.currencyapi.business.concretes;

import java.time.Instant;

/**
 * Bir anahtar oluşturma işleminin sonucu. {@code rawKey} yalnız BURADA, oluşturma anında
 * bulunur — {@code ApiKeyStore} ham anahtarı hiç görmez, sonraki hiçbir sorguda geri gelmez.
 */
public record ApiKeyCreationResult(
        String id,
        String rawKey,
        String consumerName,
        Instant createdAt,
        Integer rateLimitOverride
) {
}
