package com.ohbsy.currencyapi.core.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.integrations.ecb.EcbExchangeRateProvider;
import com.ohbsy.currencyapi.core.integrations.ecb.EcbRateMapper;
import com.ohbsy.currencyapi.core.integrations.ecb.EcbXmlReader;
import com.ohbsy.currencyapi.core.integrations.evds.EvdsExchangeRateProvider;
import com.ohbsy.currencyapi.core.integrations.evds.EvdsJsonReader;
import com.ohbsy.currencyapi.core.integrations.evds.EvdsRateMapper;
import com.ohbsy.currencyapi.core.integrations.tcmb.TcmbExchangeRateProvider;
import com.ohbsy.currencyapi.core.integrations.tcmb.TcmbRateMapper;
import com.ohbsy.currencyapi.core.integrations.tcmb.TcmbXmlReader;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sağlayıcı zinciri — <b>öncelik sırası ve düşme davranışı</b>.
 *
 * <p>Zincirin değeri tam olarak burada sınanandır: sıra yanlışsa ya da bir arıza yanlış
 * etiketle yukarı çıkarsa sistem yine kur sunar, yani hata <b>sessizdir</b>.
 */
@DisplayName("ChainedExchangeRateProvider — sıralı sağlayıcı zinciri")
class ChainedExchangeRateProviderTest {

    private static ExchangeRateSnapshot snapshotOf(LocalDate rateDate) {
        return new ExchangeRateSnapshot(
                CurrencyCode.TRY,
                Map.of(CurrencyCode.USD, new BigDecimal("0.0209328954")),
                rateDate,
                Instant.parse("2026-08-14T07:21:41Z"),
                TcmbExchangeRateProvider.NAME);
    }

    /** Sırayı da kaydeden sahte sağlayıcı — "kim, kaç kez çağrıldı" sorusu için. */
    private static final class StubProvider implements ExchangeRateProvider {
        private final String name;
        private final ExchangeRateSnapshot result;
        private final ProviderUnavailableException failure;
        private final List<String> callLog;
        private int calls;

        private StubProvider(String name, ExchangeRateSnapshot result,
                             ProviderUnavailableException failure, List<String> callLog) {
            this.name = name;
            this.result = result;
            this.failure = failure;
            this.callLog = callLog;
        }

        static StubProvider serving(String name, LocalDate rateDate, List<String> callLog) {
            return new StubProvider(name, snapshotOf(rateDate), null, callLog);
        }

        static StubProvider failing(String name, ProviderUnavailableException.Reason reason,
                                    List<String> callLog) {
            return new StubProvider(name, null,
                    new ProviderUnavailableException(reason, name + " dustu"), callLog);
        }

        @Override
        public ExchangeRateSnapshot fetchLatestRates() {
            calls++;
            callLog.add(name);
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public String name() {
            return name;
        }
    }

    @Nested
    @DisplayName("Öncelik sırası")
    class Priority {

        @Test
        @DisplayName("İlk sağlayıcı çalışırsa sonrakine HİÇ gidilmez")
        void stopsAtFirstSuccess() {
            List<String> calls = new ArrayList<>();
            var evds = StubProvider.serving("evds", LocalDate.of(2026, 8, 14), calls);
            var tcmb = StubProvider.serving("tcmb", LocalDate.of(2026, 8, 13), calls);

            var snapshot = ChainedExchangeRateProvider.of(evds, tcmb).fetchLatestRates();

            assertThat(calls).containsExactly("evds");
            assertThat(tcmb.calls).isZero();
            assertThat(snapshot.rateDate()).isEqualTo(LocalDate.of(2026, 8, 14));
        }

        /**
         * Bu, zincirin var oluş sebebidir: EVDS anahtarı düşse bile servis kur sunmayı
         * sürdürür — yalnız bülten tarihi bir gün geriden gelir.
         */
        @Test
        @DisplayName("İlk sağlayıcı düşerse ikinciye inilir — servis kur sunmayı SÜRDÜRÜR")
        void fallsBackToNextProvider() {
            List<String> calls = new ArrayList<>();
            var evds = StubProvider.failing(
                    "evds", ProviderUnavailableException.Reason.UNAUTHORIZED, calls);
            var tcmb = StubProvider.serving("tcmb", LocalDate.of(2026, 8, 13), calls);

            var snapshot = ChainedExchangeRateProvider.of(evds, tcmb).fetchLatestRates();

            assertThat(calls).containsExactly("evds", "tcmb");
            assertThat(snapshot.rateDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        }

        @Test
        @DisplayName("Tek sağlayıcılı zincir (anahtarsız kurulum) aynen çalışır")
        void singleProviderChainWorks() {
            List<String> calls = new ArrayList<>();
            var tcmb = StubProvider.serving("tcmb", LocalDate.of(2026, 8, 13), calls);

            assertThat(ChainedExchangeRateProvider.of(tcmb).fetchLatestRates()).isNotNull();
            assertThat(calls).containsExactly("tcmb");
        }
    }

