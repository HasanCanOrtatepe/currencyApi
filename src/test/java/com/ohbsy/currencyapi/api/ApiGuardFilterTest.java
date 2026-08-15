package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.utilities.ApiKeyHasher;
import com.ohbsy.currencyapi.dataAccess.ApiKeyStore;
import com.ohbsy.currencyapi.dataAccess.InMemoryApiKeyStore;
import com.ohbsy.currencyapi.dataAccess.InMemoryApiKeyUsageCounter;
import com.ohbsy.currencyapi.dataAccess.InMemoryRateLimiter;
import com.ohbsy.currencyapi.dataAccess.RateLimiter;
import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    private InMemoryApiKeyUsageCounter usageCounter;

    @BeforeEach
    void setUp() {
        properties = new CurrencyApiProperties();
        properties.getAuth().setEnabled(true);
        properties.getAuth().setKeySpec("gizli-anahtar=crm");
        apiKeyStore = new InMemoryApiKeyStore();
        clients = new ApiClientResolver(properties, apiKeyStore, FIXED);
        RateLimiter limiter = new InMemoryRateLimiter(properties, FIXED);
        usageCounter = new InMemoryApiKeyUsageCounter(FIXED);
        filter = new ApiGuardFilter(clients, limiter, usageCounter, TestMessages.create());
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
     * görünür ve orkestratör çalışan servisi sürekli yeniden başlatırdı. Sondalar
     * (liveness/readiness) aynı gerekçeyle muaftır.
     */
    @Test
    @DisplayName("/actuator/health ve sondaları anahtar istemez (orkestratör muafiyeti)")
    void healthEndpointIsExempt() {
        for (String path : new String[] {"/actuator/health",
                "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", path)))
                    .as("muaf olmalı: %s", path)
                    .isTrue();
        }
    }

    /**
     * Muafiyet önce {@code /actuator} önekinin TAMAMINAydı ve public portta ölçülerek
     * doğrulanmış bir sızıntıydı: {@code /actuator/metrics} anahtarsız cevap veriyor, JDK
     * sürümünü ({@code jvm.info}), disk boşluğunu ve {@code http.server.requests} üzerinden
     * tüm uç listesini dışarı veriyordu. Üstelik filtreden çıktığı için KOTASIZDI.
     */
    @Test
    @DisplayName("Sağlık DIŞINDAKİ actuator yolları muaf DEĞİLDİR")
    void nonHealthActuatorPathsAreNotExempt() {
        for (String path : new String[] {"/actuator", "/actuator/metrics", "/actuator/info",
                "/actuator/metrics/jvm.info", "/actuator/health-detay"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", path)))
                    .as("kapıdan geçmeli: %s", path)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("/actuator/metrics anahtarsız çağrılınca 401 alır")
    void metricsRequiresKey() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/metrics"), response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * Kök yol tanıtım sayfasıdır (static/index.html), veri döndürmez — anahtar istemesin.
     *
     * <p>VARLIKLAR da listede olmalı: yalnız sayfa muaf tutulup favicon/logo unutulduğunda
     * sayfa açılıyor ama sekme ikonu 401 alıyordu (canlıda ölçülerek bulundu).
     */
    @Test
    @DisplayName("Tanıtım sayfası VE varlıkları anahtar istemez")
    void publicStaticAssetsAreExempt() {
        for (String path : new String[] {"/", "/index.html", "/favicon.svg", "/favicon-32.png",
                "/apple-touch-icon.png", "/logo.svg"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", path)))
                    .as("muaf olmalı: %s", path)
                    .isTrue();
        }
    }

    /** Muafiyet bir LİSTEDİR, "her statik dosya serbest" değil. */
    @Test
    @DisplayName("Listede olmayan bir dosya muaf DEĞİLDİR")
    void unlistedStaticPathIsNotExempt() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/gizli.svg")))
                .isFalse();
    }

    /**
     * Pano gerçek kuru göstermelidir; alternatif olan "sayfaya anahtar gömmek" anahtarı
     * yakmak olurdu. Bu yüzden önizleme ucu anahtarsızdır.
     */
    @Test
    @DisplayName("Önizleme ucu anahtarsız GEÇER (tanıtım panosu için)")
    void publicPreviewPassesWithoutKey() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/rates/preview");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * {@code shouldNotFilter} ile muaf tutulsaydı hız sınırı da uygulanmazdı ve herkese açık
     * bir uç sınırsız kalırdı.
     */
    @Test
    @DisplayName("Önizleme ucu anahtarsız ama SINIRSIZ değil — kota IP başına uygulanır")
    void publicPreviewIsStillRateLimited() throws Exception {
        properties.getRateLimit().setLimit(2);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/rates/preview"), ok,
                    new MockFilterChain());
            assertThat(ok.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/rates/preview"), blocked,
                new MockFilterChain());

        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    /** Muafiyet TAM EŞLEŞMEDİR: asıl kur ucu anahtar istemeye devam etmelidir. */
    @Test
    @DisplayName("Muafiyet asıl /api/v1/rates ucuna SIZMAZ")
    void previewExemptionDoesNotLeakToMainEndpoint() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(null), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * Sıra tersken (401 önce, kota sonra) başarısız kimlik doğrulaması hiç sayılmıyordu:
     * anahtar denemesi SINIRSIZDI ve dinamik anahtar biçimindeki her deneme ayrıca bir Redis
     * okuması harcatıyordu. Kapatılan şey kaba kuvvet değil (256 bitlik anahtara zaten
     * hesaplanamaz), anonim bir çağıranın servisten sınırsız iş çekebildiği tek yoldur.
     */
    @Test
    @DisplayName("Başarısız anahtar denemesi de kota harcar → sonunda 401 değil 429")
    void failedAuthConsumesQuota() throws Exception {
        properties.getRateLimit().setLimit(2);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse rejected = new MockHttpServletResponse();
            filter.doFilter(request("yanlis-anahtar"), rejected, new MockFilterChain());
            assertThat(rejected.getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("yanlis-anahtar"), blocked, new MockFilterChain());

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
    }

    /**
     * Tünel arkasında {@code getRemoteAddr()} her internet isteği için AYNI adresi verir; o
     * hâliyle "kota IP başına" cümlesi üretimde doğru değildi ve tek bir çağıran anahtarsız
     * ucu herkes adına tüketebiliyordu. Başlık YALNIZ anonim istekte okunur.
     */
    @Nested
    @DisplayName("Anonim kimlik — istemci adresi")
    class ClientIdentity {

        private MockHttpServletRequest preview(String forwardedIp) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/v1/rates/preview");
            request.setRemoteAddr("10.89.0.3");   // tünel arkasında herkes için AYNI
            if (forwardedIp != null) {
                request.addHeader("CF-Connecting-IP", forwardedIp);
            }
            return request;
        }

        @Test
        @DisplayName("Başlık yapılandırıldığında farklı istemciler AYRI kovalara yazılır")
        void separateBucketsPerClient() throws Exception {
            properties.getRateLimit().setClientIpHeader("CF-Connecting-IP");
            properties.getRateLimit().setLimit(1);

            MockHttpServletResponse first = new MockHttpServletResponse();
            filter.doFilter(preview("203.0.113.7"), first, new MockFilterChain());
            MockHttpServletResponse second = new MockHttpServletResponse();
            filter.doFilter(preview("203.0.113.8"), second, new MockFilterChain());

            assertThat(first.getStatus()).isEqualTo(200);
            // Kotayı ilk istemci doldurdu; ikincisi bundan ETKİLENMEMELİ.
            assertThat(second.getStatus()).isEqualTo(200);

            MockHttpServletResponse repeat = new MockHttpServletResponse();
            filter.doFilter(preview("203.0.113.7"), repeat, new MockFilterChain());
            assertThat(repeat.getStatus()).isEqualTo(429);
        }

        @Test
        @DisplayName("Başlık yapılandırılmadıysa okunmaz — güvenmek bilinçli bir karardır")
        void headerIgnoredWhenNotConfigured() throws Exception {
            properties.getRateLimit().setLimit(1);

            MockHttpServletResponse first = new MockHttpServletResponse();
            filter.doFilter(preview("203.0.113.7"), first, new MockFilterChain());
            MockHttpServletResponse second = new MockHttpServletResponse();
            filter.doFilter(preview("203.0.113.8"), second, new MockFilterChain());

            assertThat(first.getStatus()).isEqualTo(200);
            assertThat(second.getStatus()).isEqualTo(429);
        }

        /** Anahtarla gelen tüketicinin kimliği ADIDIR; uydurulabilir bir alan onu ezemez. */
        @Test
        @DisplayName("Anahtarlı istekte başlık kimliği DEĞİŞTİRMEZ")
        void authenticatedIdentityIgnoresHeader() throws Exception {
            properties.getRateLimit().setClientIpHeader("CF-Connecting-IP");
            properties.getRateLimit().setLimit(1);

            MockHttpServletRequest first = request("gizli-anahtar");
            first.addHeader("CF-Connecting-IP", "203.0.113.7");
            filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

            MockHttpServletRequest second = request("gizli-anahtar");
            second.addHeader("CF-Connecting-IP", "203.0.113.8");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(second, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(429);
        }
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

    /**
     * Birikmeli kullanım sayacı — admin panelindeki "Bugün/Toplam" sütunlarının kaynağı.
     *
     * <p>Panel önce yalnız hız sınırının kalan hakkını gösteriyordu; o sayı 1 dakikalık
     * pencereye ait olduğu için seyrek çağıran bir tüketicide <b>hiç değişmiyor</b> görünüyordu.
     */
    @Nested
    @DisplayName("Kullanım sayımı")
    class UsageCounting {

        private String saveKey(String id, String consumer) {
            String rawKey = ApiKeyHasher.generateRawKey();
            apiKeyStore.save(new ApiKeyRecord(id, consumer,
                    ApiKeyHasher.sha256Hex(rawKey), ApiKeyHasher.preview(rawKey),
                    FIXED.instant(), null, null, null));
            return rawKey;
        }

        @Test
        @DisplayName("Geçen her istek ANAHTAR kimliğine sayılır")
        void countsServedRequests() throws Exception {
            String rawKey = saveKey("id-usage", "raporlama");

            for (int i = 0; i < 3; i++) {
                filter.doFilter(request(rawKey), new MockHttpServletResponse(),
                        new MockFilterChain());
            }

            assertThat(usageCounter.of("id-usage").total()).isEqualTo(3);
            assertThat(usageCounter.of("id-usage").today()).isEqualTo(3);
        }

        /**
         * 429 alan istek servis EDİLMEDİ. Sayılsaydı panelin "kullanım" sütunu, tüketiciye
         * hiç dönmemiş cevapları da gösterirdi; kaçak bir döngü zaten hız sınırı WARN'ında
         * görünür.
         */
        @Test
        @DisplayName("Kotayı AŞAN istek sayılmaz — servis edilmeyen istek kullanım değildir")
        void doesNotCountThrottledRequests() throws Exception {
            properties.getRateLimit().setLimit(2);
            String rawKey = saveKey("id-throttled", "tasan");

            for (int i = 0; i < 5; i++) {
                filter.doFilter(request(rawKey), new MockHttpServletResponse(),
                        new MockFilterChain());
            }

            assertThat(usageCounter.of("id-throttled").total()).isEqualTo(2);
        }

        @Test
        @DisplayName("401 alan istek sayılmaz")
        void doesNotCountRejectedRequests() throws Exception {
            filter.doFilter(request("bilinmeyen-anahtar"), new MockHttpServletResponse(),
                    new MockFilterChain());

            assertThat(usageCounter.of("id-usage")).isEqualTo(
                    com.ohbsy.currencyapi.dataAccess.ApiKeyUsageCounter.Usage.none());
        }

        /**
         * Statik anahtarların panelde satırı yoktur (açılışta bellek içi bean'e gömülüdürler).
         * Sayaç onlar için sessizce atlanmalıdır — patlarsa CRM'in isteği düşer.
         */
        @Test
        @DisplayName("Statik anahtar sayaca yazılmaz ama isteği GEÇER")
        void staticKeyIsServedButNotCounted() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("gizli-anahtar"), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(200);
        }
    }
}
