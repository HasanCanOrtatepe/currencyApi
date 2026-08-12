package com.ohbsy.currencyapi.business.abstracts;

import com.ohbsy.currencyapi.business.concretes.ApiKeyCreationResult;
import com.ohbsy.currencyapi.business.concretes.ApiKeyUsageView;

import java.util.List;

/**
 * Dinamik API anahtarı yönetimi kullanım senaryosu. Admin controller'ı yalnız bunu görür;
 * hash'leme, depolama ve hız-sınırı entegrasyonu bu arayüzün ardındadır.
 */
public interface ApiKeyService {

    /**
     * Yeni bir anahtar oluşturur. Dönen sonuçtaki ham anahtar {@code rawKey} yalnız BU çağrının
     * cevabında görünür — depoda yalnız hash'i tutulur, bir daha hiçbir yerden okunamaz.
     *
     * @param consumerName      log/metrik kimliği (boş olamaz)
     * @param rateLimitOverride {@code null} ise global varsayılan limit geçerli olur
     */
    ApiKeyCreationResult create(String consumerName, Integer rateLimitOverride);

    /** Aktif VE iptal edilmiş tüm kayıtlar, her biri anlık hız-sınırı kullanımıyla birlikte. */
    List<ApiKeyUsageView> list();

    /** @return bilinmeyen {@code id} için {@code false} (controller bunu 404'e çevirir) */
    boolean revoke(String id);

    /**
     * Anahtarın hız sınırını değiştirir. {@code null} = global varsayılana dön.
     *
     * <p>Anahtarın kendisi DEĞİŞMEZ: tüketicinin elindeki değer çalışmaya devam eder. Limit
     * değiştirmek için anahtarı iptal edip yenisini üretmek gerekseydi, her kota ayarı
     * tüketiciye yeni anahtar dağıtmayı gerektirirdi.
     *
     * @return bilinmeyen {@code id} için {@code false}
     */
    boolean updateRateLimit(String id, Integer rateLimitOverride);
}