    @Nested
    @DisplayName("Zincirin tamamı düştüğünde")
    class TotalFailure {

        /**
         * TCMB hafta sonu yayın yapmaz. Zincir bunu {@code NOT_PUBLISHED} olarak taşımazsa
         * servis katmanı her cumartesi WARN üretir ve gerçek kesinti o gürültünün altında
         * görünmez olur.
         */
        @Test
        @DisplayName("Hepsi NOT_PUBLISHED ise sonuç da NOT_PUBLISHED — hafta sonu alarm üretmez")
        void keepsNotPublishedWhenAllAgree() {
            List<String> calls = new ArrayList<>();
            var chain = ChainedExchangeRateProvider.of(
                    StubProvider.failing(
                            "evds", ProviderUnavailableException.Reason.NOT_PUBLISHED, calls),
                    StubProvider.failing(
                            "tcmb", ProviderUnavailableException.Reason.NOT_PUBLISHED, calls));

            assertThatThrownBy(chain::fetchLatestRates)
                    .isInstanceOf(ProviderUnavailableException.class)
                    .extracting(e -> ((ProviderUnavailableException) e).getReason())
                    .isEqualTo(ProviderUnavailableException.Reason.NOT_PUBLISHED);
        }

        /**
         * Takvim her zaman en zararsız açıklamadır. Öne alınsaydı, düzeltilmesi gereken bir
         * yapılandırma hatası (süresi dolmuş anahtar) tatil gürültüsünün altına gömülürdü.
         */
        @Test
        @DisplayName("Gerçek arıza NOT_PUBLISHED'ı ezer — düzeltilmesi gereken sebep yukarı çıkar")
        void realFailureOutranksCalendar() {
            List<String> calls = new ArrayList<>();
            var chain = ChainedExchangeRateProvider.of(
                    StubProvider.failing(
                            "evds", ProviderUnavailableException.Reason.UNAUTHORIZED, calls),
                    StubProvider.failing(
                            "tcmb", ProviderUnavailableException.Reason.NOT_PUBLISHED, calls));

            assertThatThrownBy(chain::fetchLatestRates)
                    .isInstanceOf(ProviderUnavailableException.class)
                    .extracting(e -> ((ProviderUnavailableException) e).getReason())
                    .isEqualTo(ProviderUnavailableException.Reason.UNAUTHORIZED);
        }

        /** Hiçbir arıza kaybolmaz: tanılama için hepsi taşınır. */
        @Test
        @DisplayName("Atlanan sağlayıcıların hataları suppressed olarak taşınır")
        void carriesEveryFailure() {
            List<String> calls = new ArrayList<>();
            var chain = ChainedExchangeRateProvider.of(
                    StubProvider.failing(
                            "evds", ProviderUnavailableException.Reason.TIMEOUT, calls),
                    StubProvider.failing(
                            "tcmb", ProviderUnavailableException.Reason.TRANSPORT, calls));

            assertThatThrownBy(chain::fetchLatestRates)
                    .hasMessageContaining("TIMEOUT")
                    .hasMessageContaining("TRANSPORT")
                    .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
        }
    }

