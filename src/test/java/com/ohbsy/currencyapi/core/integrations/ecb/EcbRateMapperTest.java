package com.ohbsy.currencyapi.core.integrations.ecb;

import com.ohbsy.currencyapi.core.integrations.ecb.dtos.EcbDailyRates;
import com.ohbsy.currencyapi.core.integrations.ecb.dtos.EcbRatesDocument;
import com.ohbsy.currencyapi.core.integrations.tcmb.TcmbRateMapper;
import com.ohbsy.currencyapi.core.integrations.tcmb.dtos.TcmbCurrencyRow;
import com.ohbsy.currencyapi.core.integrations.tcmb.dtos.TcmbRatesDocument;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ECB çevirisi — <b>çapraz kur, birim çarpanının YOKLUĞU ve "yalnız bugün" kuralı</b>.
 *
 * <p>Bu üçünün hatası da sessizdir: yanlış yön, kaçırılmış/uydurulmuş çarpan ve eski bir
 * belge, hepsi geçerli pozitif sayılar üretir. Sayılar bilinçli olarak <b>yuvarlaktır</b>
 * (1 EUR = 50 TRY, 1,25 USD, 200 JPY) — beklenen sonucu okurken hesap makinesi gerekmemeli,
 * yoksa test kendi doğruluğunu kanıtlayamaz.
 */
@DisplayName("EcbRateMapper — EUR tabanlı belgeden TRY tabanlı domaine")
class EcbRateMapperTest {

    /** 14.08.2026, İstanbul takviminde aynı gün (UTC 09:00 → 12:00). */
    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EcbRateMapper mapper = new EcbRateMapper(clock);

    private static EcbRatesDocument document(LocalDate date, Map<String, String> rates) {
        return new EcbRatesDocument(List.of(new EcbDailyRates(date.toString(), rates)));
    }

    /** 1 EUR = 50 TRY · 1,25 USD · 200 JPY · 0,80 GBP. */
    private static Map<String, String> typicalDay() {
        Map<String, String> rates = new LinkedHashMap<>();
        rates.put("USD", "1.25");
        rates.put("JPY", "200");
        rates.put("GBP", "0.80");
        rates.put("TRY", "50");
        return rates;
    }

    @Nested
    @DisplayName("çapraz kur")
    class CrossRate {

        @Test
        @DisplayName("1 TRY = eurX / eurTRY — USD üzerinden")
        void convertsThroughEuro() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(TODAY, typicalDay()));

