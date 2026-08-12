package com.ohbsy.currencyapi.api.dtos;

import java.math.BigDecimal;

/**
 * Tek kur satırı.
 *
 * @param currency  ISO 4217 kodu
 * @param rate      1 baz birim kaç {@code currency} eder ({@code 1 TRY = 0,0209 USD}) — çevrim yönü
 * @param unitPrice 1 {@code currency} kaç baz birim eder ({@code 1 USD = 47,73 TRY}) — gösterim yönü
 */
public record ExchangeRateRow(String currency, BigDecimal rate, BigDecimal unitPrice) {
}