    /**
     * <b>Ad CACHE YUVASININ kimliğidir</b> ({@code RateCache.find(provider.name())}). Hangi
     * yolun konuştuğuna göre değişseydi cache bölünürdü: ECB'ye düşülen bir gün TCMB'nin kaydı
     * ayrı bir yuvada eskimeye devam eder, TCMB döndüğünde elimizde farklı yaşlarda iki tablo
     * olurdu.
     *
     * <p>Cevaptaki {@code provider} alanı buradan DEĞİL, kaydın kendisinden gelir
     * ({@code ExchangeRateSnapshot.source}) — bkz. {@code ExchangeRateServiceImpl}.
     */
    @Test
    @DisplayName("name() hangi yolun konuştuğuna göre DEĞİŞMEZ — cache yuvası kararlı")
    void nameIsStableAcrossFailover() {
        List<String> calls = new ArrayList<>();
        var viaEvds = ChainedExchangeRateProvider.of(
                StubProvider.serving("evds", LocalDate.of(2026, 8, 14), calls),
                StubProvider.serving("tcmb", LocalDate.of(2026, 8, 13), calls));
        var viaTcmb = ChainedExchangeRateProvider.of(
                StubProvider.failing("evds", ProviderUnavailableException.Reason.TIMEOUT, calls),
                StubProvider.serving("tcmb", LocalDate.of(2026, 8, 13), calls));

        assertThat(viaEvds.name())
                .isEqualTo(viaTcmb.name())
                .isEqualTo(TcmbExchangeRateProvider.NAME);
    }

    /**
     * <b>Zincirin KURULUŞU</b> — hangi basamağın listeye girdiği. Davranış testleri sırayı
     * sınar; burada sınanan şey, kapalı bir basamağın zincire <b>hiç konmadığıdır</b>. Listede
     * dursaydı her istekte bir istisna üretip yakalanır, "atlanan sağlayıcı" olarak loglanır
     * ve normal çalışma arıza gibi görünürdü.
     */
    @Nested
    @DisplayName("zincirin kuruluşu — açık/kapalı basamaklar")
    class Assembly {

        private static final Clock CLOCK =
                Clock.fixed(Instant.parse("2026-08-14T09:00:00Z"), ZoneOffset.UTC);

        private ChainedExchangeRateProvider chainOf(String evdsKey, boolean ecbEnabled) {
            CurrencyApiProperties properties = new CurrencyApiProperties();
            properties.getEvds().setKey(evdsKey);
            properties.getEcb().setEnabled(ecbEnabled);

            return new ChainedExchangeRateProvider(
                    new EvdsExchangeRateProvider(properties,
                            new EvdsJsonReader(new ObjectMapper()),
                            new EvdsRateMapper(CLOCK), CLOCK),
                    new TcmbExchangeRateProvider(properties,
                            new TcmbXmlReader(), new TcmbRateMapper(CLOCK)),
                    new EcbExchangeRateProvider(properties,
                            new EcbXmlReader(), new EcbRateMapper(CLOCK)));
        }

        @Test
        @DisplayName("hepsi açıkken sıra: evds → tcmb → ecb")
        void fullChainIsOrderedNewestToIndependent() {
            assertThat(chainOf("anahtar", true).chainNames())
                    .containsExactly("evds", "tcmb", "ecb");
        }

        @Test
        @DisplayName("ECB kapalıyken zincirde YOKTUR (varsayılan)")
        void omitsEcbWhenDisabled() {
            assertThat(chainOf("anahtar", false).chainNames())
                    .containsExactly("evds", "tcmb");
        }

        @Test
        @DisplayName("EVDS anahtarsızken zincirde YOKTUR")
        void omitsEvdsWhenUnconfigured() {
            assertThat(chainOf("", true).chainNames())
                    .containsExactly("tcmb", "ecb");
        }

        /** Hiçbir yapılandırma verilmeden servis yine kur sunar: today.xml tek başına yeter. */
        @Test
        @DisplayName("hiçbiri yapılandırılmamışsa geriye today.xml kalır")
        void alwaysKeepsTcmbDocumentPath() {
            assertThat(chainOf("", false).chainNames())
                    .containsExactly("tcmb");
        }

        /**
         * ECB zincirin SONUNDADIR ve bu bir öncelik kararıdır: TRY için resmî kuru TCMB
         * belirler, ECB'nin referans kuru yakın ama aynı sayı değildir ve iki bölmeyle elde
         * edilir. İkisi de varken TCMB seçilmelidir — ECB "hiç kur yok"a karşı sigortadır.
         */
        @Test
        @DisplayName("ECB her zaman TCMB'den SONRA gelir")
        void ecbNeverPrecedesTcmb() {
            List<String> names = chainOf("anahtar", true).chainNames();

            assertThat(names.indexOf("ecb")).isGreaterThan(names.indexOf("tcmb"));
        }
    }
}