            // 1 EUR = 50 TRY ve 1 EUR = 1,25 USD  ⇒  1 TRY = 1,25/50 = 0,025 USD
            assertThat(snapshot.rateOf(CurrencyCode.USD))
                    .isEqualByComparingTo(new BigDecimal("0.025"));
            // ve ters yön: 1 USD = 40 TRY
            assertThat(snapshot.unitPriceOf(CurrencyCode.USD))
                    .isEqualByComparingTo(new BigDecimal("40"));
        }

        @Test
        @DisplayName("EUR satır olarak gelmez ama tabloda VARDIR")
        void producesBaseCurrencyItself() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(TODAY, typicalDay()));

            // 1 EUR = 50 TRY  ⇒  1 TRY = 0,02 EUR
            assertThat(snapshot.rateOf(CurrencyCode.EUR))
                    .isEqualByComparingTo(new BigDecimal("0.02"));
            assertThat(snapshot.unitPriceOf(CurrencyCode.EUR))
                    .isEqualByComparingTo(new BigDecimal("50"));
        }

        @Test
        @DisplayName("kaynak 'ecb' olarak damgalanır — TCMB adıyla sunulmaz")
        void stampsItsOwnSource() {
            assertThat(mapper.toSnapshot(document(TODAY, typicalDay())).source())
                    .isEqualTo(EcbExchangeRateProvider.NAME);
        }

        @Test
        @DisplayName("belgenin günü rateDate olur")
        void carriesPublicationDate() {
            assertThat(mapper.toSnapshot(document(TODAY, typicalDay())).rateDate())
                    .isEqualTo(TODAY);
        }
    }

    @Nested
    @DisplayName("birim çarpanı — ECB'de YOKTUR")
    class UnitMultiplier {

        /**
         * Bu testin tek işi, {@code EvdsRateMapper.UNIT} tablosunun buraya kopyalanmasını
         * engellemektir: JPY'ye ×100 uygulansaydı sonuç 4 değil 400 (ya da 0,04) olurdu ve
         * <b>ikisi de geçerli pozitif sayılardır.</b>
         */
        @Test
        @DisplayName("JPY 1 birim üzerinden okunur, 100 DEĞİL")
        void doesNotApplyHundredMultiplierToJpy() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(TODAY, typicalDay()));

            // 1 EUR = 50 TRY ve 1 EUR = 200 JPY  ⇒  1 TRY = 4 JPY
            assertThat(snapshot.rateOf(CurrencyCode.JPY))
                    .isEqualByComparingTo(new BigDecimal("4"));
            // 1 JPY = 0,25 TRY — ×100 uygulansaydı 25 ya da 0,0025 çıkardı
            assertThat(snapshot.unitPriceOf(CurrencyCode.JPY))
                    .isEqualByComparingTo(new BigDecimal("0.25"));
        }

        /**
         * İki mapper'ın <b>aynı gerçeği</b> aynı sayıya çevirdiğini sabitler: TCMB "100 JPY =
         * 25 TL" der (Unit=100), ECB aynı gerçeği "1 EUR = 200 JPY, 1 EUR = 50 TRY" diye söyler.
         * Çarpan kurallarının ikisi de doğruysa sonuç aynı olmak ZORUNDADIR — biri
         * kopyalanır ya da unutulursa bu test düşer.
         */
        @Test
        @DisplayName("aynı gerçek için TCMB mapper'ıyla AYNI kuru üretir (USD ve JPY)")
        void agreesWithTcmbMapperOnEquivalentInput() {
            ExchangeRateSnapshot fromEcb = mapper.toSnapshot(document(TODAY, typicalDay()));

            // Aynı gerçegin TCMB dilindeki hâli: 1 USD = 40 TL, 100 JPY = 25 TL
            ExchangeRateSnapshot fromTcmb = new TcmbRateMapper(clock).toSnapshot(
                    new TcmbRatesDocument("14.08.2026", List.of(
                            new TcmbCurrencyRow("USD", "1", "39", "40"),
                            new TcmbCurrencyRow("JPY", "100", "24", "25"))));

            assertThat(fromEcb.rateOf(CurrencyCode.USD))
                    .isEqualByComparingTo(fromTcmb.rateOf(CurrencyCode.USD));
            assertThat(fromEcb.rateOf(CurrencyCode.JPY))
                    .isEqualByComparingTo(fromTcmb.rateOf(CurrencyCode.JPY));
        }
    }

    @Nested
    @DisplayName("yalnız BUGÜNÜN belgesi kabul edilir")
    class OnlyToday {

        /**
         * ECB'nin günlük dosyası hafta sonu kaybolmaz, cumayı göstermeye devam eder. Bu kural
         * olmasaydı her cumartesi sunulan kur TCMB'den ECB'ye atlar ve hiçbir şey bozulmadığı
         * hâlde tüketicinin gördüğü rakam kurum değiştirirdi.
         */
        @Test
        @DisplayName("dünkü belge reddedilir — hafta sonu kurum değiştirmemek için")
        void refusesYesterdaysDocument() {
            assertThatThrownBy(() ->
                    mapper.toSnapshot(document(TODAY.minusDays(1), typicalDay())))
                    .isInstanceOf(EcbRateMapper.StaleDocumentException.class)
                    .hasMessageContaining("bugune ait degil");
        }

        @Test
        @DisplayName("hiç kullanılabilir gün yoksa da aynı sebep döner")
        void refusesEmptyDocument() {
            assertThatThrownBy(() -> mapper.toSnapshot(new EcbRatesDocument(List.of())))
                    .isInstanceOf(EcbRateMapper.StaleDocumentException.class);
        }

        /** Satıcının sırasına güvenilmez: en yeni gün seçilir, ilk gün değil. */
        @Test
        @DisplayName("çok günlü belgede EN YENİ gün seçilir, sıraya bakılmaz")
        void picksNewestDayRegardlessOfOrder() {
            EcbRatesDocument document = new EcbRatesDocument(List.of(
                    new EcbDailyRates(TODAY.minusDays(1).toString(), Map.of("TRY", "40")),
                    new EcbDailyRates(TODAY.toString(), typicalDay())));

            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document);

            assertThat(snapshot.rateDate()).isEqualTo(TODAY);
            assertThat(snapshot.unitPriceOf(CurrencyCode.EUR))
                    .isEqualByComparingTo(new BigDecimal("50"));
        }
    }

    @Nested
    @DisplayName("kullanılamaz yük")
    class Invalid {

        /** TRY bölendir: yoksa tek bir kur bile hesaplanamaz — sessizce boş tablo dönülmez. */
        @Test
        @DisplayName("TRY satırı yoksa gürültülü patlar")
        void requiresTryRow() {
            assertThatThrownBy(() -> mapper.toSnapshot(
                    document(TODAY, Map.of("USD", "1.25", "JPY", "200"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRY satiri yok");
        }

        @Test
        @DisplayName("tanıdığımız bir kur bozuksa belge reddedilir")
        void rejectsMalformedKnownRate() {
            Map<String, String> rates = new LinkedHashMap<>(typicalDay());
            rates.put("USD", "bakim");

            assertThatThrownBy(() -> mapper.toSnapshot(document(TODAY, rates)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("USD");
        }

        @Test
        @DisplayName("negatif/sıfır kur reddedilir")
        void rejectsNonPositiveRate() {
            Map<String, String> rates = new LinkedHashMap<>(typicalDay());
            rates.put("TRY", "0");

            assertThatThrownBy(() -> mapper.toSnapshot(document(TODAY, rates)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** ECB 30+ kod yayınlar; bizim kümemiz dar ve tanımadıklarımız arıza değildir. */
        @Test
        @DisplayName("tanımadığımız kodlar sessizce atlanır")
        void skipsUnknownCurrencies() {
            Map<String, String> rates = new LinkedHashMap<>(typicalDay());
            rates.put("ISK", "142.30");
            rates.put("PHP", "65.11");

            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(TODAY, rates));

            assertThat(snapshot.availableCurrencies()).containsExactlyInAnyOrder(
                    CurrencyCode.TRY, CurrencyCode.USD, CurrencyCode.EUR,
                    CurrencyCode.GBP, CurrencyCode.JPY);
        }
    }
}
