package com.ohbsy.currencyapi.core.integrations;

import com.ohbsy.currencyapi.core.integrations.evds.EvdsExchangeRateProvider;
import com.ohbsy.currencyapi.core.integrations.tcmb.TcmbExchangeRateProvider;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Sağlayıcı zinciri:</b> sırayla dener, ilk başarılıyı döner. İş katmanına tek bir
 * sağlayıcı gibi görünür — {@code ExchangeRateServiceImpl} kaç yol olduğunu bilmez.
 *
 * <h2>Öncelik: en yeni → en eski</h2>
 * <ol>
 *   <li><b>Saatlik — YOK.</b> Aranmadığı için değil, <b>olmadığı için</b>: EVDS'in 671 veri
 *       grubunun tamamı tarandı, günden sık tek bir frekans yok (GÜNLÜK/İŞ GÜNÜ/HAFTALIK/
 *       AYLIK...). TCMB'nin "Saat Başı Belirlenen Döviz Kurları" sayfası da veri değil
 *       açıklama sayfasıdır — tarayıcı ağ izinde tek bir veri isteği ve tek bir "USD" metni
 *       yoktur. Bu basamak <b>bilerek boştur</b>; ileride kaynak çıkarsa zincirin başına
 *       eklenir ve altındaki hiçbir şey değişmez.</li>
 *   <li><b>Bugünkü — EVDS.</b> Her satırı kendi günüyle verir; "elimizdeki kur bugünün mü"
 *       sorusunu cevaplayabilen tek yol budur.</li>
 *   <li><b>Bugünkü (yedek) — {@code today.xml}.</b> Anahtar istemez. Sayıları EVDS ile
 *       aynıdır, yalnız tarih etiketi bir gün geriden gelir.</li>
 *   <li><b>Dünkü — burada DEĞİL.</b> Son geçerli kur {@code ExchangeRateServiceImpl}'in
 *       cache basamağıdır ({@code STALE_CACHE}) ve orada kalır: zincir de kendi bayat-kur
 *       mantığını yazsaydı kural iki yerde, muhtemelen farklı biçimde dururdu.</li>
 * </ol>
 *
 * <h2>{@code name()} zincire göre DEĞİŞMEZ</h2>
 * Sağlayıcı adı cache anahtarıdır ({@code RateCache.find(provider.name())}) ve cevaptaki
 * {@code provider} alanıdır. Hangi yolun konuştuğuna göre değişseydi iki gerçek arıza doğardı:
 * her yol değişiminde cache <b>bölünür</b> (aynı kur iki kez tutulur, devreye girişte boşuna
 * satıcıya gidilir), ve tüketicinin gördüğü {@code provider} alanı bizim iç yol seçimimize
 * göre oynardı. Ad {@code tcmb}'de sabittir ve bu <b>dürüsttür</b>: iki yol da aynı kurumun
 * aynı resmî satış kurudur, farklı olan yalnız kapıdır. Hangi kapıdan girildiği log'dadır —
 * orası tanılamanın yeridir, sözleşme değil.
 *
 * <h2>Sıra kodda, anotasyonda değil</h2>
 * Zincir {@code List<ExchangeRateProvider>} enjeksiyonu + {@code @Order} ile de kurulabilirdi.
 * Öncelik bu servisin en kritik kararlarından biridir ve tek bir yerde okunabilir olmalıdır;
 * anotasyonlara dağılmış bir sıra, sıranın ne olduğunu üç dosyayı açmadan söyleyemez.
 */
@Primary
@Component
public class ChainedExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(ChainedExchangeRateProvider.class);

    private final List<ExchangeRateProvider> chain;

    /**
     * {@code @Autowired} burada gereklidir ve kod tabanının geri kalanından ayrışır: sınıfın
     * iki kurucusu vardır (ikincisi testler için) ve Spring, birden fazla kurucu gördüğünde
     * hangisini kullanacağını <b>tahmin etmez</b>. İşaretsiz bırakmak, seçimi sürüme göre
     * değişen bir çıkarım kuralına bırakırdı.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ChainedExchangeRateProvider(EvdsExchangeRateProvider evds,
                                       TcmbExchangeRateProvider tcmb) {
        this(evds.isConfigured() ? List.of(evds, tcmb) : List.of(tcmb));
        if (!evds.isConfigured()) {
            log.info("EVDS anahtari tanimsiz (CURRENCY_EVDS_KEY) — yalniz {} kullanilacak, "
                    + "bulten tarihi bir gun geriden gelebilir", TcmbExchangeRateProvider.NAME);
        }
    }

    private ChainedExchangeRateProvider(List<ExchangeRateProvider> chain) {
        this.chain = chain;
        log.info("kur saglayici zinciri: {}", chain.stream().map(ExchangeRateProvider::name)
                .toList());
    }

    /** Testler için: zinciri doğrudan kurar. Spring bu yolu görmez (kurucu private'tır). */
    static ChainedExchangeRateProvider of(ExchangeRateProvider... providers) {
        return new ChainedExchangeRateProvider(List.of(providers));
    }

    @Override
    public ExchangeRateSnapshot fetchLatestRates() {
        List<ProviderUnavailableException> failures = new ArrayList<>(chain.size());

        for (ExchangeRateProvider provider : chain) {
            try {
                ExchangeRateSnapshot snapshot = provider.fetchLatestRates();
                if (!failures.isEmpty()) {
                    // Yedeğe düşüldü: bu satır olmadan "neden bugünün tarihi yok" sorusu
                    // yalnız kurun kendisine bakarak cevaplanamaz.
                    log.warn("kur yedek yoldan alindi kaynak={} atlanan={}",
                            provider.name(), reasonsOf(failures));
                }
                return snapshot;
            } catch (ProviderUnavailableException e) {
                log.debug("saglayici atlandi kaynak={} sebep={}", provider.name(), e.getReason());
                failures.add(e);
            }
        }

        throw combined(failures);
    }

    @Override
    public String name() {
        return TcmbExchangeRateProvider.NAME;
    }

    /**
     * Zincirin tamamı düştüğünde <b>hangi</b> sebebin yukarı çıkacağı bir alarm kararıdır.
     *
     * <p>Hepsi {@code NOT_PUBLISHED} ise sonuç da odur — yoksa her hafta sonu "satıcı
     * erişilemez" WARN'ı üreten bir sistem, gerçek kesintiyi de görünmez kılardı. Aksi halde
     * <b>ilk gerçek arıza</b> yukarı çıkar: takvim her zaman en zararsız açıklamadır ve onu
     * öne almak, düzeltilmesi gereken bir yapılandırma hatasını (ör. süresi dolmuş EVDS
     * anahtarı) tatil gürültüsünün altına gömerdi. Diğer sebepler {@code suppressed} olarak
     * taşınır; hiçbiri kaybolmaz.
     */
    private ProviderUnavailableException combined(List<ProviderUnavailableException> failures) {
        ProviderUnavailableException primary = failures.stream()
                .filter(e -> e.getReason() != ProviderUnavailableException.Reason.NOT_PUBLISHED)
                .findFirst()
                .orElse(failures.get(0));

        ProviderUnavailableException combined = new ProviderUnavailableException(
                primary.getReason(),
                "kur saglayici zincirinin tamami dustu: " + reasonsOf(failures),
                primary);
        failures.stream().filter(e -> e != primary).forEach(combined::addSuppressed);
        return combined;
    }

    private List<ProviderUnavailableException.Reason> reasonsOf(
            List<ProviderUnavailableException> failures) {
        return failures.stream().map(ProviderUnavailableException::getReason).toList();
    }
}
