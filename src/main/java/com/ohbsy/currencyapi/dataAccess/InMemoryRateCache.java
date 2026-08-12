package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bellek içi cache — <b>tek instance</b> kurulumlar, yerel geliştirme ve testler için.
 * Varsayılan uygulamadır: servis Redis olmadan da ayağa kalkabilmelidir, yoksa bir birim
 * testi altyapı gerektirir ve "test edilebilir" iddiası boşa çıkar.
 *
 * <p><b>Üretimde çok instance varsa Redis kullanılmalıdır</b> ({@code currency-api.cache.type=redis}):
 * burada her instance kendi tablosunu tutar, yani satıcı isteği instance sayısıyla çarpılır ve
 * iki instance farklı kur döndürebilir. Sınır kodda değil kurulumdadır ve bilinçle böyledir —
 * tek instance için Redis zorunlu kılmak gereksiz bir bağımlılık olurdu.
 *
 * <p>{@code retention} burada da uygulanır: süresi geçmiş kayıt "yok" sayılır, böylece
 * çok eski bir kurun sonsuza dek sunulması engellenir.
 */
@Component
@ConditionalOnProperty(name = "currency-api.cache.type", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryRateCache implements RateCache {

    private final Map<String, ExchangeRateSnapshot> entries = new ConcurrentHashMap<>();
    private final CurrencyApiProperties properties;
    private final Clock clock;

    public InMemoryRateCache(CurrencyApiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<ExchangeRateSnapshot> find(String providerName) {
        ExchangeRateSnapshot snapshot = entries.get(providerName);
        if (snapshot == null) {
            return Optional.empty();
        }
        Instant expiresAt = snapshot.fetchedAt().plus(properties.getCache().getRetention());
        if (expiresAt.isBefore(clock.instant())) {
            entries.remove(providerName);
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    @Override
    public void put(String providerName, ExchangeRateSnapshot snapshot) {
        entries.put(providerName, snapshot);
    }

    @Override
    public String kind() {
        return "memory";
    }
}
