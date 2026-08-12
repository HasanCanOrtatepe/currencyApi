package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryRateLimiter — peek ve limitOverride")
class InMemoryRateLimiterTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-12T10:00:00Z"), ZoneOffset.UTC);

    private CurrencyApiProperties properties;
    private InMemoryRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new CurrencyApiProperties();
        properties.getRateLimit().setLimit(10);
        limiter = new InMemoryRateLimiter(properties, FIXED);
    }

    @Test
    @DisplayName("peek sayaç ARTIRMAZ — art arda çağrılar hep aynı sonucu döner")
    void peekDoesNotMutate() {
        RateLimiter.Decision first = limiter.peek("crm");
        RateLimiter.Decision second = limiter.peek("crm");

        assertThat(first.remaining()).isEqualTo(10);
        assertThat(second.remaining()).isEqualTo(10);
    }

    @Test
    @DisplayName("peek, önceki tryConsume çağrılarını doğru yansıtır (kendisi tüketmeden)")
    void peekReflectsPriorConsumption() {
        limiter.tryConsume("crm");
        limiter.tryConsume("crm");

        RateLimiter.Decision peeked = limiter.peek("crm");

        assertThat(peeked.remaining()).isEqualTo(8);
        // Tekrar peek etmek sayacı değiştirmemeli.
        assertThat(limiter.peek("crm").remaining()).isEqualTo(8);
    }

    @Test
    @DisplayName("limitOverride global varsayılanı ezer (tryConsume ve peek'te)")
    void limitOverrideWinsOverGlobal() {
        RateLimiter.Decision decision = limiter.tryConsume("reporting", 3);

        assertThat(decision.limit()).isEqualTo(3);
        assertThat(limiter.peek("reporting", 3).limit()).isEqualTo(3);
    }

    @Test
    @DisplayName("null override global varsayılana düşer")
    void nullOverrideFallsBackToGlobal() {
        RateLimiter.Decision decision = limiter.tryConsume("crm", null);

        assertThat(decision.limit()).isEqualTo(10);
    }
}
