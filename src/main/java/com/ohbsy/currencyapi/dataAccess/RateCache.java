package com.ohbsy.currencyapi.dataAccess;

import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;

import java.util.Optional;

/**
 * Kur cache'i. <b>İki pencere, tek girdi</b> — bu ayrım servisin tüm davranışını belirler:
 *
 * <ul>
 *   <li><b>{@code cache-ttl} (15 dk)</b> — <i>tazelik</i> penceresi. İçindeyken satıcıya
 *       HİÇ gidilmez.</li>
 *   <li><b>{@code retention}</b> — <i>saklama</i> süresi. Tazelik dolsa da kayıt SİLİNMEZ;
 *       satıcı erişilemezse "son geçerli kur" olarak sunulur.</li>
 * </ul>
 *
 * Kayıt tazelik dolunca silinseydi, TCMB'nin hafta sonu yayın yapmaması sistemi kursuz
 * bırakırdı — oysa doğru davranış cuma günkü kuru sunmaktır.
 *
 * <h2>Fail-open</h2>
 * Cache bir <b>hızlandırıcıdır, kaynak değildir</b>: erişilemezliği istisna üretmez, yalnız
 * cache-miss sayılır ve satıcıya gidilir. Redis çöktüğünde servis yavaşlar ama durmaz.
 * Bozuk kayıt da miss sayılır — bozukluk kendini beslemesin diye geri yazılmaz.
 */
public interface RateCache {

    /**
     * Saklanan son kayıt (taze ya da bayat — <b>ayrımı çağıran yapar</b>, çünkü tazelik
     * kaydın özelliği değil okuma anındaki yargıdır).
     */
    Optional<ExchangeRateSnapshot> find(String providerName);

    /** Taze çekimi yazar. Hata yükseltmez (fail-open). */
    void put(String providerName, ExchangeRateSnapshot snapshot);

    /** Tanılama/log alanı: hangi uygulama devrede ({@code redis} / {@code memory}). */
    String kind();
}
