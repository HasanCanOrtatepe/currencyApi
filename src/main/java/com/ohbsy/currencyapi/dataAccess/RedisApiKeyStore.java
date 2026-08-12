package com.ohbsy.currencyapi.dataAccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis destekli anahtar deposu — çok instance'lı kurulumun gerektirdiği uygulama (anahtar
 * kümesi paylaşılan bir durumdur, {@link RedisRateCache} ile aynı gerekçe).
 *
 * <h2>Anahtar düzeni — KEYS taraması YOK</h2>
 * <pre>
 * currency:apikeys:id:&lt;id&gt;      → JSON ApiKeyRecord
 * currency:apikeys:byhash:&lt;hash&gt; → &lt;id&gt;   (ikincil indeks, oluşturmada bir kez yazılır)
 * currency:apikeys:index          → tüm id'lerin SET'i (findAll için)
 * </pre>
 * Hash ve id bir kaydın ömrü boyunca değişmez, bu yüzden {@code byhash} girdisi ve {@code
 * index} üyeliği yalnız {@link #save} ilk çağrıldığında (oluşturma) yazılır; sonraki {@code
 * save} çağrıları (iptal, {@code lastUsedAt}) yalnız {@code id} kaydını üzerine yazar.
 *
 * <h2>{@link #findByHash} FAIL-CLOSED'dır — bkz. {@link ApiKeyStore} sınıf yorumu</h2>
 * Diğer Redis-destekli sınıflardan (fail-open) kasıtlı olarak farklıdır: burada Redis
 * doğrulamanın kaynağıdır, hızlandırıcısı değil. Hata seviyesi bilinçli olarak {@code ERROR}
 * (kod tabanındaki fail-open {@code WARN} loglarından görsel olarak ayrışsın diye).
 */
@Component
@ConditionalOnProperty(name = "currency-api.cache.type", havingValue = "redis")
public class RedisApiKeyStore implements ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisApiKeyStore.class);
    private static final String KEY_PREFIX = "currency:apikeys:";
    private static final String INDEX_KEY = KEY_PREFIX + "index";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisApiKeyStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(ApiKeyRecord record) {
        // save() exception YUTMAZ: admin'in bir yazma isteği sessizce "başarılı" görünüp
        // aslında kalıcı olmaması, görünür şekilde hata vermesinden daha kötüdür. Çağıran
        // (AdminApiKeyController) bunu yakalayıp 503'e çevirir.
        String idKey = idKey(record.id());
        boolean isNew = !Boolean.TRUE.equals(redisTemplate.hasKey(idKey));
        try {
            redisTemplate.opsForValue().set(idKey, objectMapper.writeValueAsString(record));
            if (isNew) {
                redisTemplate.opsForValue().set(hashKey(record.keyHash()), record.id());
                redisTemplate.opsForSet().add(INDEX_KEY, record.id());
            }
        } catch (Exception e) {
            throw new IllegalStateException("api anahtari yazilamadi id=" + record.id(), e);
        }
    }

    @Override
    public Optional<ApiKeyRecord> findByHash(String keyHash) {
        try {
            String id = redisTemplate.opsForValue().get(hashKey(keyHash));
            if (id == null) {
                return Optional.empty();
            }
            return readById(id);
        } catch (Exception e) {
            // FAIL-CLOSED — bkz. ApiKeyStore sinif yorumu: burada Redis dogrulamanin
            // KAYNAGIDIR, hizlandiricisi degil. Gecirmek yetkilendirmeyi yok ederdi.
            log.error("api anahtari dogrulanamadi (Redis erisilemez), ISTEK REDDEDILECEK "
                    + "sebep={}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public List<ApiKeyRecord> findAll() {
        try {
            Set<String> ids = redisTemplate.opsForSet().members(INDEX_KEY);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            return ids.stream()
                    .map(this::readById)
                    .flatMap(Optional::stream)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("api anahtari listesi okunamadi sebep={}", e.toString());
            return List.of();
        }
    }

    @Override
    public Optional<ApiKeyRecord> findById(String id) {
        try {
            return readById(id);
        } catch (Exception e) {
            log.error("api anahtari okunamadi id={} sebep={}", id, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public String kind() {
        return "redis";
    }

    private Optional<ApiKeyRecord> readById(String id) {
        String json = redisTemplate.opsForValue().get(idKey(id));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ApiKeyRecord.class));
        } catch (Exception e) {
            log.error("api anahtari kaydi bozuk id={} sebep={}", id, e.toString());
            return Optional.empty();
        }
    }

    private String idKey(String id) {
        return KEY_PREFIX + "id:" + id;
    }

    private String hashKey(String hash) {
        return KEY_PREFIX + "byhash:" + hash;
    }
}
