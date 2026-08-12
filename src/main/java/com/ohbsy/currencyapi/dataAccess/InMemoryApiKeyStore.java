package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bellek içi anahtar deposu — tek instance, yerel geliştirme ve testler için. {@link
 * InMemoryRateLimiter}/{@link InMemoryRateCache} ile aynı gerekçe: servis Redis olmadan da
 * ayağa kalkabilmelidir, aksi hâlde basit bir birim testi bile altyapı gerektirirdi.
 *
 * <p><b>Çok instance'lı üretimde yanlıştır ve bilinçlidir:</b> her instance kendi anahtar
 * kümesini tutar. Üretimde {@code currency-api.cache.type=redis} kullanılmalıdır.
 */
@Component
@ConditionalOnProperty(name = "currency-api.cache.type", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryApiKeyStore implements ApiKeyStore {

    private final Map<String, ApiKeyRecord> byId = new ConcurrentHashMap<>();
    private final Map<String, String> hashToId = new ConcurrentHashMap<>();

    @Override
    public void save(ApiKeyRecord record) {
        byId.put(record.id(), record);
        hashToId.put(record.keyHash(), record.id());
    }

    @Override
    public Optional<ApiKeyRecord> findByHash(String keyHash) {
        String id = hashToId.get(keyHash);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<ApiKeyRecord> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public Optional<ApiKeyRecord> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public String kind() {
        return "memory";
    }
}
