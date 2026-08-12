package com.ohbsy.currencyapi.core.integrations;

import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;

/**
 * <b>Kur sağlayıcısı soyutlaması.</b> Servisin dış dünyayla tek sözleşmesi budur: iş katmanı ve
 * API, kurun TCMB'den mi ECB'den mi geldiğini <b>bilmez</b>.
 *
 * <h2>Yeni sağlayıcı eklemek</h2>
 * {@code core/integrations/<satıcı>/} altında bu arayüzü uygulayan bir sınıf + kendi
 * DTO'su + kendi mapper'ı. Satıcının tel formatı (XML/JSON, alan adları, kur yönü, birim
 * çarpanı) <b>o paketten dışarı çıkmaz</b>; dışarı çıkan şey {@link ExchangeRateSnapshot}'tır.
 * İş katmanı, cache ve API sözleşmesi değişmez.
 *
 * <h2>Bu arayüz İSTİSNA FIRLATIR — ve bu bilinçlidir</h2>
 * Sağlayıcı ham kaynaktır ve dürüstçe patlar ({@link ProviderUnavailableException}). "Kur
 * alınamazsa son geçerli kuru kullan" kuralı <b>burada değil</b> servis katmanındadır: her
 * sağlayıcı kendi fallback'ini icat etseydi, bayat-kur mantığı sağlayıcı sayısı kadar
 * kopyalanır ve hiçbiri tek bir testle doğrulanamazdı.
 */
public interface ExchangeRateProvider {

    /**
     * Sağlayıcıdan güncel kur tablosunu çeker.
     *
     * @throws ProviderUnavailableException kaynak erişilemez ya da cevabı kullanılamazsa
     */
    ExchangeRateSnapshot fetchLatestRates();

    /** Tanılama ve cevap alanı: hangi kaynak konuştu ({@code tcmb}, {@code ecb}, ...). */
    String name();
}
