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

    /** IPv6'nın en uzun yazımı (IPv4 gömülü biçim dahil) 45 karakterdir. */
    private static final int MAX_ADDRESS_LENGTH = 45;

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

    /**
     * @param consumerName      tüketici adı — hız sınırının kimliği budur
     * @param rateLimitOverride yalnız dinamik anahtarda dolu
     * @param keyId             dinamik anahtarın kimliği; <b>statik anahtarda {@code null}</b>.
     *                          Kullanım sayacı buna yazılır: panelde bir satır bir ANAHTARdır,
     *                          statik anahtarların ise satırı yoktur (açılışta bellek içi
     *                          bean'e gömülüdürler, runtime'da yönetilmezler).
     */
    public record ResolvedClient(String consumerName, Integer rateLimitOverride, String keyId) {
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
            return Optional.of(new ResolvedClient(staticConsumer, null, null));
        }

        return apiKeyStore.findByHash(ApiKeyHasher.sha256Hex(key))
                .filter(ApiKeyRecord::isActive)
                .map(record -> new ResolvedClient(record.consumerName(),
                        touchLastUsed(record).rateLimitOverride(), record.id()));
    }

    /**
     * Anonim isteğin hız sınırı kimliği — <b>çağıranın adresi</b>.
     *
     * <h2>Neden {@code getRemoteAddr()} tek başına yetmiyor (ölçülerek bulundu)</h2>
     * Servis bir tünelin (Cloudflare) arkasındadır ve konteynere gelen bağlantının kaynağı
     * HER İNTERNET İSTEĞİ İÇİN AYNI adrestir (ölçüldü: {@code remote=10.89.0.3}). Yani
     * "kota IP başına" cümlesi üretimde <b>doğru değildi</b>: tüm dünya tek bir kovayı
     * paylaşıyordu ve tek bir çağıran, anahtarsız önizleme ucunu dakikada 120 istekle
     * herkese 429 yaptırabiliyordu. Kötüye kullanım WARN'ı da kimseyi işaret etmiyordu.
     *
     * <h2>Başlığa neden güvenilebiliyor — ve nerede güvenilemez</h2>
     * Başlık adı <b>yapılandırmadan</b> gelir ({@code currency-api.rate-limit.client-ip-header})
     * ve varsayılanı BOŞTUR: hiçbir kurulum bunu istemeden devralmaz. Üretimde
     * {@code CF-Connecting-IP} verilir; bu başlığı cloudflared her istekte kendisi YAZAR,
     * yani tünelden geçen bir istemci onu uyduramaz. Portu doğrudan güvenilmeyen bir ağa açan
     * bir kurulumda bu ayar <b>verilmemelidir</b> — orada başlık uydurulabilir ve sınır
     * her istekte yeni bir kimlikle atlatılabilirdi.
     *
     * <p>Değer yalnız anonim istekte kullanılır; anahtarla gelen tüketicinin kimliği adıdır.
     * Uydurulabilir bir alanın yarıçapı böylece anahtarsız uçla sınırlı kalır.
     */
    public String clientIp(HttpServletRequest request) {
        String headerName = properties.getRateLimit().getClientIpHeader();
        if (headerName.isBlank()) {
            return request.getRemoteAddr();
        }
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            return request.getRemoteAddr();
        }
        // X-Forwarded-For biçimi liste olabilir ("istemci, vekil1, vekil2"); istemci baştadır.
        String candidate = value.split(",", 2)[0].trim();
        return isPlausibleAddress(candidate) ? candidate : request.getRemoteAddr();
    }

    /**
     * Değer bir hız sınırı kimliğine, oradan da <b>Redis anahtarına ve log satırına</b> girer.
     * Doğrulanmasaydı satır sonu içeren bir başlık log'a sahte satır yazdırabilir
     * ({@code CorrelationIdFilter} ile aynı gerekçe), uzun/keyfi bir değer de anahtar alanını
     * şişirebilirdi. IPv4/IPv6 yazımı için gereken karakterler bunlardır, fazlası değil.
     */
    private static boolean isPlausibleAddress(String value) {
        if (value.isEmpty() || value.length() > MAX_ADDRESS_LENGTH) {
            return false;
        }
        return value.chars().allMatch(ch ->
                (ch >= '0' && ch <= '9')
                        || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')
                        || ch == '.' || ch == ':');
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
