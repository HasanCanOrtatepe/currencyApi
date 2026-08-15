package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.utilities.ApiKeyHasher;
import com.ohbsy.currencyapi.dataAccess.InMemoryApiKeyStore;
import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statik ({@code CURRENCY_API_KEYS}) ve dinamik (admin tarafından oluşturulmuş) anahtarların
 * birlikte, doğru öncelikle çözüldüğünü doğrular.
 */
@DisplayName("ApiClientResolver — statik + dinamik anahtar")
class ApiClientResolverTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-12T10:00:00Z"), ZoneOffset.UTC);

    private CurrencyApiProperties properties;
    private InMemoryApiKeyStore apiKeyStore;
    private ApiClientResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new CurrencyApiProperties();
        properties.getAuth().setKeySpec("statik-anahtar=crm");
        apiKeyStore = new InMemoryApiKeyStore();
        resolver = new ApiClientResolver(properties, apiKeyStore, FIXED);
    }

    private MockHttpServletRequest request(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (key != null) {
            request.addHeader(ApiClientResolver.API_KEY_HEADER, key);
        }
        return request;
    }

    @Test
    @DisplayName("Statik anahtar (regresyon) çalışmaya devam eder")
    void staticKeyResolves() {
        var resolved = resolver.resolveClient(request("statik-anahtar"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().consumerName()).isEqualTo("crm");
        assertThat(resolved.get().rateLimitOverride()).isNull();
    }

    @Test
    @DisplayName("Dinamik aktif anahtar consumer adına çözülür")
    void dynamicActiveKeyResolves() {
        String rawKey = ApiKeyHasher.generateRawKey();
        apiKeyStore.save(new ApiKeyRecord("id-1", "reporting",
                ApiKeyHasher.sha256Hex(rawKey), ApiKeyHasher.preview(rawKey),
                FIXED.instant(), null, null, null));

        var resolved = resolver.resolveClient(request(rawKey));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().consumerName()).isEqualTo("reporting");
    }

    @Test
    @DisplayName("İptal edilmiş dinamik anahtar çözülmez")
    void revokedDynamicKeyDoesNotResolve() {
        String rawKey = ApiKeyHasher.generateRawKey();
        apiKeyStore.save(new ApiKeyRecord("id-2", "reporting",
                ApiKeyHasher.sha256Hex(rawKey), ApiKeyHasher.preview(rawKey),
                FIXED.instant(), FIXED.instant(), null, null));

        assertThat(resolver.resolveClient(request(rawKey))).isEmpty();
    }

    @Test
    @DisplayName("Bilinmeyen anahtar çözülmez")
    void unknownKeyDoesNotResolve() {
        assertThat(resolver.resolveClient(request("hic-var-olmayan-anahtar"))).isEmpty();
    }

    @Test
    @DisplayName("Dinamik anahtarın rateLimitOverride'ı taşınır, statik anahtarınki her zaman boştur")
    void rateLimitOverrideOnlyForDynamicKeys() {
        String rawKey = ApiKeyHasher.generateRawKey();
        apiKeyStore.save(new ApiKeyRecord("id-3", "reporting",
                ApiKeyHasher.sha256Hex(rawKey), ApiKeyHasher.preview(rawKey),
                FIXED.instant(), null, 7, null));

        assertThat(resolver.resolveClient(request(rawKey)).get().rateLimitOverride())
                .isEqualTo(7);
        assertThat(resolver.resolveClient(request("statik-anahtar")).get().rateLimitOverride())
                .isNull();
    }

    @Test
    @DisplayName("Anahtarsız istek çözülmez")
    void noKeyDoesNotResolve() {
        assertThat(resolver.resolveClient(request(null))).isEmpty();
    }

    /**
     * {@code lastUsedAt} yalnız admin panelindeki gösterge içindir. Her istekte yazılsaydı
     * OKUMA yolundaki her çağrı bir Redis YAZMASI üretirdi — kotası 120/dk olan tek bir
     * tüketici bile dakikada 120 gereksiz yazma demektir.
     */
    @Test
    @DisplayName("lastUsedAt her istekte YAZILMAZ (dakikada bir yeter)")
    void lastUsedAtIsThrottled() {
        String rawKey = ApiKeyHasher.generateRawKey();
        apiKeyStore.save(new ApiKeyRecord("id-4", "crm",
                ApiKeyHasher.sha256Hex(rawKey), ApiKeyHasher.preview(rawKey),
                FIXED.instant(), null, null, null));

        // İlk çözümleme yazar (lastUsedAt henüz null).
        resolver.resolveClient(request(rawKey));
        Instant afterFirst = apiKeyStore.findById("id-4").orElseThrow().lastUsedAt();
        assertThat(afterFirst).isEqualTo(FIXED.instant());

        // Saat İLERLEMEDİĞİ için ikinci çözümleme yeniden yazmamalı.
        resolver.resolveClient(request(rawKey));

        assertThat(apiKeyStore.findById("id-4").orElseThrow().lastUsedAt())
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("Bir dakika geçtikten sonra lastUsedAt yeniden yazılır")
    void lastUsedAtIsWrittenAfterResolutionWindow() {
        String rawKey = ApiKeyHasher.generateRawKey();
        apiKeyStore.save(new ApiKeyRecord("id-5", "crm",
                ApiKeyHasher.sha256Hex(rawKey), ApiKeyHasher.preview(rawKey),
                FIXED.instant(), null, null, FIXED.instant()));

        Instant later = FIXED.instant().plusSeconds(90);
        ApiClientResolver laterResolver = new ApiClientResolver(
                properties, apiKeyStore, Clock.fixed(later, ZoneOffset.UTC));

        laterResolver.resolveClient(request(rawKey));

        assertThat(apiKeyStore.findById("id-5").orElseThrow().lastUsedAt()).isEqualTo(later);
    }

    /**
     * Anonim isteğin adresi hız sınırı kimliğine, oradan da <b>Redis anahtarına ve log
     * satırına</b> girer. Bu yüzden başlık hem opsiyoneldir hem de doğrulanır.
     */
    @Nested
    @DisplayName("clientIp — anonim isteğin adresi")
    class ClientIp {

        private MockHttpServletRequest withHeader(String name, String value) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.89.0.3");
            if (value != null) {
                request.addHeader(name, value);
            }
            return request;
        }

        @Test
        @DisplayName("Başlık yapılandırılmadıysa uzak adres kullanılır")
        void fallsBackToRemoteAddrWhenDisabled() {
            assertThat(resolver.clientIp(withHeader("CF-Connecting-IP", "203.0.113.7")))
                    .isEqualTo("10.89.0.3");
        }

        @Test
        @DisplayName("Yapılandırılan başlık okunur (IPv4 ve IPv6)")
        void readsConfiguredHeader() {
            properties.getRateLimit().setClientIpHeader("CF-Connecting-IP");

            assertThat(resolver.clientIp(withHeader("CF-Connecting-IP", "203.0.113.7")))
                    .isEqualTo("203.0.113.7");
            assertThat(resolver.clientIp(withHeader("CF-Connecting-IP", "2001:db8::1")))
                    .isEqualTo("2001:db8::1");
        }

        /** X-Forwarded-For biçimi liste olabilir; istemci baştadır, vekiller arkada. */
        @Test
        @DisplayName("Liste biçiminde İLK değer alınır")
        void takesFirstValueOfList() {
            properties.getRateLimit().setClientIpHeader("X-Forwarded-For");

            assertThat(resolver.clientIp(
                    withHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178")))
                    .isEqualTo("203.0.113.7");
        }

        /**
         * Satır sonu içeren bir değer log'a sahte satır yazdırabilirdi ({@code
         * CorrelationIdFilter} ile aynı gerekçe), uzun/keyfi bir değer de Redis anahtar alanını
         * şişirirdi. Doğrulanamayan değer sessizce yok sayılır ve uzak adrese düşülür.
         */
        @Test
        @DisplayName("Uydurulmuş/bozuk değer yok sayılır — log forging ve anahtar şişmesi kapalı")
        void rejectsImplausibleValues() {
            properties.getRateLimit().setClientIpHeader("CF-Connecting-IP");

            for (String bogus : new String[] {
                    "203.0.113.7\nWARN sahte satir",
                    "kur.etiyapi.com",
                    "203.0.113.7; DROP",
                    " ",
                    "0123456789".repeat(5)}) {
                assertThat(resolver.clientIp(withHeader("CF-Connecting-IP", bogus)))
                        .as("reddedilmeli: %s", bogus)
                        .isEqualTo("10.89.0.3");
            }
        }

        @Test
        @DisplayName("Başlık yapılandırılmış ama gelmemişse uzak adres kullanılır")
        void fallsBackWhenHeaderAbsent() {
            properties.getRateLimit().setClientIpHeader("CF-Connecting-IP");

            assertThat(resolver.clientIp(withHeader("CF-Connecting-IP", null)))
                    .isEqualTo("10.89.0.3");
        }
    }
}
