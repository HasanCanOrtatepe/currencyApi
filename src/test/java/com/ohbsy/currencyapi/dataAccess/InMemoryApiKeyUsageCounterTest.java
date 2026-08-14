package com.ohbsy.currencyapi.dataAccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Birikmeli kullanım sayacı.
 *
 * <p>Bu sınıfın var oluş sebebi tek bir gözlemdir: panelin "Kalan" sütunu hız sınırının
 * 1 dakikalık penceresini gösteriyordu ve pencere dolunca sıfırlandığı için <b>hep dolu</b>
 * görünüyordu. Buradaki sayı bunun tersini yapmalıdır — <b>azalmaz, artar</b>.
 */
@DisplayName("InMemoryApiKeyUsageCounter — birikmeli kullanım")
class InMemoryApiKeyUsageCounterTest {

    /** 14 Ağustos 2026, TSİ 11:21. */
    private static final Instant MIDDAY = Instant.parse("2026-08-14T08:21:00Z");

    private InMemoryApiKeyUsageCounter counterAt(Instant now) {
        return new InMemoryApiKeyUsageCounter(Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Kullandıkça ARTAR — hız sınırının aksine sıfırlanmaz")
    void accumulates() {
        var counter = counterAt(MIDDAY);

        for (int i = 0; i < 5; i++) {
            counter.record("anahtar-1");
        }

        assertThat(counter.of("anahtar-1").today()).isEqualTo(5);
        assertThat(counter.of("anahtar-1").total()).isEqualTo(5);
    }

    @Test
    @DisplayName("Okumak sayacı ARTIRMAZ — listeleme bir kullanım değildir")
    void readingDoesNotCount() {
        var counter = counterAt(MIDDAY);
        counter.record("anahtar-1");

        counter.of("anahtar-1");
        counter.of("anahtar-1");

        assertThat(counter.of("anahtar-1").total()).isEqualTo(1);
    }

    @Test
    @DisplayName("Anahtarlar birbirine karışmaz")
    void countsPerKey() {
        var counter = counterAt(MIDDAY);
        counter.record("anahtar-1");
        counter.record("anahtar-2");
        counter.record("anahtar-2");

        assertThat(counter.of("anahtar-1").total()).isEqualTo(1);
        assertThat(counter.of("anahtar-2").total()).isEqualTo(2);
    }

    @Test
    @DisplayName("Hiç kullanılmamış anahtar sıfır döner, patlamaz")
    void unknownKeyIsZero() {
        assertThat(counterAt(MIDDAY).of("hic-kullanilmadi"))
                .isEqualTo(ApiKeyUsageCounter.Usage.none());
    }

    /**
     * Statik anahtarların ve anonim isteklerin panelde satırı yoktur; filtre onlar için
     * {@code null} geçer ve sayaç sessizce atlamalıdır — patlarsa istek düşer.
     */
    @Test
    @DisplayName("null kimlik sessizce atlanır (statik anahtar / anonim istek)")
    void nullKeyIsIgnored() {
        var counter = counterAt(MIDDAY);

        counter.record(null);

        assertThat(counter.of(null)).isEqualTo(ApiKeyUsageCounter.Usage.none());
    }

    /**
     * <b>"Bugün" Türkiye takvimiyle çizilir.</b> UTC seçilseydi gün TSİ 03:00'te dönerdi;
     * gece 01:00'de atılan bir istek, sabah bakan kişiye "dün" olarak görünürdü.
     */
    @Test
    @DisplayName("Gece TSİ 01:00'deki istek AYNI Türkiye gününe sayılır")
    void dayBoundaryFollowsIstanbulNotUtc() {
        // UTC 13.08 22:00 = TSİ 14.08 01:00 — UTC'ye göre dün, Türkiye'ye göre bugün
        var clock = new MutableClock(Instant.parse("2026-08-13T22:00:00Z"));
        var counter = new InMemoryApiKeyUsageCounter(clock);

        counter.record("anahtar-1");
        clock.set(MIDDAY);                      // aynı Türkiye günü, TSİ 11:21

        assertThat(counter.of("anahtar-1").today()).isEqualTo(1);
    }

    @Test
    @DisplayName("Gün dönünce 'bugün' sıfırlanır ama TOPLAM durur")
    void dailyResetsButTotalPersists() {
        var clock = new MutableClock(MIDDAY);
        var counter = new InMemoryApiKeyUsageCounter(clock);

        counter.record("anahtar-1");
        counter.record("anahtar-1");
        assertThat(counter.of("anahtar-1").today()).isEqualTo(2);

        clock.set(MIDDAY.plus(Duration.ofDays(1)));
        counter.record("anahtar-1");

        assertThat(counter.of("anahtar-1").today()).isEqualTo(1);
        assertThat(counter.of("anahtar-1").total()).isEqualTo(3);
    }

    @Test
    @DisplayName("kind() memory döner")
    void reportsKind() {
        assertThat(counterAt(MIDDAY).kind()).isEqualTo("memory");
    }

    /**
     * İleri sarılabilen {@link Clock} — gün dönümünü altyapısız sınamanın yolu.
     * {@code withZone} aynı zaman referansını paylaşır, böylece saat ilerletildiğinde
     * sayacın oluşturduğu bölgeli görünüm de ilerler.
     */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(Instant start) {
            this(new AtomicReference<>(start), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new MutableClock(now, other);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
