package com.ohbsy.currencyapi.core.integrations.tcmb;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.integrations.ExchangeRateProvider;
import com.ohbsy.currencyapi.core.integrations.ProviderUnavailableException;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * <b>TCMB sağlayıcısı — ana veri kaynağı.</b> Türkiye Cumhuriyet Merkez Bankası'nın günlük
 * döviz kuru belgesini çeker ve domain modeline çevirir.
 *
 * <h2>Zincir: HTTP → XML → DTO → domain</h2>
 * Her adım ayrı bir sınıftadır ({@link TcmbXmlReader}, {@link TcmbRateMapper}) çünkü her adım
 * ayrı bir sebeple bozulur ve ayrı ayrı test edilebilmelidir: "satıcıya ulaşamadık",
 * "belgeyi okuyamadık", "kuru çeviremedik" farklı arızalardır ve farklı yerlerde aranır.
 *
 * <h2>TCMB'nin günlük yayın mantığı</h2>
 * TCMB kuru <b>iş günlerinde bir kez</b> yayınlar; hafta sonu ve resmî tatillerde
 * {@code today.xml} <b>yoktur (404)</b>. Bu bir arıza değil takvimdir ve
 * {@link ProviderUnavailableException.Reason#NOT_PUBLISHED} ile ayrı işaretlenir — servis
 * katmanı o gün son geçerli kuru sunar. Ayrımın değeri alarm tarafındadır: her cumartesi
 * "sağlayıcı düştü" alarmı üreten bir sistem, gerçek kesintiyi de görünmez kılar.
 */
@Component
public class TcmbExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(TcmbExchangeRateProvider.class);

    public static final String NAME = "tcmb";
    /** Satıcının günlük belge yolu. */
    static final String DAILY_PATH = "/kurlar/today.xml";

    private final CurrencyApiProperties properties;
    private final TcmbXmlReader xmlReader;
    private final TcmbRateMapper mapper;
    private final HttpClient httpClient;

    public TcmbExchangeRateProvider(CurrencyApiProperties properties,
                                    TcmbXmlReader xmlReader,
                                    TcmbRateMapper mapper) {
        this.properties = properties;
        this.xmlReader = xmlReader;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTcmb().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public ExchangeRateSnapshot fetchLatestRates() {
        String xml = fetchDocument();
        ExchangeRateSnapshot snapshot = mapOrFail(xml);
        log.info("TCMB kurlari cekildi rateDate={} currencies={}",
                snapshot.rateDate(), snapshot.availableCurrencies());
        return snapshot;
    }

    @Override
    public String name() {
        return NAME;
    }

    private String fetchDocument() {
        String url = properties.getTcmb().getBaseUrl() + DAILY_PATH;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.getTcmb().getReadTimeout())
                    .header("User-Agent", "crm-currency-api/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                // Hafta sonu/tatil: belge yok. Arıza DEĞİL takvim.
                throw new ProviderUnavailableException(
                        ProviderUnavailableException.Reason.NOT_PUBLISHED,
                        "TCMB gunluk belgesi yayinlanmamis (hafta sonu/tatil): " + url);
            }
            if (response.statusCode() != 200) {
                throw new ProviderUnavailableException(
                        ProviderUnavailableException.Reason.TRANSPORT,
                        "TCMB HTTP " + response.statusCode());
            }
            return response.body();

        } catch (ProviderUnavailableException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TIMEOUT, "TCMB zaman asimi", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TRANSPORT, "TCMB cagrisi kesildi", e);
        } catch (Exception e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TRANSPORT, "TCMB belgesi alinamadi", e);
        }
    }

    /**
     * Ham ayrıştırma/eşleme hatası iş katmanına <b>sızmaz</b>: arayüz tek istisna tipi vaat eder.
     * Bozuk yük {@code TRANSPORT}'tan ayrılır — "sunucu yok" ile "cevabı bozuk" farklı
     * arızalardır.
     */
    private ExchangeRateSnapshot mapOrFail(String xml) {
        try {
            return mapper.toSnapshot(xmlReader.read(xml));
        } catch (IllegalArgumentException e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.INVALID_PAYLOAD,
                    "TCMB belgesi kullanilamaz durumda", e);
        }
    }
}
