package com.ohbsy.currencyapi.business.concretes;

import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;

/**
 * İş katmanının çıktısı: <b>kur + o kurun nasıl elde edildiği</b>.
 *
 * <p>Durumu cevaba taşımak bilinçli bir sözleşme kararıdır. Tüketici "bu kur taze mi, yoksa
 * satıcı düştüğü için son bilinen değer mi" sorusunu <b>sormak zorundadır</b>: birincisinde
 * ekranda bir şey göstermeye gerek yoktur, ikincisinde kullanıcıya kurun tarihi söylenmelidir.
 * Bu bilgi verilmezse her tüketici bunu tahmin etmeye çalışır ve çoğu yanlış tahmin eder.
 *
 * @param snapshot kur tablosu — {@link Status#UNAVAILABLE} durumunda {@code null}
 * @param status   kurun kaynağı ve tazeliği
 * @param provider hangi sağlayıcı ({@code tcmb}, ileride {@code ecb})
 */
public record RateResult(ExchangeRateSnapshot snapshot, Status status, String provider) {

    public enum Status {
        /** Cache taze — satıcıya gidilmedi. */
        FRESH_CACHE,
        /** Satıcıdan yeni çekildi. */
        FRESH_PROVIDER,
        /**
         * Satıcı erişilemedi, <b>son geçerli kur</b> sunuldu. TCMB'nin hafta sonu/tatilde
         * yayın yapmaması da buraya düşer — beklenen ve normal bir durumdur.
         */
        STALE_CACHE,
        /** Ne satıcı ne cache: kur yok. */
        UNAVAILABLE
    }

    public static RateResult unavailable(String provider) {
        return new RateResult(null, Status.UNAVAILABLE, provider);
    }

    public boolean hasRates() {
        return snapshot != null;
    }

    /** Ekranda "kur güncel değil" uyarısı gerektiren durumlar. */
    public boolean isStale() {
        return status == Status.STALE_CACHE;
    }
}
