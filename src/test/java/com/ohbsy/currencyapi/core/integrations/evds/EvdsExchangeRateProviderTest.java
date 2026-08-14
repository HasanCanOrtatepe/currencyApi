package com.ohbsy.currencyapi.core.integrations.evds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.integrations.ProviderUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EVDS sağlayıcısının <b>ağa çıkmayan</b> kararları: sorgunun nasıl kurulduğu, anahtarın nereye
 * konduğu ve anahtarsız kurulumda ne olduğu.
 *
 * <p>HTTP'nin kendisi burada sınanmaz (altyapısız test kuralı); sınanan şey EVDS değil
 * <b>bizim kararlarımızdır</b>.
 */
@DisplayName("EvdsExchangeRateProvider — sorgu ve yapılandırma")
class EvdsExchangeRateProviderTest {

    /** 14 Ağustos 2026, TSİ 10:21 (07:21 UTC) — TCMB günü Türkiye takvimiyle yayınlar. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-14T07:21:41Z"), ZoneOffset.UTC);

    private EvdsExchangeRateProvider providerWith(String key) {
        var properties = new CurrencyApiProperties();
        properties.getEvds().setKey(key);
        return new EvdsExchangeRateProvider(
                properties,
                new EvdsJsonReader(new ObjectMapper()),
                new EvdsRateMapper(CLOCK),
                CLOCK);
    }

    /**
     * Ölçülmüş gerçek biçim: sorgu {@code ?} ile DEĞİL doğrudan {@code series=} ile başlar ve
     * seriler tire ile ayrılır. Bu, EVDS'e özgü bir tuhaflıktır ve tahmin edilebilir değildir.
     */
    @Test
    @DisplayName("Sorgu EVDS'in beklediği biçimde kurulur")
    void buildsSeriesQuery() {
        String url = providerWith("test-anahtar").seriesUrl();

        assertThat(url).startsWith("https://evds3.tcmb.gov.tr/igmevdsms-dis/series=");
        assertThat(url).contains("TP.DK.USD.S-TP.DK.EUR.S");
        assertThat(url).endsWith("&type=json");
    }

    /**
     * Tek gün sorulsaydı her cumartesi, her tatilde ve bültenin yayınlanmadığı sabah
     * saatlerinde cevap boş dönerdi.
     */
    @Test
    @DisplayName("Aralık sorulur: bugün ve geriye lookback kadar gün")
    void asksForARangeEndingToday() {
        String url = providerWith("test-anahtar").seriesUrl();

        assertThat(url).contains("&startDate=04-08-2026")   // 14 - 10 gün
                .contains("&endDate=14-08-2026");
    }

    /**
     * <b>URL'ler log'a, hata mesajına ve stack trace'e girer.</b> EVDS anahtarı sorgu
     * dizesinde kabul edilmediği için ({@code 403}) zaten başlığa konmak zorundadır — ama
     * bunun sırrı URL'lerden uzak tutması asıl kazançtır ve regresyona karşı sabitlenmelidir.
     */
    @Test
    @DisplayName("Anahtar URL'e HİÇ girmez — URL'ler serbestçe loglanabilsin")
    void neverPutsKeyInUrl() {
        assertThat(providerWith("cok-gizli-anahtar").seriesUrl())
                .doesNotContain("cok-gizli-anahtar")
                .doesNotContain("key=");
    }

    @Test
    @DisplayName("Anahtar yoksa sağlayıcı yapılandırılmamıştır — zincire girmez")
    void reportsMissingKey() {
        assertThat(providerWith("").isConfigured()).isFalse();
        assertThat(providerWith("   ").isConfigured()).isFalse();
        assertThat(providerWith("gercek-anahtar").isConfigured()).isTrue();
    }

    /**
     * {@code .env} doldurulmadığında servis, geçersiz bir yer tutucuyla her 15 dakikada bir
     * EVDS'ten 401 toplamamalıdır.
     */
    @Test
    @DisplayName("Yer tutucu değer anahtar sayılmaz")
    void ignoresPlaceholderKey() {
        assertThat(providerWith("__unset__").isConfigured()).isFalse();
    }

    /**
     * Zincir bu sağlayıcıyı anahtarsızken çağırmamalıdır; yine de çağrılırsa sessizce "veri
     * yok" demek yerine sebebi söyleyerek patlar — ve sebep {@code UNAUTHORIZED}'dır, yani
     * kendiliğinden düzelmeyecek bir yapılandırma hatası olarak işaretlenir.
     */
    @Test
    @DisplayName("Anahtarsız çağrı ağa ÇIKMADAN UNAUTHORIZED ile düşer")
    void failsClosedWithoutKey() {
        assertThatThrownBy(() -> providerWith("").fetchLatestRates())
                .isInstanceOf(ProviderUnavailableException.class)
                .extracting(e -> ((ProviderUnavailableException) e).getReason())
                .isEqualTo(ProviderUnavailableException.Reason.UNAUTHORIZED);
    }

    /** Konteyner tuzağı: {@code ${VAR:-}} boş dizgi gönderir ve varsayılanı ezmemelidir. */
    @Test
    @DisplayName("Boş base-url varsayılanı EZMEZ")
    void blankBaseUrlKeepsDefault() {
        var properties = new CurrencyApiProperties();
        properties.getEvds().setBaseUrl("");

        assertThat(properties.getEvds().getBaseUrl()).isEqualTo("https://evds3.tcmb.gov.tr");
    }

    @Test
    @DisplayName("Varsayılan pencere en uzun bayram zincirini kapsar")
    void defaultLookbackCoversLongHolidays() {
        assertThat(new CurrencyApiProperties().getEvds().getLookback())
                .isGreaterThanOrEqualTo(Duration.ofDays(9));
    }
}
