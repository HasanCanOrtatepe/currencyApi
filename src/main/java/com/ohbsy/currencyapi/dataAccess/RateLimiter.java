package com.ohbsy.currencyapi.dataAccess;

/**
 * İstek hızı sayacı.
 *
 * <h2>Fail-open — pazarlıksız</h2>
 * Sayaç deposu (Redis) erişilemezse istek <b>GEÇER</b>. Aksi hâlde sayacın kesintisi, kur
 * sunabilen bir servisi durdururdu: sınırın işi kötüye kullanımı sınırlamaktır, servisin
 * kendisini durdurmak değil. Aynı gerekçe cache'te ve sağlık göstergesinde de uygulandı —
 * yardımcı bir bileşenin arızası asıl işlevi düşürmemelidir.
 */
public interface RateLimiter {

    /**
     * Bir isteği sayar ve sonucu döner. Global varsayılan limiti kullanır.
     *
     * @param identity tüketici kimliği (anahtar adı ya da uzak adres)
     */
    default Decision tryConsume(String identity) {
        return tryConsume(identity, null);
    }

    /**
     * @param identity      tüketici kimliği (anahtar adı ya da uzak adres)
     * @param limitOverride {@code null} ise global {@code currency-api.rate-limit.limit}
     *                      kullanılır; değilse bu tüketiciye özel pencere-başı sınırdır
     *                      (dinamik anahtarların anahtar-başına limiti için — bkz. {@code
     *                      ApiKeyRecord.rateLimitOverride}).
     */
    Decision tryConsume(String identity, Integer limitOverride);

    /**
     * {@code tryConsume} ile AYNI kimliği okur ama sayacı ARTIRMAZ — admin panelinin "anlık
     * kalan hak" göstergesi için. Global varsayılan limiti kullanır.
     */
    default Decision peek(String identity) {
        return peek(identity, null);
    }

    /** {@link #peek(String)} ile aynı, {@code limitOverride} anlamı {@link #tryConsume} ile aynı. */
    Decision peek(String identity, Integer limitOverride);

    /**
     * @param allowed          istek geçsin mi
     * @param limit            pencere başına izin
     * @param remaining        kalan hak (negatif olmaz)
     * @param retryAfterSeconds reddedildiyse ne kadar sonra denenmeli
     */
    record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {

        /** Sınır kapalıyken ya da sayaç erişilemezken kullanılan "serbest" kararı. */
        static Decision unlimited(int limit) {
            return new Decision(true, limit, limit, 0);
        }
    }
}
