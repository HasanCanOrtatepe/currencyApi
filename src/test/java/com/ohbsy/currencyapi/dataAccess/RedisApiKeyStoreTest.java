package com.ohbsy.currencyapi.dataAccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohbsy.currencyapi.entities.ApiKeyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis destekli anahtar deposunun <b>davranışı</b> — Redis'in kendisi değil.
 *
 * <p>Bu sınıfın testi uzun süre yoktu ve gerekçesi "altyapı gerektirir"di. Gerektirmiyor:
 * sınanması gereken şey Redis'in çalışıp çalışmadığı değil, <b>bizim</b> kararlarımızdır —
 * anahtar düzeni, KEYS taraması yapılmaması ve en önemlisi {@code findByHash}'in
 * FAIL-CLOSED olması. Bunların hepsi {@link StringRedisTemplate} taklidiyle sınanabilir ve
 * böylece testler altyapısız kalır (projenin değişmez kuralı).
 */
@DisplayName("RedisApiKeyStore — anahtar düzeni ve fail-closed davranış")
class RedisApiKeyStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private ObjectMapper objectMapper;
    private RedisApiKeyStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        store = new RedisApiKeyStore(redis, objectMapper);
    }

    private ApiKeyRecord record() {
        return new ApiKeyRecord("id-1", "crm", "hash-1", "cur_ab…yz", NOW, null, 42, null);
    }

    @Test
    @DisplayName("Yeni kayıt: id, byhash ve index'in ÜÇÜ birden yazılır")
    void newRecordWritesAllThreeKeys() throws Exception {
        when(redis.hasKey("currency:apikeys:id:id-1")).thenReturn(false);

        store.save(record());

        verify(values).set("currency:apikeys:id:id-1", objectMapper.writeValueAsString(record()));
        verify(values).set("currency:apikeys:byhash:hash-1", "id-1");
        verify(sets).add("currency:apikeys:index", "id-1");
    }

    /**
     * Hash ve id bir kaydın ömrü boyunca değişmez; iptal/lastUsedAt güncellemesinde ikincil
     * indeksi yeniden yazmak gereksiz yazma üretirdi.
     */
    @Test
    @DisplayName("Mevcut kayıt güncellenirken ikincil indeks YENİDEN yazılmaz")
    void updateOnlyRewritesIdKey() {
        when(redis.hasKey("currency:apikeys:id:id-1")).thenReturn(true);

        store.save(record().revoked(NOW));

        verify(values).set(anyString(), anyString());
        verify(values, never()).set("currency:apikeys:byhash:hash-1", "id-1");
        verify(sets, never()).add(anyString(), anyString());
    }

    @Test
    @DisplayName("findByHash ikincil indeksi kullanır (tarama YOK)")
    void findByHashUsesSecondaryIndex() throws Exception {
        when(values.get("currency:apikeys:byhash:hash-1")).thenReturn("id-1");
        when(values.get("currency:apikeys:id:id-1"))
                .thenReturn(objectMapper.writeValueAsString(record()));

        assertThat(store.findByHash("hash-1")).contains(record());
        verify(redis, never()).keys(anyString());
    }

    /**
     * BURASI KOD TABANININ TEK FAIL-CLOSED YERİ. Redis erişilemezken "bulunamadı" denmesi
     * çağıranın isteği 401'lemesini sağlar; fail-open olsaydı "anahtarı doğrulayamıyorsam
     * geçir" demiş olurduk ve yetkilendirme çökerdi.
     */
    @Test
    @DisplayName("Redis patlarsa findByHash BOŞ döner (fail-CLOSED, istek 401 alır)")
    void findByHashFailsClosed() {
        when(values.get(anyString())).thenThrow(new RuntimeException("redis erisilemez"));

        assertThat(store.findByHash("hash-1")).isEmpty();
    }

    @Test
    @DisplayName("Bozuk JSON kaydı sessizce yok sayılır, istisna sızmaz")
    void corruptRecordIsIgnored() {
        when(values.get("currency:apikeys:byhash:hash-1")).thenReturn("id-1");
        when(values.get("currency:apikeys:id:id-1")).thenReturn("{bozuk-json");

        assertThat(store.findByHash("hash-1")).isEmpty();
    }

    /**
     * {@code save()} admin işlemidir: sessizce "başarılı" görünüp aslında kalıcı olmaması,
     * görünür bir hatadan kötüdür. Çağıran (controller) bunu 503'e çevirir.
     */
    @Test
    @DisplayName("save() istisnayı YUTMAZ — admin görünür hata almalı")
    void savePropagatesFailure() {
        when(redis.hasKey(anyString())).thenReturn(false);
        // set(...) void doner: when(...) derlenmez, doThrow kullanilir.
        doThrow(new RuntimeException("yazilamadi")).when(values).set(anyString(), anyString());

        assertThatThrownBy(() -> store.save(record()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("findAll SMEMBERS ile okur — KEYS taraması YAPMAZ")
    void findAllUsesIndexSetNotKeysScan() throws Exception {
        when(sets.members("currency:apikeys:index")).thenReturn(Set.of("id-1"));
        when(values.get("currency:apikeys:id:id-1"))
                .thenReturn(objectMapper.writeValueAsString(record()));

        assertThat(store.findAll()).containsExactly(record());
        verify(redis, never()).keys(anyString());
    }

    @Test
    @DisplayName("Index boşsa findAll boş liste döner")
    void findAllHandlesEmptyIndex() {
        when(sets.members("currency:apikeys:index")).thenReturn(Set.of());

        assertThat(store.findAll()).isEmpty();
    }

    @Test
    @DisplayName("findAll Redis hatasında boş liste döner, istisna sızmaz")
    void findAllSurvivesRedisFailure() {
        when(sets.members(anyString())).thenThrow(new RuntimeException("redis erisilemez"));

        assertThat(store.findAll()).isEmpty();
    }

    @Test
    @DisplayName("kind() 'redis' döner")
    void kindIsRedis() {
        assertThat(store.kind()).isEqualTo("redis");
    }

    /** Kayıt Redis'e JSON olarak yazılıp geri okunabilmelidir (Instant alanları dahil). */
    @Test
    @DisplayName("Kayıt JSON'a yazılıp geri okunabilir")
    void recordSurvivesJsonRoundTrip() throws Exception {
        ApiKeyRecord original = record().withLastUsedAt(NOW).revoked(NOW);

        String json = objectMapper.writeValueAsString(original);

        assertThat(objectMapper.readValue(json, ApiKeyRecord.class)).isEqualTo(original);
    }
}
