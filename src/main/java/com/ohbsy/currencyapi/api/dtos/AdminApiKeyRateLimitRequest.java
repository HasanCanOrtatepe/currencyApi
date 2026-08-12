package com.ohbsy.currencyapi.api.dtos;

/**
 * @param rateLimitOverride yeni pencere-başı sınır; {@code null} = global varsayılana dön
 */
public record AdminApiKeyRateLimitRequest(Integer rateLimitOverride) {
}
