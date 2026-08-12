package com.ohbsy.currencyapi.dataAccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kur cache'inin Redis uygulaması. Sınananlar: sağlayıcı başına ayrı anahtar (ECB eklendiğinde
 * TCMB'nin kaydını ezmesin), TTL'in <b>retention</b> olması (tazelik değil) ve fail-OPEN.
 */
@DisplayName("RedisRateCache — anahtar, TTL ve fail-open")
class RedisRateCacheTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ObjectMapper objectMapper;
    private CurrencyApiProperties properties;
    private RedisRateCache cache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new CurrencyApiProperties();
        cache = new RedisRateCache(redis, objectMapper, properties);
    }

    private ExchangeRateSnapshot snapshot() {
        return new ExchangeRateSnapshot(
                CurrencyCode.TRY,
                Map.of(CurrencyCode.USD, new BigDecimal("0.0209")),
                LocalDate.of(2026, 8, 12),
                Instant.parse("2026-08-12T10:00:00Z"));
    }

    /** Tek anahtar kullanılsaydı ECB'ye düşen bir cevap TCMB'nin kaydını ezerdi. */
    @Test
    @DisplayName("Anahtar sağlayıcı adını taşır")
    void keyCarriesProviderName() throws Exception {
        cache.put("tcmb", snapshot());

        verify(values).set(
                org.mockito.ArgumentMatchers.eq("currency:rates:tcmb"),
                org.mockito.ArgumentMatchers.eq(objectMapper.writeValueAsString(snapshot())),
                any(Duration.class));
    }

    /**
     * TTL {@code retention}'dır, {@code ttl} (tazelik) DEĞİL: tazelik dolduğunda kayıt
     * silinseydi "son geçerli kur" güvenlik ağı kaybolurdu.
     */
    @Test
    @DisplayName("Redis TTL'i RETENTION'dır (tazelik penceresi değil)")
    void ttlIsRetentionNotFreshness() {
        properties.getCache().setTtl(Duration.ofMinutes(15));
        properties.getCache().setRetention(Duration.ofDays(7));

        cache.put("tcmb", snapshot());

        verify(values).set(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("Yazılan kayıt geri okunabilir")
    void roundTrip() throws Exception {
        when(values.get("currency:rates:tcmb"))
                .thenReturn(objectMapper.writeValueAsString(snapshot()));

        assertThat(cache.find("tcmb")).contains(snapshot());
    }

    @Test
    @DisplayName("Kayıt yoksa boş döner (miss)")
    void missReturnsEmpty() {
        when(values.get(anyString())).thenReturn(null);

        assertThat(cache.find("tcmb")).isEmpty();
    }

    /** Cache bir HIZLANDIRICIDIR: kesintisi kur sunmayı durdurmamalı, yalnız miss olmalı. */
    @Test
    @DisplayName("Redis patlarsa okuma MISS sayılır, istisna sızmaz (fail-open)")
    void readFailsOpen() {
        when(values.get(anyString())).thenThrow(new RuntimeException("redis erisilemez"));

        assertThat(cache.find("tcmb")).isEmpty();
    }

    @Test
    @DisplayName("Bozuk kayıt da MISS sayılır")
    void corruptValueIsMiss() {
        when(values.get(anyString())).thenReturn("{bozuk-json");

        assertThat(cache.find("tcmb")).isEmpty();
    }

    @Test
    @DisplayName("Redis patlarsa yazma sessizce atlanır, istisna sızmaz")
    void writeFailsOpen() {
        doThrow(new RuntimeException("redis erisilemez"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put("tcmb", snapshot())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("kind() 'redis' döner")
    void kindIsRedis() {
        assertThat(cache.kind()).isEqualTo("redis");
    }
}
