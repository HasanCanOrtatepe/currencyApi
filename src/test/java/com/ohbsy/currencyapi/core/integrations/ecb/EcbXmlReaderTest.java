package com.ohbsy.currencyapi.core.integrations.ecb;

import com.ohbsy.currencyapi.core.integrations.ecb.dtos.EcbDailyRates;
import com.ohbsy.currencyapi.core.integrations.ecb.dtos.EcbRatesDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ECB belgesinin okunması — <b>üç ayrı şeyin de {@code <Cube>} adını taşıması</b>.
 *
 * <p>Buradaki asıl risk düz gezmedir: {@code getElementsByTagName("Cube")} sarmalayıcıyı,
 * günü ve kur satırlarını birlikte döndürür. Kur satırlarını gün sanan bir okuyucu <b>hata
 * vermez</b>, yalnız boş günler üretir — yani arıza sessizdir.
 */
@DisplayName("EcbXmlReader — ECB eurofxref belgesi")
class EcbXmlReaderTest {

    private final EcbXmlReader reader = new EcbXmlReader();

    private static String envelope(String cubes) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                                 xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                  <gesmes:subject>Reference rates</gesmes:subject>
                  <gesmes:Sender><gesmes:name>European Central Bank</gesmes:name></gesmes:Sender>
                  <Cube>
                %s
                  </Cube>
                </gesmes:Envelope>
                """.formatted(cubes);
    }

    @Nested
    @DisplayName("günlük dosya")
    class Daily {

        @Test
        @DisplayName("tek günü ve altındaki kurları okur")
        void readsSingleDay() {
            EcbRatesDocument document = reader.read(envelope("""
                        <Cube time="2026-08-14">
                          <Cube currency="USD" rate="1.1665"/>
                          <Cube currency="TRY" rate="55.7218"/>
                          <Cube currency="JPY" rate="171.85"/>
                        </Cube>
                    """));

            assertThat(document.days()).hasSize(1);
            EcbDailyRates day = document.days().get(0);
            assertThat(day.date()).isEqualTo("2026-08-14");
            assertThat(day.rates())
                    .containsEntry("USD", "1.1665")
                    .containsEntry("TRY", "55.7218")
                    .containsEntry("JPY", "171.85");
        }

        @Test
        @DisplayName("gesmes: önekli üst elemanlar gün sayılmaz")
        void ignoresEnvelopeElements() {
            EcbRatesDocument document = reader.read(envelope("""
                        <Cube time="2026-08-14">
                          <Cube currency="TRY" rate="55.7218"/>
                        </Cube>
                    """));

            assertThat(document.days()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("çok günlü dosya (90 günlük şema)")
    class Historical {

        /**
         * Asıl tuzak: kurlar {@code getElementsByTagName} ile toplansaydı ilk günün altına
         * ikinci günün satırları da girerdi — iki gün aynı tabloyu gösterirdi.
         */
        @Test
        @DisplayName("her günün kuru YALNIZ kendi altındaki satırlardan okunur")
        void doesNotLeakRatesBetweenDays() {
            EcbRatesDocument document = reader.read(envelope("""
                        <Cube time="2026-08-14">
                          <Cube currency="USD" rate="1.1665"/>
                          <Cube currency="TRY" rate="55.7218"/>
                        </Cube>
                        <Cube time="2026-08-13">
                          <Cube currency="USD" rate="1.1600"/>
                          <Cube currency="TRY" rate="55.0000"/>
                        </Cube>
                    """));

            assertThat(document.days()).hasSize(2);
            assertThat(document.days().get(0).rates())
                    .hasSize(2).containsEntry("USD", "1.1665");
            assertThat(document.days().get(1).rates())
                    .hasSize(2).containsEntry("USD", "1.1600");
        }
    }

    @Nested
    @DisplayName("kullanılamaz belge")
    class Rejected {

        @Test
        @DisplayName("hiç <Cube> yoksa ECB belgesi değildir")
        void rejectsForeignDocument() {
            assertThatThrownBy(() -> reader.read("<html><body>bakim</body></html>"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ECB belgesi degil");
        }

        @Test
        @DisplayName("DOCTYPE reddedilir (XXE)")
        void rejectsDoctype() {
            assertThatThrownBy(() -> reader.read("""
                    <?xml version="1.0"?>
                    <!DOCTYPE Envelope [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <Envelope><Cube><Cube time="2026-08-14"/></Cube></Envelope>
                    """))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("boş gün (kur satırı olmayan) taşınır ama boş kalır")
        void keepsEmptyDayEmpty() {
            EcbRatesDocument document = reader.read(envelope("""
                        <Cube time="2026-08-14"/>
                    """));

            assertThat(document.days()).hasSize(1);
            assertThat(document.days().get(0).hasNoValues()).isTrue();
        }
    }
}
