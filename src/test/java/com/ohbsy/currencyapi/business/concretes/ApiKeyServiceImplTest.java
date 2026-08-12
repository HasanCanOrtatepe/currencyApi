package com.ohbsy.currencyapi.business.concretes;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.dataAccess.InMemoryApiKeyStore;
import com.ohbsy.currencyapi.dataAccess.InMemoryRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiKeyServiceImpl — create/list/revoke")
class ApiKeyServiceImplTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-12T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryApiKeyStore store;
    private ApiKeyServiceImpl service;

    @BeforeEach
    void setUp() {
        store = new InMemoryApiKeyStore();
        CurrencyApiProperties properties = new CurrencyApiProperties();
        service = new ApiKeyServiceImpl(store, new InMemoryRateLimiter(properties, FIXED), FIXED);
    }

    @Test
    @DisplayName("create benzersiz ham anahtar üretir, depoda yalnız hash tutulur")
    void createGeneratesUniqueRawKeyAndStoresOnlyHash() {
        ApiKeyCreationResult a = service.create("crm", null);
        ApiKeyCreationResult b = service.create("crm", null);

        assertThat(a.rawKey()).isNotEqualTo(b.rawKey());
        assertThat(store.findAll()).allSatisfy(record ->
                assertThat(record.keyHash()).isNotEqualTo(a.rawKey()).isNotEqualTo(b.rawKey()));
    }

    @Test
    @DisplayName("list() aktif+iptal kayıtları anlık kullanımla birlikte döner")
    void listAttachesLiveUsage() {
        service.create("crm", null);

        var rows = service.list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).usageLimit()).isEqualTo(120);
        assertThat(rows.get(0).usageRemaining()).isEqualTo(120);
        assertThat(rows.get(0).isActive()).isTrue();
    }

    @Test
    @DisplayName("revoke bilinmeyen id için false döner")
    void revokeUnknownIdReturnsFalse() {
        assertThat(service.revoke("hic-var-olmayan-id")).isFalse();
    }

    @Test
    @DisplayName("revoke bilinen id'yi iptal eder, list() bunu yansıtır")
    void revokeKnownIdMarksInactive() {
        ApiKeyCreationResult created = service.create("crm", null);

        boolean result = service.revoke(created.id());

        assertThat(result).isTrue();
        assertThat(service.list().get(0).isActive()).isFalse();
    }
}
