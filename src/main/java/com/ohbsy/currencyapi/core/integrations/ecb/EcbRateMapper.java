package com.ohbsy.currencyapi.core.integrations.ecb;

import com.ohbsy.currencyapi.core.integrations.ecb.dtos.EcbDailyRates;
import com.ohbsy.currencyapi.core.integrations.ecb.dtos.EcbRatesDocument;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * <b>DTO → domain.</b> ECB'nin söylediğini bizim anladığımız şeye çevirir. Bu sınıftan sonra
 * sistemin geri kalanı ECB diye bir şey olduğunu bilmez.
 *
 * <h2>ECB'nin TCMB'den üç yapısal farkı</h2>
 * <ol>
 *   <li><b>Tabanı EUR'dur, TRY değil.</b> Belge "1 EUR = X birim" der. Bize lazım olan
 *       "1 TRY = ? birim"dir, yani <b>çapraz kur</b> hesaplanır (aşağıya bkz.).</li>
 *   <li><b>Birim çarpanı YOKTUR ve OLMAMALIDIR.</b> {@code today.xml} JPY'yi 100 birim
 *       üzerinden yayınlar ({@code <Unit>100</Unit>}), EVDS de aynı geleneği sessizce
 *       sürdürür ({@code EvdsRateMapper.UNIT}). <b>ECB sürdürmez:</b> {@code JPY 171.85}
 *       satırı "1 EUR = 171,85 JPY" demektir, tam olarak 1 birim üzerinden. Buraya EVDS'ten
 *       kopyalanmış bir çarpan eklemek kuru <b>100 kat</b> bozar ve sonuç yine geçerli bir
 *       pozitif sayı olduğu için hiçbir doğrulamaya takılmaz. Bu sınıfta bilinçli olarak
 *       çarpan tablosu YOKTUR; testi bunu JPY üzerinden sabitler.</li>
 *   <li><b>Kur farklı bir ŞEYDİR.</b> ECB'nin yayınladığı <i>referans kuru</i>, TCMB'nin
 *       <i>resmî satış kuru</i> ile aynı sayı değildir. Bu yüzden kayıt kendi kaynağını
 *       taşır ({@code ExchangeRateSnapshot.source}) ve cevapta {@code provider: "ecb"} yazar —
 *       ECB'nin sayısını TCMB adıyla sunmak sessiz bir yanlışlık olurdu.</li>
 * </ol>
 *
 * <h2>Çapraz kur: iki bölme, tek formül</h2>
 * <pre>
 *   1 EUR = eurX  birim X          (belgeden)
 *   1 EUR = eurTRY birim TRY       (belgeden — TRY satırı)
 *   ⇒ eurTRY TRY = eurX X
 *   ⇒ 1 TRY = (eurX / eurTRY) X    ← domain sözleşmemiz
 * </pre>
 * EUR'un kendisi belgede satır olarak <b>gelmez</b> (baz kendisidir); formülde {@code eurEUR=1}
 * alınır, yani {@code 1 TRY = 1/eurTRY EUR}.
 *
 * <p><b>TRY satırı zorunludur ve yoksa GÜRÜLTÜLÜ patlar.</b> ECB para birimi listesini zaman
 * zaman değiştirir; TRY listeden çıkarsa tek bir kur bile hesaplanamaz. Sessizce boş tablo
 * dönmek, servisin "ECB yedeği var" iddiasını doğru ama işlevsiz kılardı.
 *
 * <h2>Yalnız BUGÜNÜN belgesi kabul edilir</h2>
 * ECB'nin günlük dosyası hafta sonu <b>kaybolmaz</b>, cuma gününü göstermeye devam eder. Bu
 * kural olmasaydı her cumartesi zincir TCMB'nin cuma kurundan ECB'nin cuma kuruna <b>atlar</b>
 * ve tüketicinin gördüğü rakam, hiçbir şey bozulmadığı hâlde kurum değiştirdiği için oynardı.
 * Kural şudur: <b>ECB yalnız bugünün kuruna sahipse konuşur</b>; aksi hâlde
 * {@link StaleDocumentException} ile susar ve servis kendi son geçerli kurunu sunar (o kur da
 * TCMB'nindir, yani kurum değişmez).
 */
@Component
public class EcbRateMapper {

    /** ECB tarihleri ISO yazar: {@code 2026-08-14}. */
    private static final DateTimeFormatter ECB_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Gün karşılaştırması TCMB ile <b>aynı takvimde</b> yapılır. ECB'yi kendi saat diliminde
     * değerlendirmek, "bugün" kelimesinin servisin iki yerinde iki anlama gelmesi demekti.
     */
    private static final ZoneId TCMB_ZONE = ZoneId.of("Europe/Istanbul");

    /** ECB'nin baz para birimi — belgede satır olarak GELMEZ, burada üretilir. */
    private static final CurrencyCode ECB_BASE = CurrencyCode.EUR;

    private final Clock clock;

    public EcbRateMapper(Clock clock) {
        this.clock = clock;
    }

    /**
     * Belge var ama bugüne ait değil — hafta sonu, tatil ya da "ECB henüz yayınlamadı"
     * (yayın ~16:00 CET). {@link IllegalArgumentException}'dan ayrı bir tip olması, çağıranın
     * bunu bozuk yükle karıştırmadan {@code NOT_PUBLISHED} olarak işaretlemesini sağlar;
     * ayrımı hata <i>metnine</i> bakarak yapmak mesaj düzenlendiğinde sessizce bozulurdu.
     */
    public static class StaleDocumentException extends IllegalArgumentException {
        StaleDocumentException(String message) {
            super(message);
        }
    }

    /**
     * @throws StaleDocumentException    kullanılabilir gün yoksa ya da en yeni gün bugün değilse
     * @throws IllegalArgumentException  TRY satırı yoksa veya tanıdığımız bir kur bozuksa
     */
    public ExchangeRateSnapshot toSnapshot(EcbRatesDocument document) {
        Dated newest = newestUsableDay(document);

        LocalDate today = LocalDate.now(clock.withZone(TCMB_ZONE));
        if (!newest.date().equals(today)) {
            throw new StaleDocumentException(
                    "ECB belgesi bugune ait degil: belge=" + newest.date() + " bugun=" + today);
        }

        BigDecimal euroToTry = euroToTry(newest.day());

        Map<CurrencyCode, BigDecimal> rates = new EnumMap<>(CurrencyCode.class);
        newest.day().rates().forEach((rawCode, rawRate) -> {
            CurrencyCode code = CurrencyCode.fromCode(rawCode).orElse(null);
            if (code == null || code == CurrencyCode.TRY) {
                return; // tanimadigimiz kod ya da bolenin kendisi
            }
            rates.put(code, crossRate(positive(rawRate, code), euroToTry));
        });
        // Baz satır olarak gelmez: 1 EUR = 1 EUR.
        rates.put(ECB_BASE, crossRate(BigDecimal.ONE, euroToTry));

        return new ExchangeRateSnapshot(CurrencyCode.TRY, rates, newest.date(), clock.instant(),
                EcbExchangeRateProvider.NAME);
    }

    /**
     * En yeni dolu gün. Sıralama ECB'nin verdiği sıraya <b>güvenmez</b> ({@code EvdsRateMapper}
     * ile aynı gerekçe): 90 günlük dosyada sıranın tersine dönmesi, "en yeni" yerine "en eski"
     * kuru sunmak demek olurdu — yine sessizce.
     */
    private Dated newestUsableDay(EcbRatesDocument document) {
        List<Dated> candidates = new ArrayList<>();
        for (EcbDailyRates day : document.days()) {
            LocalDate date = parseDate(day.date());
            if (date == null || day.hasNoValues()) {
                continue; // tarihsiz ya da içi boş gün sıralanamaz/kullanılamaz
            }
            candidates.add(new Dated(date, day));
        }
        return candidates.stream()
                .max(Comparator.comparing(Dated::date))
                .orElseThrow(() -> new StaleDocumentException(
                        "ECB belgesinde kullanilabilir gun yok (gun=" + document.days().size()
                                + ")"));
    }

    /** {@code 1 TRY = perEuro / euroToTry} — yön çevirme ve çapraz kur tek satırda. */
    private BigDecimal crossRate(BigDecimal perEuro, BigDecimal euroToTry) {
        return perEuro.divide(euroToTry, ExchangeRateSnapshot.RATE_SCALE,
                ExchangeRateSnapshot.RATE_ROUNDING);
    }

    /** Bölen: 1 EUR kaç TRY. Yoksa tek bir kur bile hesaplanamaz — belge reddedilir. */
    private BigDecimal euroToTry(EcbDailyRates day) {
        String raw = day.valueOf(CurrencyCode.TRY.name());
        if (raw == null) {
            throw new IllegalArgumentException(
                    "ECB belgesinde TRY satiri yok — capraz kur hesaplanamaz (tarih="
                            + day.date() + ")");
        }
        return positive(raw, CurrencyCode.TRY);
    }

    /**
     * Tanıdığımız bir kod bozuk gelirse satır atlanmaz, <b>belge reddedilir</b>: "yarısı doğru"
     * bir kur tablosu, yanlış tutar göstermenin en sessiz yoludur ({@code TcmbRateMapper} ile
     * aynı ilke). Tanımadığımız kodlar ise zaten çağıran tarafta elenir.
     */
    private BigDecimal positive(String raw, CurrencyCode code) {
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "ECB kuru sayiya cevrilemedi (" + code + "): " + raw, e);
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "ECB kuru kullanilamaz: " + code + " rate=" + value);
        }
        return value;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), ECB_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ayrıştırılmış tarihiyle eşlenmiş gün — sıralama iki kez ayrıştırma yapmasın diye. */
    private record Dated(LocalDate date, EcbDailyRates day) {
    }
}
