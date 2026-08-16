package com.ohbsy.currencyapi.core.integrations.tcmb;

import com.ohbsy.currencyapi.core.integrations.tcmb.dtos.TcmbCurrencyRow;
import com.ohbsy.currencyapi.core.integrations.tcmb.dtos.TcmbRatesDocument;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;

/**
 * <b>DTO → domain.</b> Zincirin ikinci adımı: TCMB'nin söylediğini <i>bizim anladığımız şeye</i>
 * çevirir. Satıcıya özgü her tuhaflık burada biter; bu sınıftan sonra sistemin geri kalanı
 * TCMB diye bir şey olduğunu bilmez.
 *
 * <h2>Yaptığı üç dönüşüm</h2>
 * <ol>
 *   <li><b>Yön çevirme:</b> {@code 1 TL = Unit / ForexSelling}. TCMB "1 USD = 47,73 TL" der,
 *       sözleşmemiz "1 TL = 0,0209 USD"dir. <b>Bu hata sessizdir</b> — 47,73 de geçerli bir
 *       pozitif kurdur ve hiçbir doğrulamaya takılmaz, yalnız tutarları 2000 kat şişirir.
 *       Tek koruma bu dönüşümün testidir.</li>
 *   <li><b>Birim düzeltmesi:</b> {@code Unit} 100 ise (JPY) kur 100 kat yanlış olurdu.</li>
 *   <li><b>Tarih ayrıştırma:</b> {@code dd.MM.yyyy} → {@link LocalDate}.</li>
 * </ol>
 *
 * <p><b>Tanımadığımız kodlar sessizce atlanır</b> (TCMB 20+ yayınlar, bizim kümemiz dar);
 * ama <b>tanıdığımız bir kod bozuk gelirse</b> satır atlanmaz, belge reddedilir — "yarısı
 * doğru" tablo, yanlış tutar göstermenin en sessiz yoludur.
 */
@Component
public class TcmbRateMapper {

    private static final DateTimeFormatter TCMB_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** TCMB günü Türkiye takvimiyle yayınlar. */
    private static final ZoneId TCMB_ZONE = ZoneId.of("Europe/Istanbul");

    private final Clock clock;

    public TcmbRateMapper(Clock clock) {
        this.clock = clock;
    }

    /**
     * @throws IllegalArgumentException tanıdığımız bir kod bozuksa ya da tabloda hiç
     *         kullanılabilir kur kalmazsa (kanonik kurucu reddeder)
     */
    public ExchangeRateSnapshot toSnapshot(TcmbRatesDocument document) {
        Map<CurrencyCode, BigDecimal> rates = new EnumMap<>(CurrencyCode.class);

        for (TcmbCurrencyRow row : document.rows()) {
            CurrencyCode code = CurrencyCode.fromCode(row.code()).orElse(null);
            if (code == null || code == CurrencyCode.TRY) {
                continue; // tanimadigimiz kod ya da bazin kendisi
            }
            rates.put(code, inverseRate(row, code));
        }

        return new ExchangeRateSnapshot(CurrencyCode.TRY, rates, rateDate(document),
                clock.instant(), TcmbExchangeRateProvider.NAME);
    }

    /** {@code 1 TL = Unit / ForexSelling} — yön çevirme ve birim düzeltmesi tek satırda. */
    private BigDecimal inverseRate(TcmbCurrencyRow row, CurrencyCode code) {
        BigDecimal perUnit = required(row.forexSelling(), "ForexSelling", code);
        BigDecimal unit = optional(row.unit());
        if (perUnit.signum() <= 0 || unit.signum() <= 0) {
            throw new IllegalArgumentException(
                    "TCMB kuru kullanilamaz: " + code + " rate=" + perUnit + " unit=" + unit);
        }
        return unit.divide(perUnit, ExchangeRateSnapshot.RATE_SCALE,
                ExchangeRateSnapshot.RATE_ROUNDING);
    }

    private BigDecimal required(String raw, String field, CurrencyCode code) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("TCMB belgesinde " + field + " yok: " + code);
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "TCMB " + field + " sayiya cevrilemedi (" + code + "): " + raw, e);
        }
    }

    /** {@code Unit} yoksa ya da okunamazsa 1 varsayılır — eksikliği belgeyi düşürmez. */
    private BigDecimal optional(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ONE;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ONE;
        }
    }

    /**
     * Belgedeki gün. Okunamazsa <b>bugüne</b> düşülür: tarih bir bilgi alanıdır (cevapta
     * "kur şu güne ait" der), kurun kendisi değil — biçimi değişti diye geçerli kurları çöpe
     * atmak orantısız olurdu.
     */
    private LocalDate rateDate(TcmbRatesDocument document) {
        if (document.date() != null) {
            try {
                return LocalDate.parse(document.date(), TCMB_DATE);
            } catch (Exception ignored) {
                // asagidaki yedege dusulur
            }
        }
        return LocalDate.now(clock.withZone(TCMB_ZONE));
    }
}
