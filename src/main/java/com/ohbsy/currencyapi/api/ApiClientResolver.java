package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.utilities.ApiKeyHasher;
import com.ohbsy.currencyapi.dataAccess.ApiKeyStore;
import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * API anahtarını <b>tüketici kimliğine</b> çevirir.
 *
 * <h2>Neden anahtar değil "kimlik" döner</h2>
 * Anahtarın kendisi log'a, metriğe ve hata mesajına <b>girmez</b>. "Hangi tüketici kotayı
 * doldurdu" sorusu bir ada (`crm`, `reporting`) bakarak cevaplanabilmelidir; anahtara bakarak
 * cevaplanan bir sistem, o soruyu her soruşta sırrı bir yere daha yazar.
 *
 * <h2>Statik + dinamik, bu sırayla</h2>
 * Önce {@code CurrencyApiProperties.Auth.keys} (bellek içi, açılışta donmuş, Redis'ten
 * bağımsız) kontrol edilir; yalnız orada bulunamazsa {@link ApiKeyStore} (admin tarafından
 * runtime'da oluşturulmuş anahtarlar) sorgulanır. Bu sıra bilinçlidir: statik anahtarla gelen
 * bir tüketici, dinamik depoyu tutan Redis çökse bile çalışmaya devam eder.
 *
 * <h2>Tek çözümleme, tek Redis çağrısı</h2>
 * {@link #resolveClient} bir istek için TÜM bilgiyi (kimlik + varsa özel hız limiti) tek
 * seferde döner. Çağıran ({@link ApiGuardFilter}) bunu bir kez çağırıp sonucu tekrar kullanır —
 * ayrı ayrı {@code resolve()}/{@code rateLimitOverride()} çağırmak dinamik bir anahtar için
 * her seferinde ayrı bir Redis okuması VE {@code lastUsedAt} yazması demek olurdu.
 */
@Component
public class ApiClientResolver {

    private static final Logger log = LoggerFactory.getLogger(ApiClientResolver.class);

    /** Ticari kur API'lerinin yaygın başlığı — tüketiciler için tanıdık olsun. */
    public static final String API_KEY_HEADER = "X-API-Key";

    /** {@code lastUsedAt} bu aralıktan sık güncellenmez — bkz. {@link #touchLastUsed}. */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final CurrencyApiProperties properties;
    private final ApiKeyStore apiKeyStore;
    private final Clock clock;

    public ApiClientResolver(CurrencyApiProperties properties, ApiKeyStore apiKeyStore,
                             Clock clock) {
        this.properties = properties;
        this.apiKeyStore = apiKeyStore;
        this.clock = clock;
    }

    /**
     * Yanlış yapılandırma <b>açılışta ve gürültülü</b> düşer: kimlik doğrulama açık ama hiç
     * statik anahtar tanımlı değilse servis her isteği 401'leyerek "çalışıyor" görünürdü —
     * sessiz ve teşhisi zor bir arıza. Ayağa kalkmaması, yanlış çalışmasından iyidir.
     *
     * <p>Yalnız statik anahtarlar sayılır: dinamik depo boş olması bir açılış hatası değildir
     * (admin henüz hiç anahtar oluşturmamış olabilir, bu normaldir).
     */
    @PostConstruct
    void validateConfiguration() {
        if (properties.getAuth().isEnabled() && properties.getAuth().getKeys().isEmpty()) {
            throw new IllegalStateException(
                    "currency-api.auth.enabled=true ama hic anahtar tanimli degil "
                            + "(currency-api.auth.keys). Anahtarlar ortam degiskeninden verilir.");
        }
        if (properties.getAuth().isEnabled()) {
            log.info("API anahtari dogrulamasi ACIK, tanimli statik tuketici sayisi={}",
                    properties.getAuth().getKeys().size());
        } else {
            log.info("API anahtari dogrulamasi KAPALI (currency-api.auth.enabled=false)");
        }
    }

    public boolean isAuthEnabled() {
        return properties.getAuth().isEnabled();
    }

    /** @param consumerName tüketici adı, @param rateLimitOverride yalnız dinamik anahtarda dolu */
    public record ResolvedClient(String consumerName, Integer rateLimitOverride) {
    }

    /**
     * İsteği bir tüketiciye çözer — statik anahtar, yoksa dinamik anahtar (aktifse), yoksa boş.
     * İptal edilmiş bir dinamik anahtar bulunamamış gibi davranır.
     */
    public Optional<ResolvedClient> resolveClient(HttpServletRequest request) {
        Optional<String> rawKey = rawKey(request);
        if (rawKey.isEmpty()) {
            return Optional.empty();
        }
        String key = rawKey.get();

        String staticConsumer = properties.getAuth().getKeys().get(key);
        if (staticConsumer != null) {
            return Optional.of(new ResolvedClient(staticConsumer, null));
        }

        return apiKeyStore.findByHash(ApiKeyHasher.sha256Hex(key))
                .filter(ApiKeyRecord::isActive)
                .map(record -> new ResolvedClient(record.consumerName(),
                        touchLastUsed(record).rateLimitOverride()));
    }

    /** İstekteki anahtara karşılık gelen tüketici adı; anahtar yok/tanınmıyorsa boş. */
    public Optional<String> resolve(HttpServletRequest request) {
        return resolveClient(request).map(ResolvedClient::consumerName);
    }

    /**
     * Hız sınırının sayacı hangi kimliğe yazılacak.
     *
     * <p>Kimlik doğrulama kapalıyken <b>uzak adrese</b> düşülür: sınırın hiç uygulanmaması,
     * kaçak bir döngünün serbest kalması demek olurdu. Mükemmel bir kimlik değildir (NAT
     * arkasında tüm tüketiciler tek adres görünür) ama sınırın amacı kimlik doğrulamak değil,
     * yarıçapı sınırlamaktır.
     */
    public String rateLimitIdentity(HttpServletRequest request) {
        return resolve(request).orElseGet(() -> "ip:" + request.getRemoteAddr());
    }

    /**
     * Bulunan kayıt best-effort {@code lastUsedAt} güncellemesiyle geri döner (fail-open: bu
     * yazının kaybı güvenliği etkilemez, yetkilendirme kararı zaten verildi).
     *
     * <h2>Neden her istekte yazılmıyor</h2>
     * Bu alan yalnız admin panelindeki "son kullanım" sütunu içindir; dakika hassasiyeti
     * fazlasıyla yeterlidir. Her istekte yazılsaydı <b>okuma yolundaki her çağrı bir Redis
     * yazması üretirdi</b> — kotası 120/dk olan tek bir tüketici bile dakikada 120 gereksiz
     * yazma demektir ve bu, sırf bir gösterge alanı için ödenen bir bedeldir.
     */
    private ApiKeyRecord touchLastUsed(ApiKeyRecord record) {
        Instant now = clock.instant();
        if (record.lastUsedAt() != null
                && Duration.between(record.lastUsedAt(), now).compareTo(LAST_USED_RESOLUTION) < 0) {
            return record;
        }
        try {
            ApiKeyRecord updated = record.withLastUsedAt(now);
            apiKeyStore.save(updated);
            return updated;
        } catch (Exception e) {
            log.warn("son kullanim zamani guncellenemedi id={} sebep={}",
                    record.id(), e.toString());
            return record;
        }
    }

    private Optional<String> rawKey(HttpServletRequest request) {
        String key = request.getHeader(API_KEY_HEADER);
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(key.trim());
    }
}
