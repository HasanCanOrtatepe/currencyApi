package com.ohbsy.currencyapi.dataAccess;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tek instance'lık kurulumun sayacı — varsayılan.
 *
 * <p><b>Süreçle birlikte sıfırlanır</b> ve bu bilinçli bir sınırdır, gizlenmemelidir: bellekte
 * tutulan bir toplam, yeniden başlatmadan sağ çıkmaz. Kalıcı sayım isteyen kurulum zaten
 * {@code currency-api.cache.type=redis} kullanır — panelin çalıştığı kurulum da odur.
 */
@Component
@ConditionalOnProperty(name = "currency-api.cache.type", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryApiKeyUsageCounter implements ApiKeyUsageCounter {

    private final Map<String, AtomicLong> totals = new ConcurrentHashMap<>();
    private final Map<String, DayCount> daily = new ConcurrentHashMap<>();

    private final Clock clock;

    public InMemoryApiKeyUsageCounter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void record(String keyId) {
        if (keyId == null) {
            return;
        }
        totals.computeIfAbsent(keyId, id -> new AtomicLong()).incrementAndGet();
        // Gün değiştiyse kayıt SIFIRDAN kurulur; böylece harita gün sayısıyla değil
        // anahtar sayısıyla büyür.
        daily.compute(keyId, (id, current) -> {
            LocalDate today = today();
            return current != null && current.day().equals(today)
                    ? new DayCount(today, current.count() + 1)
                    : new DayCount(today, 1);
        });
    }

    @Override
    public Usage of(String keyId) {
        if (keyId == null) {
            return Usage.none();
        }
        AtomicLong total = totals.get(keyId);
        DayCount day = daily.get(keyId);
        return new Usage(
                day != null && day.day().equals(today()) ? day.count() : 0,
                total == null ? 0 : total.get());
    }

    @Override
    public String kind() {
        return "memory";
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(DAY_ZONE));
    }

    private record DayCount(LocalDate day, long count) {
    }
}
