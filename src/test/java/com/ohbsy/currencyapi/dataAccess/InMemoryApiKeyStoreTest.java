package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryApiKeyStore — save/find/revoke")
class InMemoryApiKeyStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");

    private InMemoryApiKeyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryApiKeyStore();
    }

    private ApiKeyRecord record(String id, String hash) {
        return new ApiKeyRecord(id, "crm", hash, "cur_ab…yz", NOW, null, null, null);
    }

    @Test
    @DisplayName("save + findByHash round-trip")
    void saveAndFindByHash() {
        store.save(record("id-1", "hash-1"));

        assertThat(store.findByHash("hash-1")).contains(record("id-1", "hash-1"));
        assertThat(store.findByHash("bilinmeyen-hash")).isEmpty();
    }

    @Test
    @DisplayName("findById kaydı id ile bulur")
    void findById() {
        store.save(record("id-1", "hash-1"));

        assertThat(store.findById("id-1")).isPresent();
        assertThat(store.findById("bilinmeyen-id")).isEmpty();
    }

    @Test
    @DisplayName("findAll aktif VE iptal edilmiş tüm kayıtları döner")
    void findAllIncludesRevoked() {
        store.save(record("id-1", "hash-1"));
        store.save(record("id-2", "hash-2").revoked(NOW));

        assertThat(store.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("İptal sonrası findByHash().isActive() false döner, findById kaydı yine bulur")
    void revokeMarksInactiveButKeepsHistory() {
        store.save(record("id-1", "hash-1"));

        store.save(record("id-1", "hash-1").revoked(NOW));

        assertThat(store.findByHash("hash-1")).get()
                .extracting(ApiKeyRecord::isActive).isEqualTo(false);
        assertThat(store.findById("id-1")).isPresent();
    }

    @Test
    @DisplayName("kind() 'memory' döner")
    void kindIsMemory() {
        assertThat(store.kind()).isEqualTo("memory");
    }
}
