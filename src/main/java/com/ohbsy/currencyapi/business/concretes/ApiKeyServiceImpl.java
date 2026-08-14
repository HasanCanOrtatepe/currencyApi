package com.ohbsy.currencyapi.business.concretes;

import com.ohbsy.currencyapi.business.abstracts.ApiKeyService;
import com.ohbsy.currencyapi.core.utilities.ApiKeyHasher;
import com.ohbsy.currencyapi.dataAccess.ApiKeyStore;
import com.ohbsy.currencyapi.dataAccess.ApiKeyUsageCounter;
import com.ohbsy.currencyapi.dataAccess.RateLimiter;
import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyServiceImpl implements ApiKeyService {

    private final ApiKeyStore apiKeyStore;
    private final RateLimiter rateLimiter;
    private final ApiKeyUsageCounter usageCounter;
    private final Clock clock;

    public ApiKeyServiceImpl(ApiKeyStore apiKeyStore, RateLimiter rateLimiter,
                             ApiKeyUsageCounter usageCounter, Clock clock) {
        this.apiKeyStore = apiKeyStore;
        this.rateLimiter = rateLimiter;
        this.usageCounter = usageCounter;
        this.clock = clock;
    }

    @Override
    public ApiKeyCreationResult create(String consumerName, Integer rateLimitOverride) {
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName zorunludur");
        }

        String rawKey = ApiKeyHasher.generateRawKey();
        String id = UUID.randomUUID().toString();
        var createdAt = clock.instant();

        ApiKeyRecord record = new ApiKeyRecord(
                id,
                consumerName.trim(),
                ApiKeyHasher.sha256Hex(rawKey),
                ApiKeyHasher.preview(rawKey),
                createdAt,
                null,
                rateLimitOverride,
                null);

        // save() burada exception YUTMAZ — cagiran (AdminApiKeyController) yakalayip 503'e
        // cevirir. Bir "olustu" cevabinin aslinda kalici olmamasi, gorunur hatadan kotudur.
        apiKeyStore.save(record);

        return new ApiKeyCreationResult(id, rawKey, record.consumerName(), createdAt,
                rateLimitOverride);
    }

    @Override
    public List<ApiKeyUsageView> list() {
        return apiKeyStore.findAll().stream()
                .map(this::toUsageView)
                .toList();
    }

    @Override
    public boolean revoke(String id) {
        Optional<ApiKeyRecord> existing = apiKeyStore.findById(id);
        if (existing.isEmpty()) {
            return false;
        }
        ApiKeyRecord record = existing.get();
        if (record.isActive()) {
            apiKeyStore.save(record.revoked(clock.instant()));
        }
        return true;
    }

    @Override
    public boolean updateRateLimit(String id, Integer rateLimitOverride) {
        if (rateLimitOverride != null && rateLimitOverride < 1) {
            throw new IllegalArgumentException("rateLimitOverride en az 1 olmalidir");
        }
        Optional<ApiKeyRecord> existing = apiKeyStore.findById(id);
        if (existing.isEmpty()) {
            return false;
        }
        apiKeyStore.save(existing.get().withRateLimitOverride(rateLimitOverride));
        return true;
    }

    private ApiKeyUsageView toUsageView(ApiKeyRecord record) {
        // peek: sayaci ARTIRMAZ, yalniz okur — listeleme bir tuketim degildir.
        // Kimlik olarak consumerName kullanilir cunku hiz siniri KOVASI odur (ayni ada bagli
        // iki anahtar ayni kovayi paylasir); birikmeli sayac ise ANAHTAR kimligiyle okunur.
        RateLimiter.Decision usage = rateLimiter.peek(record.consumerName(),
                record.rateLimitOverride());
        ApiKeyUsageCounter.Usage counted = usageCounter.of(record.id());
        return new ApiKeyUsageView(
                record.id(),
                record.consumerName(),
                record.keyPreview(),
                record.createdAt(),
                record.revokedAt(),
                record.rateLimitOverride(),
                record.lastUsedAt(),
                usage.limit(),
                usage.remaining(),
                counted.today(),
                counted.total());
    }
}
