package com.ohbsy.currencyapi.business.abstracts;

import com.ohbsy.currencyapi.business.concretes.RateResult;

/**
 * Kur okuma kullanım senaryosu. API katmanı yalnız bunu görür; cache, sağlayıcı ve bayat-kur
 * kuralları bu arayüzün ardındadır.
 */
public interface ExchangeRateService {

    /**
     * Güncel kur tablosu.
     *
     * <p><b>İstisna fırlatmaz</b> — elde hiçbir kur yoksa bile: o durum
     * {@link RateResult.Status#UNAVAILABLE} olarak <b>cevabın alanıdır</b>. Tüketiciye 500
     * atmak yerine "kur yok" demek, onun kendi yedek davranışını kurabilmesini sağlar.
     */
    RateResult currentRates();
}
