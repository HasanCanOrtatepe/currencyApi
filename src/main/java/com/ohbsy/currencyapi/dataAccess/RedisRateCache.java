package com.ohbsy.currencyapi.dataAccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Redis destekli cache — <b>çok instance'lı kurulumun gerektirdiği uygulama</b>.
 *
 * <p>Bellekte tutulsaydı her instance kendi kurunu çeker (satıcı isteği instance sayısıyla
 * çarpılır) ve iki instance <b>farklı kur</b> döndürebilirdi; aynı kullanıcı sayfayı
 * yenilediğinde tutarın değişmesi bundan doğardı. Kur paylaşılan bir durumdur, dolayısıyla
 * paylaşılan bir yerde durmalıdır.
 *
 * <h2>Anahtar sağlayıcı adını taşır</h2>
 * {@code currency:rates:<provider>} — ileride ECB eklendiğinde iki kaynağın tabloları
 * birbirinin üzerine yazmasın. Sağlayıcı başına ayrı girdi, sağlayıcı başına ayrı tazelik
 * demektir; tek anahtar kullanılsaydı ECB'ye düşen bir cevap TCMB'nin kaydını ezerdi.
 *
 * <h2>TTL = retention, tazelik DEĞİL</h2>
 * Redis TTL'i {@code retention}'dır ({@code cache-ttl} değil): kayıt tazelik penceresi
 * dolduğunda <b>silinmez</b>, "son geçerli kur" olarak durmaya devam eder. Tazelik yargısı
 * okuma anında {@code fetchedAt} ile verilir.
 */
@Component
@ConditionalOnProperty(name = "currency-api.cache.type", havingValue = "redis")
public class RedisRateCache implements RateCache {

    private static final Logger log = LoggerFactory.getLogger(RedisRateCache.class);
    private static final String KEY_PREFIX = "currency:rates:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CurrencyApiProperties properties;

    public RedisRateCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                          CurrencyApiProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<ExchangeRateSnapshot> find(String providerName) {
        String key = key(providerName);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ExchangeRateSnapshot.class));
        } catch (Exception e) {
            // Fail-open: bozuk kayıt ya da Redis kesintisi cache-miss'tir, arıza değil.
            log.warn("kur cache okunamadi, miss sayiliyor key={} sebep={}", key, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String providerName, ExchangeRateSnapshot snapshot) {
        String key = key(providerName);
        try {
            redisTemplate.opsForValue().set(key,
                    objectMapper.writeValueAsString(snapshot),
                    properties.getCache().getRetention());
        } catch (Exception e) {
            log.warn("kur cache yazilamadi, atlaniyor key={} sebep={}", key, e.toString());
        }
    }

    @Override
    public String kind() {
        return "redis";
    }

    private String key(String providerName) {
        return KEY_PREFIX + providerName;
    }
}
