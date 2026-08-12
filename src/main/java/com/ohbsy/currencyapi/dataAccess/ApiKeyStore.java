package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.entities.ApiKeyRecord;

import java.util.List;
import java.util.Optional;

/**
 * Dinamik API anahtarlarının deposu — {@link RateCache}/{@link RateLimiter} ile aynı aile:
 * bir arayüz, {@code memory}/{@code redis} seçimi {@code currency-api.cache.type} ile.
 *
 * <h2>Fail-open DEĞİL, burada bilinçli bir sapma var</h2>
 * {@link RateCache} ve {@link RateLimiter} için Redis bir <b>hızlandırıcıdır</b>, arızası asıl
 * işlevi (kur sunma / kötüye kullanımı sınırlama) durdurmamalıdır — kod tabanının genel
 * felsefesi budur. {@code ApiKeyStore.findByHash()} için ise Redis <b>doğrulamanın kendisinin
 * kaynağıdır</b>: burada fail-open, "anahtarı doğrulayamıyorsam geçir" anlamına gelir ki bu
 * asıl işlevi (yetkilendirme) yok eder. Bu yüzden {@code findByHash} implementasyonları
 * FAIL-CLOSED'dır (bkz. {@link RedisApiKeyStore}) — aynı ilkenin ("altyapı arızası sistemin ne
 * yaptığını sessizce değiştirmesin") farklı bir uygulanışıdır, ilkeden sapma değil.
 *
 * <p>Statik {@code CURRENCY_API_KEYS} anahtarları bu riskten muaftır (bellek içi, Redis'e hiç
 * bağımlı değil) — Redis kesintisinde ayakta kalması gereken bir tüketiciye dinamik değil
 * statik anahtar verilmelidir.
 */
public interface ApiKeyStore {

    /** Yeni kayıt oluşturur ya da mevcut bir kaydın güncel hâlini yazar (kayıt immutable). */
    void save(ApiKeyRecord record);

    /** İstek-anı yetkilendirme yolu — O(1), tarama yapmaz. */
    Optional<ApiKeyRecord> findByHash(String keyHash);

    /** Admin listelemesi — aktif ve iptal edilmiş kayıtların tümü. */
    List<ApiKeyRecord> findAll();

    /** Admin iptal işlemi için — kaydı {@code id} ile bulur. */
    Optional<ApiKeyRecord> findById(String id);

    String kind();
}
