package com.ohbsy.currencyapi.core.integrations.ecb;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.integrations.ExchangeRateProvider;
import com.ohbsy.currencyapi.core.integrations.ProviderUnavailableException;
import com.ohbsy.currencyapi.core.utilities.BoundedHttpBody;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * <b>ECB sağlayıcısı — zincirin son basamağı ve tek BAĞIMSIZ kaynağı.</b> Avrupa Merkez
 * Bankası'nın günlük referans kuru belgesini ({@code eurofxref-daily.xml}) çeker.
 *
 * <h2>Neden var: tek nokta arızası TCMB'ydi</h2>
 * EVDS ve {@code today.xml} <b>aynı kurumun</b> iki kapısıdır. Aynı kurum erişilemez olduğunda
 * (bakım, kesinti, ağ yolu) ikisi birden düşer ve servisin elinde yalnız kendi bayat kuru
 * kalır. ECB farklı bir kurum, farklı bir altyapı, farklı bir ülkedir — <b>birlikte düşmeleri
 * için ortak bir sebep yoktur.</b> Kazanılan şey budur; takvim değildir (bkz. aşağıdaki not).
 *
 * <h2>Neden hafta sonunu ÇÖZMEZ</h2>
 * ECB de hafta sonu ve TARGET tatillerinde yayın yapmaz. Yayın boşluğunun cevabı bu sağlayıcı
 * değil, cache'in <b>7 günlük saklama</b> penceresidir ("son geçerli kur"). Dahası bu sınıf
 * hafta sonu bilinçle <b>susar</b>: ECB'nin günlük dosyası cumartesi de erişilebilir, içinde
 * cuma günü durur — {@code EcbRateMapper} yalnız bugünün belgesini kabul ederek her hafta sonu
 * TCMB→ECB kurum değişimi yaşanmasını engeller.
 *
 * <h2>Zincirin SONUNDA — çünkü TCMB otoritedir</h2>
 * TRY için resmî kuru TCMB belirler; ECB'nin yayınladığı <i>referans kuru</i> yakın ama aynı
 * sayı değildir ve iki bölmeyle (çapraz kur) elde edilir. İkisi de varken TCMB'yi seçmek
 * doğrudur; ECB "hiç kur yok"a karşı bir sigortadır, bir alternatif değil.
 *
 * <h2>Varsayılan KAPALI</h2>
 * Anahtar istemez, yani EVDS'teki "anahtar aynı zamanda düğmedir" hilesi burada kurulamaz ve
 * açık bir {@code enabled} bayrağı gerekir. Varsayılanın kapalı olması {@code auth.enabled} ve
 * {@code rate-limit.client-ip-header} ile aynı gerekçedir: bu sağlayıcı <b>sunulan sayının
 * hangi kurumdan geldiğini değiştirebilir</b>, ve bunu bilmeyen bir kurulum sessizce
 * devralmamalıdır. Açmak bilinçli bir dağıtım kararıdır.
 */
@Component
public class EcbExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(EcbExchangeRateProvider.class);

    public static final String NAME = "ecb";

    /** Satıcının günlük belge yolu — tek gün içerir, şeması 90 günlük dosyayla aynıdır. */
    static final String DAILY_PATH = "/stats/eurofxref/eurofxref-daily.xml";

    private final CurrencyApiProperties properties;
    private final EcbXmlReader xmlReader;
    private final EcbRateMapper mapper;
    private final HttpClient httpClient;

    public EcbExchangeRateProvider(CurrencyApiProperties properties,
                                   EcbXmlReader xmlReader,
                                   EcbRateMapper mapper) {
        this.properties = properties;
        this.xmlReader = xmlReader;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getEcb().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Açık mı — zincire alınıp alınmayacağını bu belirler ({@code EvdsProvider} deseni). */
    public boolean isEnabled() {
        return properties.getEcb().isEnabled();
    }

    @Override
    public ExchangeRateSnapshot fetchLatestRates() {
        String url = properties.getEcb().getBaseUrl() + DAILY_PATH;
        ExchangeRateSnapshot snapshot = mapOrFail(fetchDocument(url), url);
        // WARN değil INFO: buraya düşmek TCMB'nin iki yolunun da düştüğü anlamına gelir ve o
        // uyarıyı zincir zaten basar; burada iki kez alarm üretmenin değeri yoktur.
        log.info("ECB kurlari cekildi (TCMB'ye ulasilamadi) rateDate={} currencies={}",
                snapshot.rateDate(), snapshot.availableCurrencies());
        return snapshot;
    }

    @Override
    public String name() {
        return NAME;
    }

    private String fetchDocument(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.getEcb().getReadTimeout())
                    .header("User-Agent", "crm-currency-api/1.0")
                    .GET()
                    .build();
            // ofString() DEĞİL: gövde üst sınırlı okunur, bkz. BoundedHttpBody.
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            // Hata yollarında da kapanmalı: kapatılmayan gövde bağlantıyı havuzda tutar.
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) {
                    // TCMB'nin aksine ECB'de 404 TAKVİM DEĞİLDİR: günlük dosya hafta sonu da
                    // durur (içeriği eskir, kendisi kaybolmaz). Kaybolduysa yol değişmiştir.
                    throw new ProviderUnavailableException(
                            ProviderUnavailableException.Reason.TRANSPORT,
                            "ECB gunluk belgesi bulunamadi (404): " + url);
                }
                if (response.statusCode() != 200) {
                    throw new ProviderUnavailableException(
                            ProviderUnavailableException.Reason.TRANSPORT,
                            "ECB HTTP " + response.statusCode());
                }
                return BoundedHttpBody.read(body, response.headers());
            }

        } catch (ProviderUnavailableException e) {
            throw e;
        } catch (BoundedHttpBody.TooLargeException e) {
            // Taşıma çalıştı, gelen ŞEY kullanılamaz — INVALID_PAYLOAD ile aynı raf.
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.INVALID_PAYLOAD,
                    "ECB cevabi cok buyuk: " + e.getMessage(), e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TIMEOUT, "ECB zaman asimi", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TRANSPORT, "ECB cagrisi kesildi", e);
        } catch (Exception e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TRANSPORT, "ECB belgesi alinamadi", e);
        }
    }

    /**
     * "Belge bugüne ait değil" bir <b>yayın</b> durumudur, bozuk yük değil — hafta sonu her
     * çağrıda "satıcı bozuk" alarmı üretmemesi için {@code NOT_PUBLISHED} ile işaretlenir.
     */
    private ExchangeRateSnapshot mapOrFail(String xml, String url) {
        try {
            return mapper.toSnapshot(xmlReader.read(xml));
        } catch (EcbRateMapper.StaleDocumentException e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.NOT_PUBLISHED,
                    "ECB bugunku kuru yayinlamamis: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.INVALID_PAYLOAD,
                    "ECB belgesi kullanilamaz durumda: " + url, e);
        }
    }
}
