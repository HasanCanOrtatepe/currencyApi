package com.ohbsy.currencyapi.core.integrations.evds;

import com.ohbsy.currencyapi.core.integrations.evds.dtos.EvdsObservation;
import com.ohbsy.currencyapi.core.integrations.evds.dtos.EvdsSeriesDocument;
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
import static org.assertj.core.api.Assertions.within;

/**
 * EVDS satırlarının domaine çevrilmesi.
 *
 * <p>Buradaki iddiaların <b>tamamı sessiz hatalara karşıdır</b>: yanlış yön de, 100 kat yanlış
 * birim de, yanlış günün seçilmesi de geçerli birer pozitif sayı üretir ve hiçbir doğrulamaya
 * takılmaz. Bu dosya o üçünün tek koruyucusudur.
 */
@DisplayName("EvdsRateMapper — DTO → domain")
class EvdsRateMapperTest {

    private static final Instant NOW = Instant.parse("2026-08-14T07:21:41Z");

    private final EvdsRateMapper mapper = new EvdsRateMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    /** Ölçülmüş gerçek değerler — 14-08-2026 EVDS cevabı. */
    private static Map<String, String> realRow() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("TP_DK_USD_S", "47.77170000");
        values.put("TP_DK_EUR_S", "55.07440000");
        values.put("TP_DK_GBP_S", "64.53560000");
        values.put("TP_DK_CHF_S", "58.91290000");
        values.put("TP_DK_JPY_S", "30.04820000");
        return values;
    }

    private static EvdsObservation day(String date, Map<String, String> values) {
        return new EvdsObservation(date, values);
    }

    /** Yayın olmayan gün: satır gelir, değerler boştur. */
    private static EvdsObservation emptyDay(String date) {
        return new EvdsObservation(date, Map.of());
    }

    private static EvdsSeriesDocument document(EvdsObservation... days) {
        return new EvdsSeriesDocument(days.length, List.of(days));
    }

    @Nested
    @DisplayName("Birim çarpanı")
    class Units {

        /**
         * <b>Bu testin varlık sebebi:</b> EVDS {@code today.xml}'in {@code <Unit>} alanının
         * karşılığını GÖNDERMEZ. JPY'nin 30.0482 değeri "1 JPY = 30 TL" değil "100 JPY =
         * 30 TL" demektir; çarpan atlanırsa kur 100 kat şişer ve hiçbir yerde patlamaz.
         */
        @Test
        @DisplayName("JPY 100 birim üzerinden yayınlanır — 1 JPY ≈ 0,30 TL")
        void jpyIsQuotedPerHundred() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(
                    document(day("14-08-2026", realRow())));

            assertThat(snapshot.unitPriceOf(CurrencyCode.JPY).doubleValue())
                    .isCloseTo(0.300482, within(0.000001));
            assertThat(snapshot.rateOf(CurrencyCode.JPY).doubleValue())
                    .isCloseTo(3.3280, within(0.0001));   // 1 TL ≈ 3,33 JPY
        }

        @Test
        @DisplayName("Tek birimli kurlar bölünmez — 1 USD ≈ 47,77 TL")
        void singleUnitCurrenciesAreNotScaled() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(
                    document(day("14-08-2026", realRow())));

            assertThat(snapshot.unitPriceOf(CurrencyCode.USD).doubleValue())
                    .isCloseTo(47.7717, within(0.0001));
            assertThat(snapshot.unitPriceOf(CurrencyCode.EUR).doubleValue())
                    .isCloseTo(55.0744, within(0.0001));
        }

        /**
         * Yeni bir para birimi eklenip {@code UNIT} tablosuna yazılmazsa hata <b>burada</b>
         * çıkmalıdır, üretimde 100 kat yanlış bir kur olarak değil.
         */
        @Test
        @DisplayName("Sorduğumuz her para biriminin birim çarpanı TANIMLI")
        void everyQuotedCurrencyHasAUnit() {
            assertThat(EvdsRateMapper.quotedCurrencies())
                    .isNotEmpty()
                    .allSatisfy(code -> {
                        Map<String, String> row = new LinkedHashMap<>(realRow());
                        row.put(EvdsRateMapper.columnNameOf(code), "10.0");
                        assertThat(mapper.toSnapshot(document(day("14-08-2026", row)))
                                .rateOf(code)).isNotNull();
                    });
        }
    }

    @Nested
    @DisplayName("Gün seçimi")
    class DaySelection {

        /**
         * EVDS bugün artan tarih sırasıyla cevap veriyor, ama bu sözleşmede yazmıyor. Sıra
         * tersine dönerse "en yeni" yerine "en eski" kur sunulur — yine sessizce.
         */
        @Test
        @DisplayName("Cevabın sırasına GÜVENİLMEZ, en yeni gün tarihe göre seçilir")
        void picksNewestByDateNotByPosition() {
            Map<String, String> older = new LinkedHashMap<>(realRow());
            older.put("TP_DK_USD_S", "47.53520000");

            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(
                    day("14-08-2026", realRow()),      // en yeni ama BAŞTA
                    day("04-08-2026", older)));

            assertThat(snapshot.rateDate()).isEqualTo(LocalDate.of(2026, 8, 14));
            assertThat(snapshot.unitPriceOf(CurrencyCode.USD).doubleValue())
                    .isCloseTo(47.7717, within(0.0001));
        }

        /** Hafta sonu/tatil satırı gelir ama boştur — arıza değil takvim. */
        @Test
        @DisplayName("Boş günler atlanır, son yayınlanan gün sunulur")
        void skipsUnpublishedDays() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(
                    day("07-08-2026", realRow()),
                    emptyDay("08-08-2026"),            // cumartesi
                    emptyDay("09-08-2026")));          // pazar

            assertThat(snapshot.rateDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        }

        /** "Yarısı doğru" bir tablo, yanlış tutar göstermenin en sessiz yoludur. */
        @Test
        @DisplayName("Eksik sütunlu gün atlanır, tam olan bir önceki gün seçilir")
        void skipsPartiallyFilledDays() {
            Map<String, String> partial = new LinkedHashMap<>(realRow());
            partial.remove("TP_DK_JPY_S");

            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(
                    day("13-08-2026", realRow()),
                    day("14-08-2026", partial)));

            assertThat(snapshot.rateDate()).isEqualTo(LocalDate.of(2026, 8, 13));
            assertThat(snapshot.availableCurrencies()).contains(CurrencyCode.JPY);
        }

        @Test
        @DisplayName("Tarihi okunamayan satır sıralanamaz, atlanır")
        void skipsRowsWithUnreadableDate() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(
                    day("13-08-2026", realRow()),
                    day("2026/08/14", realRow())));

            assertThat(snapshot.rateDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        }

        /**
         * Bu, "bozuk cevap" DEĞİL "yayınlanmış gün yok" durumudur ve sağlayıcı onu
         * {@code NOT_PUBLISHED} olarak işaretler — her uzun tatilde arıza alarmı üretmesin.
         */
        @Test
        @DisplayName("Hiç dolu gün yoksa NoPublishedDayException — bozuk yük DEĞİL")
        void emptyRangeIsNotPublishedNotMalformed() {
            assertThatThrownBy(() -> mapper.toSnapshot(document(
                    emptyDay("08-08-2026"), emptyDay("09-08-2026"))))
                    .isInstanceOf(EvdsRateMapper.NoPublishedDayException.class);

            assertThatThrownBy(() -> mapper.toSnapshot(new EvdsSeriesDocument(0, List.of())))
                    .isInstanceOf(EvdsRateMapper.NoPublishedDayException.class);
        }
    }

    @Nested
    @DisplayName("Kur yönü ve doğrulama")
    class Conversion {

        /**
         * EVDS "1 USD = 47,77 TL" der; sözleşmemiz "1 TL = 0,0209 USD"dir. Ters çevrilmiş kur
         * da geçerli bir pozitif sayıdır ve hiçbir doğrulamaya takılmaz — tutarları yalnız
         * 2000 kat şişirir.
         */
        @Test
        @DisplayName("rate = 1 TL kaç birim (satıcının yönünün TERSİ)")
        void invertsDirection() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(
                    document(day("14-08-2026", realRow())));

            assertThat(snapshot.rateOf(CurrencyCode.USD).doubleValue())
                    .isCloseTo(0.0209328, within(0.0000001))
                    .isLessThan(1.0);
        }

        @Test
        @DisplayName("Satış (S) serisi kullanılır — today.xml'in ForexSelling'i ile aynı taraf")
        void usesSellingSeries() {
            assertThat(EvdsRateMapper.seriesNameOf(CurrencyCode.USD)).isEqualTo("TP.DK.USD.S");
            assertThat(EvdsRateMapper.columnNameOf(CurrencyCode.USD)).isEqualTo("TP_DK_USD_S");
        }

        @Test
        @DisplayName("Baz (TRY) sorulmaz — kendi kuru satıcıdan gelmez")
        void doesNotQuoteBase() {
            assertThat(EvdsRateMapper.quotedCurrencies()).doesNotContain(CurrencyCode.TRY);
        }

        @Test
        @DisplayName("Sayıya çevrilemeyen ya da pozitif olmayan kur belgeyi REDDEDER")
        void rejectsUnusableRates() {
            Map<String, String> broken = new LinkedHashMap<>(realRow());
            broken.put("TP_DK_USD_S", "bozuk");

            assertThatThrownBy(() -> mapper.toSnapshot(document(day("14-08-2026", broken))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("USD");

            Map<String, String> zero = new LinkedHashMap<>(realRow());
            zero.put("TP_DK_EUR_S", "0");

            assertThatThrownBy(() -> mapper.toSnapshot(document(day("14-08-2026", zero))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR");
        }

        /** Tazelik ölçütü bizim çekme anımızdır, satıcının yayın günü değil. */
        @Test
        @DisplayName("fetchedAt saatten gelir, rateDate satıcının günüdür")
        void separatesFetchTimeFromRateDate() {
            ExchangeRateSnapshot snapshot = mapper.toSnapshot(
                    document(day("13-08-2026", realRow())));

            assertThat(snapshot.fetchedAt()).isEqualTo(NOW);
            assertThat(snapshot.rateDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        }

        /**
         * İki kaynak aynı kurumun aynı resmî kurudur; sayıları ayrışırsa biri yanlış eşlenmiş
         * demektir. Bu test, {@code TcmbRateMapper} ile {@code EvdsRateMapper}'ın aynı girdi
         * için aynı çıktıyı ürettiğini sabitler.
         */
        @Test
        @DisplayName("today.xml ile aynı sayıyı üretir (aynı kur, aynı yön, aynı birim)")
        void agreesWithTcmbMapper() {
            var tcmb = new com.ohbsy.currencyapi.core.integrations.tcmb.TcmbRateMapper(
                    Clock.fixed(NOW, ZoneOffset.UTC));
            var tcmbSnapshot = tcmb.toSnapshot(
                    new com.ohbsy.currencyapi.core.integrations.tcmb.dtos.TcmbRatesDocument(
                            "13.08.2026", List.of(
                            new com.ohbsy.currencyapi.core.integrations.tcmb.dtos
                                    .TcmbCurrencyRow("USD", "1", "47.6858", "47.7717"),
                            new com.ohbsy.currencyapi.core.integrations.tcmb.dtos
                                    .TcmbCurrencyRow("JPY", "100", "29.9721", "30.0482"))));

            var evdsSnapshot = mapper.toSnapshot(document(day("14-08-2026", realRow())));

            for (CurrencyCode code : List.of(CurrencyCode.USD, CurrencyCode.JPY)) {
                assertThat(evdsSnapshot.rateOf(code))
                        .as("%s kuru iki kaynakta ayni olmali", code)
                        .isEqualByComparingTo(tcmbSnapshot.rateOf(code));
            }
        }
    }

    @Test
    @DisplayName("Sayılar BigDecimal olarak taşınır — double yuvarlaması yok")
    void keepsPrecision() {
        ExchangeRateSnapshot snapshot = mapper.toSnapshot(document(day("14-08-2026", realRow())));

        assertThat(snapshot.rateOf(CurrencyCode.USD))
                .isInstanceOf(BigDecimal.class)
                .satisfies(rate -> assertThat(rate.scale())
                        .isEqualTo(ExchangeRateSnapshot.RATE_SCALE));
    }
}
