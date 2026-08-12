package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Redis destekli <b>sabit pencere</b> sayacı — çok instance'lı kurulumun gerektirdiği uygulama.
 *
 * <p>Bellekte tutulsaydı sınır <b>instance sayısıyla çarpılırdı</b>: üç instance'lı bir serviste
 * "dakikada 120" pratikte 360 olurdu ve sınır ilan ettiği şeyi yapmazdı. Cache'le birebir aynı
 * gerekçe — paylaşılan bir karar paylaşılan bir yerde durmalıdır.
 *
 * <h2>Neden sabit pencere (token bucket değil)</h2>
 * Sabit pencere iki Redis komutuyla ({@code INCR} + ilk artışta {@code EXPIRE}) atomik biçimde
 * kurulur ve okunması kolaydır. Bilinen zayıflığı <b>pencere sınırındaki iki katı patlamadır</b>
 * (59. ve 61. saniyede tam kota). Burada kabul edilebilir: sınır kotayı korumak için değil
 * kaçak döngüyü durdurmak için var ve kaçak bir döngü pencere sınırını beklemez.
 */
@Component
@ConditionalOnProperty(name = "currency-api.cache.type", havingValue = "redis")
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String KEY_PREFIX = "currency:ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final CurrencyApiProperties properties;
    private final Clock clock;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, CurrencyApiProperties properties,
                            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Decision tryConsume(String identity, Integer limitOverride) {
        CurrencyApiProperties.RateLimit config = properties.getRateLimit();
        int limit = effectiveLimit(config, limitOverride);
        if (!config.isEnabled()) {
            return Decision.unlimited(limit);
        }

        Duration window = config.getWindow();
        long windowIndex = clock.millis() / window.toMillis();
        String key = KEY_PREFIX + identity + ":" + windowIndex;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return Decision.unlimited(limit);
            }
            if (count == 1L) {
                // TTL yalnız ilk artışta kurulur; pencere böylece kaymaz.
                redisTemplate.expire(key, window);
            }
            int remaining = (int) Math.max(0, limit - count);
            boolean allowed = count <= limit;
            if (!allowed) {
                log.warn("hiz siniri asildi identity={} pencere={} limit={}",
                        identity, window, limit);
            }
            return new Decision(allowed, limit, remaining, retryAfter(window));
        } catch (Exception e) {
            // FAIL-OPEN: sayacın kesintisi servisi durdurmaz.
            log.warn("hiz sinirlayici sayaci okunamadi, istek GECIRILIYOR identity={} sebep={}",
                    identity, e.toString());
            return Decision.unlimited(limit);
        }
    }

    @Override
    public Decision peek(String identity, Integer limitOverride) {
        CurrencyApiProperties.RateLimit config = properties.getRateLimit();
        int limit = effectiveLimit(config, limitOverride);
        if (!config.isEnabled()) {
            return Decision.unlimited(limit);
        }

        Duration window = config.getWindow();
        long windowIndex = clock.millis() / window.toMillis();
        String key = KEY_PREFIX + identity + ":" + windowIndex;

        try {
            // GET, INCR degil — peek bir key/EXPIRE asla yaratmamali.
            String raw = redisTemplate.opsForValue().get(key);
            long count = raw == null ? 0 : Long.parseLong(raw);
            int remaining = (int) Math.max(0, limit - count);
            return new Decision(count <= limit, limit, remaining, retryAfter(window));
        } catch (Exception e) {
            log.warn("hiz sinirlayici sayaci okunamadi (peek) identity={} sebep={}",
                    identity, e.toString());
            return Decision.unlimited(limit);
        }
    }

    private int effectiveLimit(CurrencyApiProperties.RateLimit config, Integer limitOverride) {
        return limitOverride != null ? limitOverride : config.getLimit();
    }

    /** Pencerenin bitimine kalan saniye (en az 1). */
    private long retryAfter(Duration window) {
        long windowMillis = window.toMillis();
        long elapsed = clock.millis() % windowMillis;
        return Math.max(1, (windowMillis - elapsed) / 1000);
    }
}
