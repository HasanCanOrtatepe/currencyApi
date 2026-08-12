package com.ohbsy.currencyapi.core.integrations.tcmb;

import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XML → DTO → domain zincirinin <b>en kritik</b> testi: kur YÖNÜ.
 *
 * <p>TCMB "1 USD = 47,73 TL" yayınlar, sözleşmemiz "1 TL = 0,0209 USD"dir. Yön hatası hiçbir
 * doğrulamaya takılmaz — 47,73 de geçerli bir pozitif kurdur — ve tutarları ~2000 kat şişirir.
 * Tek koruma budur.
 */
@DisplayName("TCMB eşlemesi — yön, birim, tarih")
class TcmbRateMapperTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    private final TcmbXmlReader reader = new TcmbXmlReader();
    private final TcmbRateMapper mapper =
            new TcmbRateMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Tarih_Date Tarih="11.08.2026" Date="08/11/2026">
              <Currency Kod="USD" CurrencyCode="USD">
                <Unit>1</Unit><Isim>ABD DOLARI</Isim>
                <ForexBuying>47.7000</ForexBuying><ForexSelling>47.7300</ForexSelling>
              </Currency>
              <Currency Kod="JPY" CurrencyCode="JPY">
                <Unit>100</Unit><Isim>JAPON YENI</Isim>
                <ForexBuying>32.1000</ForexBuying><ForexSelling>32.2000</ForexSelling>
              </Currency>
              <Currency Kod="XDR" CurrencyCode="XDR">
                <Unit>1</Unit><Isim>SDR</Isim>
                <ForexBuying>63.0000</ForexBuying><ForexSelling>63.5000</ForexSelling>
              </Currency>
            </Tarih_Date>
            """;

    private ExchangeRateSnapshot map(String xml) {
        return mapper.toSnapshot(reader.read(xml));
    }

    @Test
    @DisplayName("Kur YÖNÜ çevrilir: '1 USD = 47,73 TL' → '1 TL = 0,0209... USD'")
    void invertsRateDirection() {
        ExchangeRateSnapshot snapshot = map(XML);

        assertThat(snapshot.base()).isEqualTo(CurrencyCode.TRY);
        // 1 / 47,73 = 0,0209511837... (10 hane, HALF_UP)
        assertThat(snapshot.rateOf(CurrencyCode.USD)).isEqualByComparingTo("0.0209511837");
        // Ters yön de sunulur ve satıcının yayınladığı değere (yuvarlama payıyla) geri döner:
        // iki bölme üst üste bindiği için son hane birebir dönmez, bu beklenen bir davranıştır.
        assertThat(snapshot.unitPriceOf(CurrencyCode.USD))
                .isCloseTo(new java.math.BigDecimal("47.7300"),
                        org.assertj.core.data.Offset.offset(new java.math.BigDecimal("0.0001")));
        assertThat(snapshot.rateOf(CurrencyCode.TRY)).isEqualByComparingTo("1");
    }

    /** {@code Unit=100} hesaba katılmazsa kur 100 kat yanlış olur — sessizce. */
    @Test
    @DisplayName("Unit çarpanı uygulanır: JPY 100 birim üzerinden yayınlanır")
    void appliesUnitMultiplier() {
        // 100 / 32,20 = 3,10559... yani 1 TL = 3,1056 JPY
        assertThat(map(XML).rateOf(CurrencyCode.JPY)).isEqualByComparingTo("3.1055900621");
    }

    @Test
    @DisplayName("Tanımadığımız kodlar sessizce atlanır (TCMB 20+ kod yayınlar)")
    void skipsUnknownCurrencies() {
        assertThat(map(XML).availableCurrencies())
                .containsExactlyInAnyOrder(CurrencyCode.TRY, CurrencyCode.USD, CurrencyCode.JPY);
    }

    @Test
    @DisplayName("Yayın günü belgeden okunur (çekme anımızdan ayrı tutulur)")
    void readsRateDateFromDocument() {
        ExchangeRateSnapshot snapshot = map(XML);

        assertThat(snapshot.rateDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(snapshot.fetchedAt()).isEqualTo(NOW);
    }

    /** "Yarısı doğru" tablo kabul edilmez: tanıdığımız bir kod bozuksa belge düşer. */
    @Test
    @DisplayName("Desteklenen kodda bozuk kur → belge tamamen reddedilir")
    void brokenSupportedRateFailsDocument() {
        String broken = XML.replace("<ForexSelling>47.7300</ForexSelling>",
                "<ForexSelling>sifir</ForexSelling>");

        assertThatThrownBy(() -> map(broken)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Sıfır/negatif kur reddedilir (tutarları sessizce sıfırlardı)")
    void nonPositiveRateIsRejected() {
        String zero = XML.replace("<ForexSelling>47.7300</ForexSelling>",
                "<ForexSelling>0</ForexSelling>");

        assertThatThrownBy(() -> map(zero)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Bozuk XML ve TCMB olmayan belge reddedilir")
    void malformedDocumentsRejected() {
        assertThatThrownBy(() -> map("<Tarih_Date><Currency Kod="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> map("<html><body>Not Found</body></html>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tarih_Date");
    }

    /** XXE: cevap ağdan gelir ve istek bizim sunucumuzdan çıkar — ağ sınırı korumaz. */
    @Test
    @DisplayName("DOCTYPE taşıyan yük reddedilir (XXE koruması)")
    void rejectsDoctypePayload() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Tarih_Date Tarih="11.08.2026">
                  <Currency Kod="USD"><Unit>1</Unit><ForexSelling>47.73</ForexSelling></Currency>
                </Tarih_Date>
                """;

        assertThatThrownBy(() -> map(xxe)).isInstanceOf(IllegalArgumentException.class);
    }
}
