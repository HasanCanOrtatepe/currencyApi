package com.ohbsy.currencyapi.api.controllers;

import com.ohbsy.currencyapi.api.dtos.RatePreviewResponse;
import com.ohbsy.currencyapi.business.abstracts.ExchangeRateService;
import com.ohbsy.currencyapi.business.concretes.RateResult;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tanıtım sayfasının panosunu besleyen anahtarsız uç.
 *
 * <p>Buradaki en önemli iddia <b>ne döndürdüğü kadar ne döndürMEDİĞİdir</b>: uç herkese
 * açıktır ve ürünün yerine geçmemelidir.
 */
@DisplayName("ExchangeRateController /preview — anahtarsız pano ucu")
class ExchangeRateControllerPreviewTest {

    private static final Instant FETCHED = Instant.parse("2026-08-14T07:21:41Z");

    private ExchangeRateSnapshot snapshot() {
        return new ExchangeRateSnapshot(
                CurrencyCode.TRY,
                Map.of(
                        CurrencyCode.USD, new BigDecimal("0.0209328954"),
                        CurrencyCode.EUR, new BigDecimal("0.0181495450"),
                        CurrencyCode.CHF, new BigDecimal("0.0170000000")),
                LocalDate.of(2026, 8, 13),
                FETCHED);
    }

    private ExchangeRateControllerPreviewTest.Stub stubWith(RateResult result) {
        return new Stub(result);
    }

    private record Stub(RateResult result) implements ExchangeRateService {
        @Override
        public RateResult currentRates() {
            return result;
        }
    }

    private ResponseEntity<RatePreviewResponse> preview(RateResult result) {
        return new ExchangeRateController(stubWith(result)).preview();
    }

    /**
     * Sayfada yalnız bülten günü gösterilince "bugün 14'ü, neden 13 yazıyor" sorusu doğuyordu.
     * Cevap "en son ne zaman baktığımız"dır — bu yüzden alan sözleşmenin parçasıdır.
     */
    @Test
    @DisplayName("fetchedAt döner — pano 'son kontrol' saatini gösterebilsin")
    void exposesFetchedAt() {
        var body = preview(new RateResult(snapshot(), RateResult.Status.FRESH_CACHE, "tcmb"))
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.fetchedAt()).isEqualTo(FETCHED);
        assertThat(body.rateDate()).isEqualTo(LocalDate.of(2026, 8, 13));
    }

    /** Uç bir VİTRİNDİR: yalnız panoda gösterilen birimler döner, tüm tablo değil. */
    @Test
    @DisplayName("Yalnız panodaki para birimleri döner")
    void returnsOnlyBoardCurrencies() {
        var body = preview(new RateResult(snapshot(), RateResult.Status.FRESH_CACHE, "tcmb"))
                .getBody();

        assertThat(body.rates()).extracting(RatePreviewResponse.Row::currency)
                .containsExactly("USD", "EUR")     // GBP/JPY tabloda yok, CHF panoda yok
                .doesNotContain("CHF");
    }

    /**
     * Ürünün yerine geçmemesi için çevrim yönü ({@code rate}), sağlayıcı ve tazelik
     * bilgisi BİLİNÇLİ olarak yoktur — yalnız gösterim değeri döner.
     */
    @Test
    @DisplayName("Yalnız unitPrice döner; rate/provider/cache alanları YOKTUR")
    void doesNotLeakFullContract() {
        var row = preview(new RateResult(snapshot(), RateResult.Status.FRESH_CACHE, "tcmb"))
                .getBody().rates().get(0);

        assertThat(row.unitPrice()).isNotNull();
        assertThat(RatePreviewResponse.Row.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("currency", "unitPrice");
    }

    @Test
    @DisplayName("Elde kur yoksa 503 + Retry-After")
    void unavailableYields503() {
        var response = preview(RateResult.unavailable("tcmb"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }
}
