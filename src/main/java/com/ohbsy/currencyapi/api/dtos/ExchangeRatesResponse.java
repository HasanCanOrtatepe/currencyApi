package com.ohbsy.currencyapi.api.dtos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * <b>Bizim JSON sözleşmemiz</b> — satıcının değil. TCMB'nin XML'i, ters kuru ve {@code Unit}
 * çarpanı buraya kadar gelmez; tüketici tek bir biçim görür ve satıcı değiştiğinde bu sözleşme
 * DEĞİŞMEZ. Sağlayıcı soyutlamasının dışarıdan görünen yüzü budur.
 *
 * <pre>{@code
 * {
 *   "base": "TRY",
 *   "rateDate": "2026-08-11",
 *   "fetchedAt": "2026-08-11T21:00:00Z",
 *   "provider": "tcmb",
 *   "cache": "FRESH_CACHE",
 *   "stale": false,
 *   "rates": [ { "currency": "USD", "rate": 0.0209526369, "unitPrice": 47.7300000000 } ]
 * }
 * }</pre>
 *
 * <h2>İki kur yönü de sunulur — bilinçli</h2>
 * {@code rate} = 1 baz birim kaç hedef birim ({@code 1 TRY = 0,0209 USD}) — çevrim için;
 * {@code unitPrice} = 1 hedef birim kaç baz birim ({@code 1 USD = 47,73 TRY}) — gösterim için.
 * Tüketicilerin yarısı birini, yarısı diğerini bekler; dönüşümü her tüketicinin ayrı yapması
 * <b>sessiz yön hatalarının</b> kaynağıdır (ters çevrilmiş bir kur da geçerli bir pozitif
 * sayıdır ve hiçbir doğrulamaya takılmaz).
 *
 * @param base      baz para birimi
 * @param rateDate  <b>satıcının yayın günü</b> — hafta sonu/tatilde son iş gününde kalır
 * @param fetchedAt bizim çekme anımız — tazelik bununla ölçülür
 * @param provider  hangi kaynak konuştu
 * @param cache     kurun nasıl elde edildiği ({@code FRESH_CACHE} / {@code FRESH_PROVIDER} /
 *                  {@code STALE_CACHE})
 * @param stale     kur "son geçerli değer" mi — tüketici bunu ekranda göstermelidir
 * @param rates     baz dahil kur satırları
 */
public record ExchangeRatesResponse(
        String base,
        LocalDate rateDate,
        Instant fetchedAt,
        String provider,
        String cache,
        boolean stale,
        List<ExchangeRateRow> rates
) {
}
