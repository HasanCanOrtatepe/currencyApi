package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bellek içi sabit pencere sayacı — tek instance, yerel geliştirme ve testler için.
 *
 * <p><b>Çok instance'lı üretimde yanlıştır ve bu bilinçli bir sınırdır:</b> her instance kendi
 * sayacını tuttuğu için ilan edilen sınır instance sayısıyla çarpılır. Üretimde
 * {@code currency-api.cache.type=redis} kullanılmalıdır ({@link RedisRateLimiter}). Yine de
 * varsayılan budur, çünkü servis Redis olmadan da çalışabilmelidir — aksi hâlde basit bir
 * birim testi altyapı gerektirirdi.
 */
@Component
@ConditionalOnProperty(name = "currency-api.cache.type", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    /** {@code kimlik:pencere → sayaç}. Eski pencereler yazma anında temizlenir. */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private final CurrencyApiProperties properties;
    private final Clock clock;

    public InMemoryRateLimiter(CurrencyApiProperties properties, Clock clock) {
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
        String key = identity + ":" + windowIndex;

        // Pencere değiştiğinde eski anahtarlar birikmesin (sınırsız büyüyen bir harita,
        // uzun ömürlü bir serviste sessiz bir bellek sızıntısıdır).
        counters.keySet().removeIf(existing -> !existing.endsWith(":" + windowIndex));

        long count = counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        int remaining = (int) Math.max(0, limit - count);
        return new Decision(count <= limit, limit, remaining, retryAfter(window));
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
        String key = identity + ":" + windowIndex;

        // Mutasyon YOK: yalnız okur, computeIfAbsent/incrementAndGet çağrılmaz.
        AtomicLong counter = counters.get(key);
        long count = counter == null ? 0 : counter.get();
        int remaining = (int) Math.max(0, limit - count);
        return new Decision(count <= limit, limit, remaining, retryAfter(window));
    }

    private int effectiveLimit(CurrencyApiProperties.RateLimit config, Integer limitOverride) {
        return limitOverride != null ? limitOverride : config.getLimit();
    }

    private long retryAfter(Duration window) {
        long windowMillis = window.toMillis();
        return Math.max(1, (windowMillis - (clock.millis() % windowMillis)) / 1000);
    }
}
