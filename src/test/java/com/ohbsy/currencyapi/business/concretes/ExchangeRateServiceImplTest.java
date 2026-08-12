package com.ohbsy.currencyapi.business.concretes;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.integrations.ExchangeRateProvider;
import com.ohbsy.currencyapi.core.integrations.ProviderUnavailableException;
import com.ohbsy.currencyapi.dataAccess.InMemoryRateCache;
import com.ohbsy.currencyapi.dataAccess.RateCache;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Servisin üç basamağı: taze cache → sağlayıcı → <b>son geçerli kur</b>.
 *
 * <p>Buradaki testler ürünün iki temel vaadini kilitler: "her istekte TCMB'ye gidilmez" ve
 * "TCMB düşse de kur döner". İkisi de yalnız arıza/zaman koşullarında görünür, yani gözle
 * fark edilmezler.
 */
@DisplayName("ExchangeRateService — cache-aside ve son geçerli kur")
class ExchangeRateServiceImplTest {

    private static final Instant T0 = Instant.parse("2026-08-11T09:00:00Z");

    private ExchangeRateProvider provider;
    private RateCache cache;
    private CurrencyApiProperties properties;
    private MutableClock clock;
    private ExchangeRateServiceImpl service;

    /** Zamanı ileri sarabilen saat: 15 dakika beklemeden tazelik sınırını sınamak için. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        provider = mock(ExchangeRateProvider.class);
        when(provider.name()).thenReturn("tcmb");
        properties = new CurrencyApiProperties();
        clock = new MutableClock(T0);
        cache = new InMemoryRateCache(properties, clock);
        service = new ExchangeRateServiceImpl(provider, cache, properties, clock);
    }

    private ExchangeRateSnapshot snapshot(String usdRate, Instant fetchedAt) {
        return new ExchangeRateSnapshot(CurrencyCode.TRY,
                Map.of(CurrencyCode.USD, new BigDecimal(usdRate)),
                LocalDate.of(2026, 8, 11), fetchedAt);
    }

    private static ProviderUnavailableException outage(ProviderUnavailableException.Reason reason) {
        return new ProviderUnavailableException(reason, "saglayici dustu");
    }

    /** Ürünün birinci vaadi: <b>her istekte TCMB'ye gidilmez.</b> */
    @Test
    @DisplayName("Taze cache: ikinci istekte sağlayıcıya HİÇ gidilmez")
    void freshCacheSkipsProvider() {
        when(provider.fetchLatestRates()).thenReturn(snapshot("0.0209", T0));

        RateResult first = service.currentRates();
        RateResult second = service.currentRates();

        assertThat(first.status()).isEqualTo(RateResult.Status.FRESH_PROVIDER);
        assertThat(second.status()).isEqualTo(RateResult.Status.FRESH_CACHE);
        verify(provider, times(1)).fetchLatestRates();
    }

    @Test
    @DisplayName("TTL (15 dk) dolunca sağlayıcıya yeniden gidilir")
    void expiredTtlRefetches() {
        when(provider.fetchLatestRates()).thenReturn(snapshot("0.0209", T0));
        service.currentRates();

        clock.advance(Duration.ofMinutes(16));
        when(provider.fetchLatestRates()).thenReturn(snapshot("0.0210", clock.instant()));
        RateResult result = service.currentRates();

        assertThat(result.status()).isEqualTo(RateResult.Status.FRESH_PROVIDER);
        assertThat(result.snapshot().rateOf(CurrencyCode.USD)).isEqualByComparingTo("0.0210");
        verify(provider, times(2)).fetchLatestRates();
    }

    /**
     * Ürünün ikinci vaadi: <b>TCMB düşse de kur döner.</b> Hafta sonu/tatil senaryosu da
     * budur — TCMB o gün belge yayınlamaz ({@code NOT_PUBLISHED}) ve son iş gününün kuru sunulur.
     */
    @Test
    @DisplayName("Sağlayıcı düşerse SON GEÇERLİ kur sunulur (stale=true)")
    void providerFailureServesLastKnownGood() {
        when(provider.fetchLatestRates()).thenReturn(snapshot("0.0209", T0));
        service.currentRates();

        clock.advance(Duration.ofHours(2));
        when(provider.fetchLatestRates())
                .thenThrow(outage(ProviderUnavailableException.Reason.TRANSPORT));
        RateResult result = service.currentRates();

        assertThat(result.status()).isEqualTo(RateResult.Status.STALE_CACHE);
        assertThat(result.isStale()).isTrue();
        assertThat(result.snapshot().rateOf(CurrencyCode.USD)).isEqualByComparingTo("0.0209");
    }

    @Test
    @DisplayName("Hafta sonu/tatil (NOT_PUBLISHED) da son geçerli kura düşer")
    void notPublishedServesLastKnownGood() {
        when(provider.fetchLatestRates()).thenReturn(snapshot("0.0209", T0));
        service.currentRates();

        clock.advance(Duration.ofDays(2));   // cumartesi
        when(provider.fetchLatestRates())
                .thenThrow(outage(ProviderUnavailableException.Reason.NOT_PUBLISHED));

        assertThat(service.currentRates().status()).isEqualTo(RateResult.Status.STALE_CACHE);
    }

    /** İstisna fırlatmaz: "kur yok" cevabın bir alanıdır, tüketici kendi yedeğine geçebilsin. */
    @Test
    @DisplayName("Sağlayıcı düştü ve cache boş → UNAVAILABLE (istisna DEĞİL)")
    void noProviderNoCacheYieldsUnavailable() {
        when(provider.fetchLatestRates())
                .thenThrow(outage(ProviderUnavailableException.Reason.TRANSPORT));

        RateResult result = service.currentRates();

        assertThat(result.status()).isEqualTo(RateResult.Status.UNAVAILABLE);
        assertThat(result.hasRates()).isFalse();
    }

    /** Saklama süresi dolmuş kayıt "yok" sayılır: çok eski kur sonsuza dek sunulmamalı. */
    @Test
    @DisplayName("Saklama süresi (retention) dolan kayıt artık son geçerli kur sayılmaz")
    void retentionExpiryDropsCachedSnapshot() {
        when(provider.fetchLatestRates()).thenReturn(snapshot("0.0209", T0));
        service.currentRates();

        clock.advance(Duration.ofDays(8));   // retention = 7 gün
        when(provider.fetchLatestRates())
                .thenThrow(outage(ProviderUnavailableException.Reason.TRANSPORT));

        assertThat(service.currentRates().status()).isEqualTo(RateResult.Status.UNAVAILABLE);
    }

    /**
     * Cache <b>sağlayıcı adıyla</b> anahtarlanır: ileride ECB eklendiğinde iki kaynağın
     * tabloları birbirinin üzerine yazmasın.
     */
    @Test
    @DisplayName("Cache boşken ilk istek sağlayıcıya gider ve sonucu sağlayıcı adıyla yazar")
    void firstRequestPopulatesCache() {
        when(provider.fetchLatestRates()).thenReturn(snapshot("0.0209", T0));

        RateResult result = service.currentRates();

        assertThat(result.provider()).isEqualTo("tcmb");
        assertThat(cache.find("tcmb")).isPresent();
        assertThat(cache.find("ecb")).isEmpty();
    }
}
