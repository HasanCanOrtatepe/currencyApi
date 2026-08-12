package com.ohbsy.currencyapi.api.dtos;

/**
 * @param consumerName      log/metrik kimliği, zorunlu
 * @param rateLimitOverride {@code null}/verilmezse global varsayılan limit geçerli olur
 */
public record AdminApiKeyCreateRequest(String consumerName, Integer rateLimitOverride) {
}
