package com.ohbsy.currencyapi.core.integrations.evds;

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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * <b>EVDS sağlayıcısı — TCMB'nin veri dağıtım sistemi.</b> Kaynak {@code today.xml} ile aynı
 * kurumdur ({@code TP.DK.*.S} = resmî döviz satış kuru), farklı olan <b>erişim yoludur</b>.
 *
 * <h2>Neden ikinci bir TCMB yolu</h2>
 * {@code today.xml}'in {@code Tarih} özniteliği bir gün geriden gelir; içindeki sayılar
 * bugünün sayıları olsa bile belge "dünü" gösterir. Vitrinde "bugün 14'ü, kur 13.08" yazması
 * bundandır. EVDS her satırı kendi günüyle verir, yani <b>"elimizdeki kur bugünün mü"
 * sorusunu cevaplayabilen tek yol budur.</b> Sayılar iki kaynakta aynıdır (ölçüldü) —
 * kazanılan şey doğru tarihtir, farklı bir kur değil.
 *
 * <h2>Anahtar başlıkta gider, sorgu dizesinde DEĞİL</h2>
 * EVDS anahtarı {@code key} <b>HTTP başlığında</b> bekler; sorgu dizesine konursa
 * {@code 403} döner. Bu bizim için ayrıca iyidir: URL'ler log'a, hata mesajına ve stack
 * trace'e girer — anahtar orada olsaydı her tanılama satırı bir sır sızıntısı olurdu.
 * Bu sınıf URL'i serbestçe loglayabilir, çünkü URL'de sır yoktur.
 *
 * <h2>Anahtar yoksa bu sağlayıcı sessizce devre dışıdır</h2>
 * {@link #isConfigured()} false ise {@code ChainedExchangeRateProvider} onu zincire hiç
 * koymaz — servis anahtarsız kurulumda bugünkü davranışının aynısını sürdürür. Tek anahtar
 * yeter: EVDS'in kendisi de düşse zincir {@code today.xml}'e iner.
 */
@Component
public class EvdsExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(EvdsExchangeRateProvider.class);

    public static final String NAME = "evds";

    /** Satıcının seri yolu. Sorgu {@code ?} ile DEĞİL, doğrudan {@code series=} ile başlar. */
    static final String SERIES_PATH = "/igmevdsms-dis/series=";

    private static final DateTimeFormatter EVDS_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    /** TCMB günü Türkiye takvimiyle yayınlar. */
    private static final ZoneId TCMB_ZONE = ZoneId.of("Europe/Istanbul");

    private final CurrencyApiProperties properties;
    private final EvdsJsonReader jsonReader;
    private final EvdsRateMapper mapper;
    private final Clock clock;
    private final HttpClient httpClient;

    public EvdsExchangeRateProvider(CurrencyApiProperties properties,
                                    EvdsJsonReader jsonReader,
                                    EvdsRateMapper mapper,
                                    Clock clock) {
        this.properties = properties;
        this.jsonReader = jsonReader;
        this.mapper = mapper;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getEvds().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Anahtar tanımlı mı — zincire alınıp alınmayacağını bu belirler. */
    public boolean isConfigured() {
        return !properties.getEvds().getKey().isBlank();
    }

    @Override
    public ExchangeRateSnapshot fetchLatestRates() {
        String url = seriesUrl();
        ExchangeRateSnapshot snapshot = mapOrFail(fetchDocument(url), url);
        log.info("EVDS kurlari cekildi rateDate={} currencies={}",
                snapshot.rateDate(), snapshot.availableCurrencies());
        return snapshot;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Tek gün değil <b>aralık</b> sorulur: hafta sonu, resmî tatil ve bültenin henüz
     * yayınlanmadığı sabah saatlerinde tek gün sorgusu boş döner. Pencere en uzun bayram
     * zincirini kapsayacak kadar geniştir; maliyeti birkaç yüz bayttır.
     */
    String seriesUrl() {
        LocalDate today = LocalDate.now(clock.withZone(TCMB_ZONE));
        LocalDate from = today.minusDays(properties.getEvds().getLookback().toDays());

        String series = EvdsRateMapper.quotedCurrencies()
                .map(EvdsRateMapper::seriesNameOf)
                .collect(Collectors.joining("-"));

        return properties.getEvds().getBaseUrl() + SERIES_PATH + series
                + "&startDate=" + from.format(EVDS_DATE)
                + "&endDate=" + today.format(EVDS_DATE)
                + "&type=json";
    }

    private String fetchDocument(String url) {
        if (!isConfigured()) {
            // Zincir bu sağlayıcıyı hiç çağırmamalıydı; yine de sessizce "veri yok" demek
            // yerine sebebi söyleyerek patlıyoruz.
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.UNAUTHORIZED, "EVDS anahtari tanimsiz");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.getEvds().getReadTimeout())
                    .header("key", properties.getEvds().getKey())
                    .header("User-Agent", "crm-currency-api/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                // Cevap gövdesi TAŞINMAZ: hatalı anahtarı yankılayan bir satıcı olsaydı
                // anahtar log'a bizim elimizle girerdi.
                throw new ProviderUnavailableException(
                        ProviderUnavailableException.Reason.UNAUTHORIZED,
                        "EVDS anahtari kabul edilmedi (HTTP " + response.statusCode() + ")");
            }
            if (response.statusCode() == 404) {
                throw new ProviderUnavailableException(
                        ProviderUnavailableException.Reason.NOT_PUBLISHED,
                        "EVDS serisi bulunamadi: " + url);
            }
            if (response.statusCode() != 200) {
                throw new ProviderUnavailableException(
                        ProviderUnavailableException.Reason.TRANSPORT,
                        "EVDS HTTP " + response.statusCode());
            }
            return response.body();

        } catch (ProviderUnavailableException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TIMEOUT, "EVDS zaman asimi", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TRANSPORT, "EVDS cagrisi kesildi", e);
        } catch (Exception e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.TRANSPORT, "EVDS cevabi alinamadi", e);
        }
    }

    /**
     * "Aralıkta kullanılabilir gün yok" bir <b>yayın</b> durumudur, bozuk yük değil: uzun bir
     * tatil penceresi aşarsa cevap biçimsel olarak kusursuz ama boştur.
     * {@link ProviderUnavailableException.Reason#NOT_PUBLISHED} ile işaretlenir ki servis
     * katmanı bunu INFO ile geçsin, her tatilde "satıcı bozuk" alarmı üretmesin.
     */
    private ExchangeRateSnapshot mapOrFail(String json, String url) {
        try {
            return mapper.toSnapshot(jsonReader.read(json));
        } catch (EvdsRateMapper.NoPublishedDayException e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.NOT_PUBLISHED,
                    "EVDS araliginda yayinlanmis gun yok: " + url, e);
        } catch (IllegalArgumentException e) {
            throw new ProviderUnavailableException(
                    ProviderUnavailableException.Reason.INVALID_PAYLOAD,
                    "EVDS cevabi kullanilamaz durumda: " + url, e);
        }
    }
}
