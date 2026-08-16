package com.ohbsy.currencyapi.entities;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * <b>Domain modeli</b> — bir kur tablosunun anlamı. Hiçbir satıcının tel formatı değildir:
 * TCMB'nin XML'i {@code core/integrations/tcmb} altındaki DTO'da kalır, buraya <b>eşlenerek</b>
 * girer. Satıcı değişirse bu sınıf DEĞİŞMEZ — sağlayıcı soyutlamasının anlamı budur.
 *
 * <h2>Kur yönü — tek ve pazarlıksız</h2>
 * {@code rates}, <b>1 baz birimin kaç hedef birim ettiğidir</b> ({@code 1 TRY = 0,0209 USD}).
 * TCMB bunun TERSİNİ yayınlar ({@code 1 USD = 47,73 TRY}); çevirme işi mapper'ındır ve burada
 * biten yön tektir. Yön hatası sessizdir — 47,73 de geçerli bir pozitif kurdur — bu yüzden
 * sözleşmenin tek yerde ve yazılı olması gerekir.
 *
 * <p><b>Baz tabloda YOKTUR</b> ({@link #rateOf} onu 1 olarak üretir): satıcının gönderdiği bir
 * baz satırına güvenilmez, {@code 0.999} göndermesi tutarları bozardı.
 *
 * <p>{@link Serializable}: kayıt Redis'e yazılır (cache katmanı JSON serileştirir; arayüz
 * yine de bilinçli olarak işaretlenmiştir).
 *
 * @param base       baz para birimi (TCMB için {@link CurrencyCode#TRY})
 * @param rates      baz dışı kurlar
 * @param rateDate   <b>satıcının yayın günü</b> — TCMB günlük yayınlar; hafta sonu/tatilde
 *                   yeni belge yoktur ve bu alan son iş gününde kalır
 * @param fetchedAt  <b>bizim çekme anımız</b> — tazelik bununla ölçülür, {@code rateDate} ile
 *                   değil. İkisini karıştırmak, cuma çekilen kurun cumartesi "bayat" sayılıp
 *                   satıcıya boşuna gitmesine yol açardı (TCMB o gün zaten yeni veri vermez).
 * @param source     <b>bu sayıları kim yayınladı</b> ({@code tcmb} | {@code evds} | {@code ecb}).
 *                   Kaydın kendisinde durur, sağlayıcı nesnesinde değil: kur cache'e yazılır ve
 *                   cache'ten okunduğunda "bunu kim söylemişti" sorusu hâlâ cevaplanabilmelidir.
 *                   <b>Bu alan bir etiket değil, sayının anlamının parçasıdır:</b> TCMB'nin
 *                   resmî satış kuru ile ECB'nin referans kuru aynı sayı DEĞİLDİR ve birini
 *                   diğerinin adıyla sunmak, bu servisin bütün kurallarının kapatmaya çalıştığı
 *                   "hata vermeden yanlış çalışma" biçiminin ta kendisi olurdu.
 */
public record ExchangeRateSnapshot(
        CurrencyCode base,
        Map<CurrencyCode, BigDecimal> rates,
        LocalDate rateDate,
        Instant fetchedAt,
        String source
) implements Serializable {

    /** Kur bölme sonucudur; ölçek verilmezse {@code BigDecimal.divide} istisna fırlatır. */
    public static final int RATE_SCALE = 10;
    public static final RoundingMode RATE_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Kanonik kurucu — <b>doğrulama tek yerde</b>. Geçersiz satır sessizce atlanmaz, tablo
     * reddedilir: "yarısı doğru" bir kur tablosu, tüketiciye yanlış tutar göstermenin en
     * sessiz yoludur.
     */
    public ExchangeRateSnapshot {
        if (base == null) {
            throw new IllegalArgumentException("Baz para birimi zorunludur");
        }
        if (rateDate == null || fetchedAt == null) {
            throw new IllegalArgumentException("rateDate ve fetchedAt zorunludur");
        }
        // Kaynaksız kayıt kabul EDİLMEZ ve varsayılana da düşülmez. Bu alan Redis'ten okunan
        // ESKİ kayıtlarda yoktur (alan sonradan eklendi): orada null gelir, bu satır patlar ve
        // RedisRateCache onu fail-open ile cache-miss sayar — yani en fazla bir kez satıcıya
        // gidilir. Alternatif "yoksa tcmb varsay" olurdu; o, kaydı gerçekten ECB yazmışsa
        // sessizce yanlış kurumu göstermek demekti.
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Kur kaydinin kaynagi (source) zorunludur");
        }
        source = source.trim();
        rates = sanitize(base, rates);
        if (rates.isEmpty()) {
            throw new IllegalArgumentException("Kur tablosu en az bir baz-dışı kur içermelidir");
        }
    }

    private static Map<CurrencyCode, BigDecimal> sanitize(CurrencyCode base,
                                                          Map<CurrencyCode, BigDecimal> rates) {
        if (rates == null) {
            return Map.of();
        }
        EnumMap<CurrencyCode, BigDecimal> cleaned = new EnumMap<>(CurrencyCode.class);
        rates.forEach((currency, rate) -> {
            if (currency == null) {
                throw new IllegalArgumentException("Kur tablosunda null para birimi var");
            }
            if (currency == base) {
                return; // baz kuru tanim geregi 1'dir; saglayicinin degeri yok sayilir
            }
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Gecersiz kur: " + currency + "=" + rate + " (pozitif olmali)");
            }
            cleaned.put(currency, rate);
        });
        return Map.copyOf(cleaned);
    }

    /** 1 baz birimin kaç {@code target} birim ettiği; baz için daima 1. */
    public BigDecimal rateOf(CurrencyCode target) {
        if (target == base) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = rates.get(target);
        if (rate == null) {
            throw new IllegalArgumentException("Desteklenmeyen para birimi: " + target);
        }
        return rate;
    }

    /** Kullanılabilir para birimleri — baz dahildir. */
    public Set<CurrencyCode> availableCurrencies() {
        EnumMap<CurrencyCode, BigDecimal> all = new EnumMap<>(CurrencyCode.class);
        all.putAll(rates);
        all.put(base, BigDecimal.ONE);
        return Set.copyOf(all.keySet());
    }

    /**
     * 1 {@code target} biriminin kaç baz birim ettiği ({@code 1 USD = 47,73 TRY}) — kullanıcıya
     * gösterilen "piyasa" yönü. Cevapta iki yön de sunulur çünkü tüketicilerin yarısı birini,
     * yarısı diğerini bekler ve dönüşümü her tüketicinin ayrı yapması hata kaynağıdır.
     */
    public BigDecimal unitPriceOf(CurrencyCode target) {
        BigDecimal rate = rateOf(target);
        return BigDecimal.ONE.divide(rate, RATE_SCALE, RATE_ROUNDING);
    }
}
