package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sabit pencere sayacının Redis uygulaması — sınananlar <b>bizim</b> kararlarımızdır:
 * TTL'in yalnız ilk artışta kurulması (pencere kaymasın), {@code peek}'in INCR
 * KULLANMAMASI (okuma tüketim üretmesin) ve fail-OPEN davranış.
 */
@DisplayName("RedisRateLimiter — pencere, peek ve fail-open")
class RedisRateLimiterTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-12T10:00:30Z"), ZoneOffset.UTC);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private CurrencyApiProperties properties;
    private RedisRateLimiter limiter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        properties = new CurrencyApiProperties();
        properties.getRateLimit().setLimit(10);
        limiter = new RedisRateLimiter(redis, properties, FIXED);
    }

    /** TTL sonraki artışlarda da kurulsaydı pencere sürekli ileri kayar ve hiç dolmazdı. */
    @Test
    @DisplayName("TTL YALNIZ ilk artışta kurulur (pencere kaymasın)")
    void expireOnlyOnFirstIncrement() {
        when(values.increment(anyString())).thenReturn(1L);
        limiter.tryConsume("crm");
        verify(redis).expire(anyString(), any(Duration.class));

        when(values.increment(anyString())).thenReturn(2L);
        limiter.tryConsume("crm");
        verify(redis).expire(anyString(), any(Duration.class));  // hâlâ tek çağrı
    }

    @Test
    @DisplayName("Sayaç limiti aşınca istek reddedilir ve Retry-After pozitiftir")
    void rejectsOverLimit() {
        when(values.increment(anyString())).thenReturn(11L);

        RateLimiter.Decision decision = limiter.tryConsume("crm");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
        assertThat(decision.retryAfterSeconds()).isPositive();
    }

    /**
     * Sayaç bir YARDIMCIDIR: kesintisi kur sunabilen servisi durdurmamalıdır. (Anahtar
     * deposunun aksine — orası fail-CLOSED'dır, çünkü orada Redis doğrulamanın kaynağıdır.)
     */
    @Test
    @DisplayName("Redis patlarsa istek GEÇER (fail-open)")
    void failsOpen() {
        when(values.increment(anyString())).thenThrow(new RuntimeException("redis erisilemez"));

        RateLimiter.Decision decision = limiter.tryConsume("crm");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(10);
    }

    @Test
    @DisplayName("increment null dönerse istek GEÇER")
    void nullIncrementFailsOpen() {
        when(values.increment(anyString())).thenReturn(null);

        assertThat(limiter.tryConsume("crm").allowed()).isTrue();
    }

    /** peek bir OKUMADIR: INCR çağırsaydı admin panelini açmak kotayı harcardı. */
    @Test
    @DisplayName("peek GET kullanır, INCR ÇAĞIRMAZ ve TTL kurmaz")
    void peekDoesNotConsume() {
        when(values.get(anyString())).thenReturn("4");

        RateLimiter.Decision decision = limiter.peek("crm");

        assertThat(decision.remaining()).isEqualTo(6);
        verify(values, never()).increment(anyString());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Henüz sayaç yokken peek tam kotayı bildirir")
    void peekWithNoCounterReportsFullQuota() {
        when(values.get(anyString())).thenReturn(null);

        assertThat(limiter.peek("crm").remaining()).isEqualTo(10);
    }

    @Test
    @DisplayName("limitOverride global varsayılanı ezer")
    void overrideWinsOverGlobal() {
        when(values.increment(anyString())).thenReturn(3L);

        assertThat(limiter.tryConsume("reporting", 3).limit()).isEqualTo(3);
        assertThat(limiter.tryConsume("reporting", 3).allowed()).isTrue();
    }

    @Test
    @DisplayName("Sınır kapalıyken Redis'e hiç gidilmez")
    void disabledLimiterSkipsRedis() {
        properties.getRateLimit().setEnabled(false);

        assertThat(limiter.tryConsume("crm").allowed()).isTrue();
        verify(values, never()).increment(anyString());
    }

    /** Anahtar pencere indeksini taşımalı: aksi hâlde sayaç hiç sıfırlanmazdı. */
    @Test
    @DisplayName("Anahtar kimlik + pencere indeksi taşır")
    void keyCarriesIdentityAndWindow() {
        when(values.increment(anyString())).thenReturn(1L);
        long expectedWindow = FIXED.millis() / Duration.ofMinutes(1).toMillis();

        limiter.tryConsume("crm");

        verify(values).increment("currency:ratelimit:crm:" + expectedWindow);
    }
}
