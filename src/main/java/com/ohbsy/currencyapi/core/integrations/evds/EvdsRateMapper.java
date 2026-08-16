package com.ohbsy.currencyapi.core.integrations.evds;

import com.ohbsy.currencyapi.core.integrations.evds.dtos.EvdsObservation;
import com.ohbsy.currencyapi.core.integrations.evds.dtos.EvdsSeriesDocument;
import com.ohbsy.currencyapi.core.integrations.tcmb.TcmbExchangeRateProvider;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * <b>DTO → domain.</b> EVDS'in söylediğini bizim anladığımız şeye çevirir; bu sınıftan sonra
 * sistemin geri kalanı EVDS diye bir şey olduğunu bilmez.
 *
 * <h2>EVDS'in {@code today.xml}'den iki farkı</h2>
 * <ol>
 *   <li><b>Tarih doğrudur.</b> {@code today.xml}'in {@code Tarih} özniteliği bir gün geriden
 *       gelir: 14.08 sabahı yayınlanan belge "13.08.2026" der ama içindeki sayılar EVDS'in
 *       14-08-2026'ya yazdığı sayılardır (ölçülerek doğrulandı: USD 47.7717 her iki kaynakta
 *       da aynı). Sayılar aynı olduğu için bu tek başına bir kur hatası değildir — ama
 *       "elimizdeki kur bugünün mü" sorusu {@code today.xml} ile <b>cevaplanamaz</b>.</li>
 *   <li><b>Birim çarpanını GÖNDERMEZ.</b> {@code today.xml}'de {@code <Unit>100</Unit>} açıkça
 *       yazar; EVDS'te böyle bir alan yoktur, çarpan sessiz bir gelenektir. Bu yüzden
 *       {@link #UNIT} tablosu burada, elle tutulur.</li>
 * </ol>
 *
 * <h2>{@link #UNIT} yanlışsa hata 100 KATtır ve sessizdir</h2>
 * JPY'nin 30.0482 değeri "1 JPY = 30 TL" değil "<b>100</b> JPY = 30 TL" demektir. Çarpan
 * atlanırsa kur yine geçerli bir pozitif sayıdır, hiçbir doğrulamaya takılmaz ve yalnız
 * tutarları 100 kat şişirir. İki koruma vardır: tablo eksik bir para birimi için
 * {@link #unitOf} ile <b>gürültülü patlar</b> (sessizce 1 varsaymaz), ve testi tabloyu
 * {@code today.xml}'in {@code Unit} alanlarına karşı sabitler.
 *
 * <h2>Neden "en yeni dolu gün" seçiliyor</h2>
 * EVDS takvimi olduğu gibi verir: hafta sonu ve tatil günleri satır olarak <b>gelir</b>, ama
 * değerleri boştur. Tek gün sorulsaydı her cumartesi boş cevap alınırdı. Aralık isteyip en
 * yeni dolu günü seçmek, "yürürlükteki kur" sorusunun doğru cevabıdır.
 */
@Component
public class EvdsRateMapper {

    private static final Logger log = LoggerFactory.getLogger(EvdsRateMapper.class);

    /** EVDS tarihleri {@code dd-MM-yyyy} yazar (TCMB'nin {@code dd.MM.yyyy}'sinden farklı). */
    private static final java.time.format.DateTimeFormatter EVDS_DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * <b>Birim çarpanı</b> — EVDS bunu göndermez, bkz. sınıf açıklaması. Değerler
     * {@code today.xml}'in {@code <Unit>} alanlarıyla karşılaştırılarak doğrulandı.
     */
    private static final Map<CurrencyCode, BigDecimal> UNIT = new EnumMap<>(Map.of(
            CurrencyCode.USD, BigDecimal.ONE,
            CurrencyCode.EUR, BigDecimal.ONE,
            CurrencyCode.GBP, BigDecimal.ONE,
            CurrencyCode.CHF, BigDecimal.ONE,
            CurrencyCode.JPY, HUNDRED));

    private final Clock clock;

    public EvdsRateMapper(Clock clock) {
        this.clock = clock;
    }

    /** Bazın kendisi hariç, sorduğumuz her para birimi. */
    public static Stream<CurrencyCode> quotedCurrencies() {
        return Stream.of(CurrencyCode.values()).filter(code -> code != CurrencyCode.TRY);
    }

    /**
     * İSTEKteki seri adı: {@code TP.DK.USD.S}. Sondaki {@code S} <b>satış</b> kurudur —
     * {@code today.xml}'de kullandığımız {@code ForexSelling} ile aynı taraf. {@code A} (alış)
     * seçilseydi iki kaynak sessizce farklı sayılar üretirdi.
     */
    public static String seriesNameOf(CurrencyCode code) {
        return "TP.DK." + code.name() + ".S";
    }

    /** CEVAPtaki sütun adı: EVDS noktaları alt çizgiye çevirir ({@code TP_DK_USD_S}). */
    public static String columnNameOf(CurrencyCode code) {
        return seriesNameOf(code).replace('.', '_');
    }

    /**
     * Aralıkta yayınlanmış tek bir gün bile yok — biçimsel olarak kusursuz ama <b>boş</b> bir
     * cevap. {@link IllegalArgumentException}'dan ayrı bir tip olması, çağıranın bunu "bozuk
     * yük" ile karıştırmadan {@code NOT_PUBLISHED} olarak işaretlemesini sağlar; ayrımı hata
     * <i>metnine</i> bakarak yapmak, mesaj her düzenlendiğinde sessizce bozulurdu.
     */
    public static class NoPublishedDayException extends IllegalArgumentException {
        NoPublishedDayException(String message) {
            super(message);
        }
    }

    /**
     * @throws NoPublishedDayException aralıkta kullanılabilir tek bir gün bile yoksa
     * @throws IllegalArgumentException seçilen günün kuru okunamazsa
     */
    public ExchangeRateSnapshot toSnapshot(EvdsSeriesDocument document) {
        Dated newest = newestUsableDay(document);

        Map<CurrencyCode, BigDecimal> rates = new EnumMap<>(CurrencyCode.class);
        quotedCurrencies().forEach(code ->
                rates.put(code, inverseRate(newest.day().valueOf(columnNameOf(code)), code)));

        // Kaynak "evds" DEĞİL "tcmb": EVDS bir KAPI'dır, kurumu değil. Sayılar today.xml ile
        // birebir aynıdır (ölçüldü) — bu alan sayının kimin sayısı olduğunu söyler, hangi
        // kapıdan girildiğini değil. Hangi kapı olduğu log'dadır.
        return new ExchangeRateSnapshot(CurrencyCode.TRY, rates, newest.date(), clock.instant(),
                TcmbExchangeRateProvider.NAME);
    }

    /**
     * En yeni <b>tam</b> gün. Kısmen dolu gün de atlanır: "yarısı doğru" bir tablo, yanlış
     * tutar göstermenin en sessiz yoludur ({@code TcmbRateMapper} ile aynı ilke). Ama boş gün
     * takvimdir, kısmen dolu gün anormalliktir — ikincisi WARN ile ayrılır.
     *
     * <p>Sıralama EVDS'in verdiği sıraya <b>güvenmez</b>: cevap bugün artan tarihle geliyor,
     * ama bu sözleşmede yazmıyor ve tersine dönmesi "en yeni" yerine "en eski" kuru sunmak
     * demek olurdu — yine sessizce.
     */
    private Dated newestUsableDay(EvdsSeriesDocument document) {
        List<Dated> candidates = new ArrayList<>();
        for (EvdsObservation day : document.days()) {
            LocalDate date = parseDate(day.date());
            if (date == null) {
                continue; // tarihsiz satır sıralanamaz; hangi güne ait olduğu bilinmiyor
            }
            if (day.hasNoValues()) {
                continue; // hafta sonu/tatil — arıza değil takvim
            }
            if (!isComplete(day)) {
                log.warn("EVDS gunu eksik sutunlu, atlandi date={} sutunlar={}",
                        day.date(), day.values().keySet());
                continue;
            }
            candidates.add(new Dated(date, day));
        }

        return candidates.stream()
                .max(Comparator.comparing(Dated::date))
                .orElseThrow(() -> new NoPublishedDayException(
                        "EVDS araliginda kullanilabilir gun yok (satir=" + document.totalCount()
                                + ")"));
    }

    private boolean isComplete(EvdsObservation day) {
        return quotedCurrencies().allMatch(code -> day.valueOf(columnNameOf(code)) != null);
    }

    /** {@code 1 TL = Unit / satışKuru} — yön çevirme ve birim düzeltmesi tek satırda. */
    private BigDecimal inverseRate(String raw, CurrencyCode code) {
        BigDecimal perUnit;
        try {
            perUnit = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "EVDS kuru sayiya cevrilemedi (" + code + "): " + raw, e);
        }
        if (perUnit.signum() <= 0) {
            throw new IllegalArgumentException(
                    "EVDS kuru kullanilamaz: " + code + " rate=" + perUnit);
        }
        return unitOf(code).divide(perUnit, ExchangeRateSnapshot.RATE_SCALE,
                ExchangeRateSnapshot.RATE_ROUNDING);
    }

    /**
     * Tablo eksikse <b>1 VARSAYILMAZ</b>. Yeni bir para birimi eklenip çarpanı unutulduğunda
     * sessizce 100 kat yanlış kur sunmaktansa açılışın ilk çağrısında patlamak yeğdir —
     * zincir bu durumda {@code today.xml}'e düşer, yani hata servisi de düşürmez.
     */
    private BigDecimal unitOf(CurrencyCode code) {
        BigDecimal unit = UNIT.get(code);
        if (unit == null) {
            throw new IllegalStateException(
                    "EVDS birim carpani tanimsiz: " + code + " (EvdsRateMapper.UNIT)");
        }
        return unit;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), EVDS_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ayrıştırılmış tarihiyle eşlenmiş gün — sıralama iki kez ayrıştırma yapmasın diye. */
    private record Dated(LocalDate date, EvdsObservation day) {
    }
}
