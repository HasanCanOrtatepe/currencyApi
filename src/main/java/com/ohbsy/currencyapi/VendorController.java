package com.ohbsy.currencyapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * T34 Faz 7 — iki satıcının günlük kur uçları. <b>Yollar gerçek satıcıların yollarıdır</b>
 * ({@code /kurlar/today.xml}, {@code /stats/eurofxref/eurofxref-daily.xml}); CRM tarafında
 * {@code fake} ile {@code real} arasındaki tek fark base URL olsun diye.
 */
@RestController
public class VendorController {

    private static final Logger log = LoggerFactory.getLogger(VendorController.class);

    private static final DateTimeFormatter TCMB_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ChaosState chaos;
    private final RateTable rates;

    public VendorController(ChaosState chaos, RateTable rates) {
        this.chaos = chaos;
        this.rates = rates;
    }

    /** TCMB günlük kur belgesi. */
    @GetMapping(value = "/kurlar/today.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> tcmbDaily() {
        ResponseEntity<String> chaosResponse = applyChaos(ChaosState.Source.TCMB);
        if (chaosResponse != null) {
            return chaosResponse;
        }
        return ResponseEntity.ok(tcmbXml());
    }

    /** ECB günlük referans kur belgesi. */
    @GetMapping(value = "/stats/eurofxref/eurofxref-daily.xml",
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> ecbDaily() {
        ResponseEntity<String> chaosResponse = applyChaos(ChaosState.Source.ECB);
        if (chaosResponse != null) {
            return chaosResponse;
        }
        return ResponseEntity.ok(ecbXml());
    }

    /**
     * Kaynağın kaos moduna göre arıza üretir; normal yolda {@code null} döner.
     *
     * @return arıza cevabı ya da {@code null} (normal yol)
     */
    private ResponseEntity<String> applyChaos(ChaosState.Source source) {
        ChaosState.Mode mode = chaos.mode(source);
        if (mode != ChaosState.Mode.SUCCESS) {
            log.info("kaos: source={} mode={}", source, mode);
        }
        return switch (mode) {
            case SUCCESS -> null;
            case ERROR -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("<error>simulated failure</error>");
            // 404: TCMB'nin tatil davranışı. Gövde de gerçekçidir (satıcı HTML döner).
            case HOLIDAY -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("<html><body>Not Found</body></html>");
            case GARBAGE -> ResponseEntity.ok("<Tarih_Date Tarih=\"11.08.2026\"><Currency Kod=");
            case TIMEOUT -> {
                // GERÇEKTEN bekler: burada sınanan şey CRM'in HTTP zaman aşımıdır (bkz. ChaosState).
                sleepQuietly(chaos.getDelayMillis());
                yield ResponseEntity.ok(source == ChaosState.Source.TCMB ? tcmbXml() : ecbXml());
            }
        };
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** TCMB şekli: yön "1 yabancı birim = X TL", {@code Unit} alanı JPY'de 100'dür. */
    private String tcmbXml() {
        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<Tarih_Date Tarih=\"")
                .append(LocalDate.now().format(TCMB_DATE))
                .append("\" Date=\"")
                .append(LocalDate.now())
                .append("\">\n");
        int order = 0;
        for (Map.Entry<String, BigDecimal> entry : rates.tryPerUnit().entrySet()) {
            String code = entry.getKey();
            int unit = "JPY".equals(code) ? 100 : 1;
            xml.append("  <Currency CrossOrder=\"").append(order++)
                    .append("\" Kod=\"").append(code)
                    .append("\" CurrencyCode=\"").append(code).append("\">\n")
                    .append("    <Unit>").append(unit).append("</Unit>\n")
                    .append("    <Isim>").append(code).append("</Isim>\n")
                    .append("    <ForexBuying>").append(entry.getValue()).append("</ForexBuying>\n")
                    .append("    <ForexSelling>").append(entry.getValue()).append("</ForexSelling>\n")
                    .append("  </Currency>\n");
        }
        return xml.append("</Tarih_Date>\n").toString();
    }

    /** ECB şekli: taban EUR, EUR'un kendi satırı YOKTUR (adaptör onu kendisi ekler). */
    private String ecbXml() {
        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gesmes:Envelope xmlns:gesmes=\"http://www.gesmes.org/xml/2002-08-01\"\n")
                .append("    xmlns=\"http://www.ecb.int/vocabulary/2002-08-01/eurofxref\">\n")
                .append("  <gesmes:subject>Reference rates</gesmes:subject>\n")
                .append("  <Cube>\n    <Cube time=\"").append(LocalDate.now()).append("\">\n");
        rates.perEuro().forEach((code, rate) -> xml
                .append("      <Cube currency=\"").append(code)
                .append("\" rate=\"").append(rate).append("\"/>\n"));
        return xml.append("    </Cube>\n  </Cube>\n</gesmes:Envelope>\n").toString();
    }
}
