package com.ohbsy.currencyapi.business.concretes;

import com.ohbsy.currencyapi.business.abstracts.ExchangeRateService;
import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.core.integrations.ExchangeRateProvider;
import com.ohbsy.currencyapi.core.integrations.ProviderUnavailableException;
import com.ohbsy.currencyapi.dataAccess.RateCache;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * <b>Cache-aside + son geçerli kur</b> kuralının TEK yeri.
 *
 * <h2>Üç basamak</h2>
 * <ol>
 *   <li><b>Taze cache</b> ({@code fetchedAt + cache-ttl} henüz geçmediyse) → satıcıya
 *       <b>HİÇ gidilmez</b>. "Her istekte TCMB'ye gitme" kuralının uygulandığı yer burasıdır;
 *       kaç tüketici olursa olsun satıcıya 15 dakikada bir istek gider.</li>
 *   <li><b>Satıcı</b> → çekilir, cache'e yazılır.</li>
 *   <li><b>Satıcı düştü</b> → <b>son geçerli kur</b> sunulur ({@code STALE_CACHE}). TCMB'nin
 *       hafta sonu/tatilde yayın yapmaması bu basamağa düşer ve <b>beklenen</b> durumdur.</li>
 * </ol>
 * Hiçbiri yoksa {@code UNAVAILABLE} — istisna değil, cevabın bir alanı.
 *
 * <h2>Neden merdiven sağlayıcıda değil burada</h2>
 * Her sağlayıcı kendi fallback'ini yazsaydı bayat-kur mantığı sağlayıcı sayısı kadar
 * kopyalanır, hiçbiri tek bir testle doğrulanamaz ve ECB eklendiğinde kural ikinci kez
 * (muhtemelen farklı) yazılırdı. Sağlayıcılar dürüstçe patlar; karar tek yerdedir.
 */
@Service
public class ExchangeRateServiceImpl implements ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateServiceImpl.class);

    private final ExchangeRateProvider provider;
    private final RateCache cache;
    private final CurrencyApiProperties properties;
    private final Clock clock;

    public ExchangeRateServiceImpl(ExchangeRateProvider provider, RateCache cache,
                                   CurrencyApiProperties properties, Clock clock) {
        this.provider = provider;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * <b>Cache yuvası {@code provider.name()}, cevaptaki kaynak {@code snapshot.source()}.</b>
     * İkisi bilinçli olarak farklı yerlerden gelir: yuva "bu tabloyu nereye koyayım" sorusunun
     * cevabıdır ve tektir (bkz. {@code ChainedExchangeRateProvider}); {@code source} ise
     * "bu sayıyı kim yayınladı" sorusunundur ve zincir ECB'ye düştüğünde gerçekten değişir.
     * Yuvadan türetilseydi, ECB'den gelen bir tablo tüketiciye {@code provider: "tcmb"} diye
     * sunulurdu — sessiz ve yakalanamaz bir yanlışlık.
     */
    @Override
    public RateResult currentRates() {
        Optional<ExchangeRateSnapshot> cached = cache.find(provider.name());

        if (cached.isPresent() && isFresh(cached.get())) {
            return new RateResult(cached.get(), RateResult.Status.FRESH_CACHE,
                    cached.get().source());
        }

        try {
            ExchangeRateSnapshot fetched = provider.fetchLatestRates();
            cache.put(provider.name(), fetched);
            return new RateResult(fetched, RateResult.Status.FRESH_PROVIDER, fetched.source());
        } catch (ProviderUnavailableException e) {
            return fallback(cached, e);
        }
    }

    /**
     * Tazelik ölçütü <b>bizim çekme anımızdır</b> ({@code fetchedAt}), satıcının yayın günü
     * değil. Yayın gününe bağlansaydı: TCMB günlük yayınladığı için kayıt <b>yazıldığı gün
     * bitince</b> bayat sayılır, ertesi sabah her istek satıcıya giderdi — ve daha kötüsü,
     * hafta sonu boyunca her istek 404 alan bir satıcıya giderdi.
     */
    private boolean isFresh(ExchangeRateSnapshot snapshot) {
        Instant expiresAt = snapshot.fetchedAt().plus(properties.getCache().getTtl());
        return !expiresAt.isBefore(clock.instant());
    }

    private RateResult fallback(Optional<ExchangeRateSnapshot> cached,
                                ProviderUnavailableException failure) {
        if (cached.isPresent()) {
            // NOT_PUBLISHED (hafta sonu/tatil) burada NORMAL bir yoldur; INFO ile ayrılır ki
            // her cumartesi WARN üreten bir sistem gerçek kesintiyi de görünmez kılmasın.
            if (failure.getReason() == ProviderUnavailableException.Reason.NOT_PUBLISHED) {
                log.info("saglayici yeni veri yayinlamamis, son gecerli kur sunuluyor "
                        + "provider={} rateDate={}", provider.name(), cached.get().rateDate());
            } else {
                log.warn("saglayici erisilemez, son gecerli kur sunuluyor provider={} "
                                + "rateDate={} sebep={} hata={}",
                        provider.name(), cached.get().rateDate(), failure.getReason(),
                        failure.toString());
            }
            return new RateResult(cached.get(), RateResult.Status.STALE_CACHE,
                    cached.get().source());
        }

        log.error("kur yok: saglayici erisilemez ve cache bos provider={} sebep={}",
                provider.name(), failure.getReason(), failure);
        return RateResult.unavailable(provider.name());
    }
}
