package com.ohbsy.currencyapi.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.time.Instant;

/**
 * Dinamik (admin tarafından oluşturulmuş) bir API anahtarının <b>domain modeli</b>.
 *
 * <h2>Ham anahtar burada YOKTUR</h2>
 * {@code keyHash} yalnız {@link com.ohbsy.currencyapi.core.utilities.ApiKeyHasher} çıktısıdır;
 * ham değer oluşturma anından sonra hiçbir yerde tutulmaz — {@code ApiKeyStore} ham anahtarı
 * hiç görmez, hiç saklamaz. {@code keyPreview} de aynı gerekçeyle yalnız maskelenmiş bir
 * gösterim değeridir, anahtarı yeniden üretmek için kullanılamaz.
 *
 * <h2>{@code id}, {@code keyHash} DEĞİLDİR</h2>
 * Admin işlemleri (iptal etme, listeleme) bu kaydı {@code id} (rastgele bir UUID) ile
 * adresler. Hash'i bir handle olarak kullanmak, hash'in kendisini URL'lerde/loglarda dolaşan
 * ikinci bir sır hâline getirirdi.
 *
 * <p>Kayıt immutable'dır: iptal etmek ya da {@code lastUsedAt} güncellemek yeni bir kopya
 * üretip {@code ApiKeyStore.save()} ile yazmak demektir — kod tabanındaki diğer domain
 * kayıtlarıyla (ör. {@link ExchangeRateSnapshot}) aynı desen.
 *
 * @param id                 admin-görünür kalıcı kimlik (UUID)
 * @param consumerName       log/metrik kimliği ve hız sınırı kovası — statik anahtarlardaki
 *                           {@code Auth.keys} değeriyle aynı rolü oynar
 * @param keyHash            ham anahtarın SHA-256 hex özeti — istek-anı eşleşme burada olur
 * @param keyPreview         maskelenmiş gösterim (ör. {@code cur_ab12…wxyz}), yalnız oluşturma
 *                           anında yakalanır
 * @param createdAt          oluşturma zamanı
 * @param revokedAt          iptal zamanı; {@code null} ise anahtar aktiftir
 * @param rateLimitOverride  bu anahtara özel pencere-başı istek sınırı; {@code null} ise
 *                           global {@code currency-api.rate-limit.limit} geçerlidir
 * @param lastUsedAt         son başarılı kullanım zamanı; yalnız bilgilendirme amaçlıdır, hiçbir
 *                           yetkilendirme kararı buna dayanmaz
 */
public record ApiKeyRecord(
        String id,
        String consumerName,
        String keyHash,
        String keyPreview,
        Instant createdAt,
        Instant revokedAt,
        Integer rateLimitOverride,
        Instant lastUsedAt
) implements Serializable {

    public ApiKeyRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id zorunludur");
        }
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName zorunludur");
        }
        if (keyHash == null || keyHash.isBlank()) {
            throw new IllegalArgumentException("keyHash zorunludur");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt zorunludur");
        }
    }

    // JsonIgnore sart: "is" onekli, parametresiz bu metod Jackson tarafindan JavaBean getter'i
    // sanilip serilestirmeye "active" adinda FAZLADAN bir alan olarak eklenirdi — deserialize
    // ederken bu alan constructor'da karsiligi olmadigi icin UnrecognizedPropertyException
    // firlatirdi (Redis'e yazilan her kayit boyle "bozuk" okunurdu).
    @JsonIgnore
    public boolean isActive() {
        return revokedAt == null;
    }

    /** Yeni bir kopya: iptal edilmiş hâli. Diğer alanlar değişmez. */
    public ApiKeyRecord revoked(Instant at) {
        return new ApiKeyRecord(id, consumerName, keyHash, keyPreview, createdAt, at,
                rateLimitOverride, lastUsedAt);
    }

    /** Yeni bir kopya: son kullanım zamanı güncellenmiş hâli. */
    public ApiKeyRecord withLastUsedAt(Instant at) {
        return new ApiKeyRecord(id, consumerName, keyHash, keyPreview, createdAt, revokedAt,
                rateLimitOverride, at);
    }
}
