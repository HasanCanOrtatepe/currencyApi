package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.utilities.ApiKeyHasher;
import com.ohbsy.currencyapi.dataAccess.ApiKeyStore;
import com.ohbsy.currencyapi.dataAccess.InMemoryApiKeyStore;
import com.ohbsy.currencyapi.dataAccess.InMemoryRateLimiter;
import com.ohbsy.currencyapi.dataAccess.RateLimiter;
import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Ticari API davranışı: anahtar (401) ve kota (429).
 *
 * <p>Bu testlerin varlık sebebi, iki yolun da <b>mutlu yolda hiç çalışmamasıdır</b>: doğru
 * yapılandırılmış bir tüketici ne 401 ne 429 görür. Test edilmezlerse ilk kez üretimde,
 * en kötü anda çalışırlar.
 */
@DisplayName("ApiGuardFilter — anahtar ve hız sınırı")
class ApiGuardFilterTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-12T10:00:00Z"), ZoneOffset.UTC);

    private CurrencyApiProperties properties;
    private ApiKeyStore apiKeyStore;
    private ApiClientResolver clients;
    private ApiGuardFilter filter;

    @BeforeEach
    void setUp() {
        properties = new CurrencyApiProperties();
        properties.getAuth().setEnabled(true);
        properties.getAuth().setKeySpec("gizli-anahtar=crm");
        apiKeyStore = new InMemoryApiKeyStore();
        clients = new ApiClientResolver(properties, apiKeyStore, FIXED);
        RateLimiter limiter = new InMemoryRateLimiter(properties, FIXED);
        filter = new ApiGuardFilter(clients, limiter, TestMessages.create());
    }

    private MockHttpServletRequest request(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rates");
        if (key != null) {
            request.addHeader(ApiClientResolver.API_KEY_HEADER, key);
        }
        return request;
    }

    @Test
    @DisplayName("Geçerli anahtar geçer ve kalan hak başlıkta bildirilir")
    void validKeyPasses() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("gizli-anahtar"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("120");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("119");
    }

    @Test
    @DisplayName("Anahtar yok ya da tanınmıyor → 401, istek servise İNMEZ")
    void missingOrUnknownKeyRejected() throws Exception {
        for (String key : new String[] {null, "yanlis-anahtar"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request(key), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(chain, never()).doFilter(request(key), response);
        }
    }

    /**
     * Anahtar <b>hiçbir koşulda</b> log'a ya da cevaba girmez: yanlış anahtar bile bir sırdır
     * (çoğu zaman BAŞKA bir ortamın geçerli anahtarıdır).
     */
    @Test
    @DisplayName("Reddedilen istekte anahtar cevaba SIZMAZ")
    void rejectedResponseDoesNotLeakKey() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("cok-gizli-yanlis-anahtar"), response, new MockFilterChain());

        assertThat(response.getContentAsString()).doesNotContain("cok-gizli-yanlis-anahtar");
    }

    @Test
    @DisplayName("Kota aşılınca 429 + Retry-After; sonraki istekler servise inmez")
    void rateLimitExceededYields429() throws Exception {
        properties.getRateLimit().setLimit(3);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(request("gizli-anahtar"), ok, new MockFilterChain());
            assertThat(ok.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("gizli-anahtar"), blocked, chain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        // Tüketici ne zaman döneceğini TAHMİN ETMEK zorunda kalmamalı.
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(blocked.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        verify(chain, never()).doFilter(request("gizli-anahtar"), blocked);
    }

    /**
     * Orkestratörün elinde anahtar YOKTUR. Sağlık ucu anahtar isteseydi her kurulum sağlıksız
     * görünür ve orkestratör çalışan servisi sürekli yeniden başlatırdı.
     */
    @Test
    @DisplayName("/actuator anahtar istemez (orkestratör muafiyeti)")
    void actuatorIsExempt() {
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(filter.shouldNotFilter(health)).isTrue();
    }

    /** Kök yol tanıtım sayfasıdır (static/index.html), veri döndürmez — anahtar istemesin. */
    @Test
    @DisplayName("/ (tanıtım sayfası) anahtar istemez")
    void rootPathIsExempt() {
        MockHttpServletRequest root = new MockHttpServletRequest("GET", "/");
        MockHttpServletRequest indexHtml = new MockHttpServletRequest("GET", "/index.html");

        assertThat(filter.shouldNotFilter(root)).isTrue();
        assertThat(filter.shouldNotFilter(indexHtml)).isTrue();
    }

    @Test
    @DisplayName("Kimlik doğrulama kapalıyken anahtarsız istek geçer (sınır yine uygulanır)")
    void authDisabledAllowsAnonymous() throws Exception {
        properties.getAuth().setEnabled(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(null), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isNotNull();
    }

    /** Sessizce her şeyi 401'lemektense açılışta gürültülü düşmek. */
    @Test
    @DisplayName("Anahtar doğrulaması açık ama anahtar tanımsız → AÇILIŞTA düşer")
    void authEnabledWithoutKeysFailsFast() {
        CurrencyApiProperties broken = new CurrencyApiProperties();
        broken.getAuth().setEnabled(true);

        assertThatThrownBy(() -> new ApiClientResolver(broken, new InMemoryApiKeyStore(), FIXED)
                .validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hic anahtar tanimli degil");
    }

    @Test
    @DisplayName("Dinamik anahtarın özel limiti globali ezer")
    void dynamicKeyHonorsRateLimitOverride() throws Exception {
        String rawKey = ApiKeyHasher.generateRawKey();
        apiKeyStore.save(new ApiKeyRecord("id-1", "reporting",
                ApiKeyHasher.sha256Hex(rawKey), ApiKeyHasher.preview(rawKey),
                FIXED.instant(), null, 2, null));

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(request(rawKey), ok, new MockFilterChain());
            assertThat(ok.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request(rawKey), blocked, new MockFilterChain());

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("X-RateLimit-Limit")).isEqualTo("2");
    }

    @Test
    @DisplayName("İptal edilmiş dinamik anahtar → 401 (bilinmeyen anahtarla aynı davranış)")
    void revokedDynamicKeyRejected() throws Exception {
        String rawKey = ApiKeyHasher.generateRawKey();
        apiKeyStore.save(new ApiKeyRecord("id-2", "reporting",
                ApiKeyHasher.sha256Hex(rawKey), ApiKeyHasher.preview(rawKey),
                FIXED.instant(), FIXED.instant(), null, null));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(rawKey), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }
}
