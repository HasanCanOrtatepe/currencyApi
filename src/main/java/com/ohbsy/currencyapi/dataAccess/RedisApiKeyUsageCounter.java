package com.ohbsy.currencyapi.dataAccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Redis destekli birikmeli sayaç — <b>paylaşılan</b> olması gerekir.
 *
 * <p>Bellekte tutulsaydı sayım instance sayısına bölünürdü: public instance'a giden istek
 * bir yerde, admin instance'ının gördüğü toplam başka yerde birikir ve panel trafiğin yalnız
 * bir kısmını gösterirdi. {@code RateLimiter} ile birebir aynı gerekçe.
 *
 * <h2>Anahtar düzeni — {@code KEYS} taraması YOK</h2>
 * <pre>
 * currency:apikeys:usage:&lt;id&gt;:total          → TTL YOK  (ömür boyu toplam)
 * currency:apikeys:usage:&lt;id&gt;:d:&lt;yyyy-MM-dd&gt;  → TTL 40g (günlük)
 * </pre>
 * Günlük anahtar kendi kendini toplar: gün değişince yeni anahtar yazılır, eskisi süresi
 * dolunca <b>kendiliğinden silinir</b>. Temizlik işi, unutulabilecek bir bakım görevi değil
 * Redis'in kendi işidir.
 *
 * <p>Okuma tek {@code MGET} ile yapılır: panel 10 saniyede bir <i>her satır için</i> okuma
 * yapar; iki ayrı {@code GET}, anahtar sayısı kadar gereksiz gidiş-dönüş demekti.
 */
@Component
@ConditionalOnProperty(name = "currency-api.cache.type", havingValue = "redis")
public class RedisApiKeyUsageCounter implements ApiKeyUsageCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisApiKeyUsageCounter.class);

    private static final String KEY_PREFIX = "currency:apikeys:usage:";

    /**
     * Günlük kayıtların saklama süresi. Bir aylık geriye dönük bakışı rahatça kapsar; sayı
     * panelde yalnız "bugün" olarak gösterilse de kaydın hemen silinmemesi, gece yarısını
     * geçen bir incelemeyi kurtarır.
     */
    private static final Duration DAILY_RETENTION = Duration.ofDays(40);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public RedisApiKeyUsageCounter(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public void record(String keyId) {
        if (keyId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().increment(totalKey(keyId));

            String dayKey = dailyKey(keyId, today());
            Long count = redisTemplate.opsForValue().increment(dayKey);
            if (count != null && count == 1L) {
                // TTL yalnız ilk artışta kurulur; her istekte EXPIRE çağırmak günü kaydırırdı.
                redisTemplate.expire(dayKey, DAILY_RETENTION);
            }
        } catch (Exception e) {
            // FAIL-OPEN: bu bir gösterge alanıdır, isteği düşürmemelidir.
            log.warn("kullanim sayaci yazilamadi id={} sebep={}", keyId, e.toString());
        }
    }

    @Override
    public Usage of(String keyId) {
        if (keyId == null) {
            return Usage.none();
        }
        try {
            List<String> values = redisTemplate.opsForValue()
                    .multiGet(List.of(totalKey(keyId), dailyKey(keyId, today())));
            if (values == null || values.size() != 2) {
                return Usage.none();
            }
            return new Usage(parse(values.get(1)), parse(values.get(0)));
        } catch (Exception e) {
            log.warn("kullanim sayaci okunamadi id={} sebep={}", keyId, e.toString());
            return Usage.none();
        }
    }

    @Override
    public String kind() {
        return "redis";
    }

    private String totalKey(String keyId) {
        return KEY_PREFIX + keyId + ":total";
    }

    private String dailyKey(String keyId, LocalDate day) {
        return KEY_PREFIX + keyId + ":d:" + day;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(DAY_ZONE));
    }

    /** Bozuk bir değer sayfayı düşürmez: okunamayan sayaç 0 sayılır. */
    private long parse(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
