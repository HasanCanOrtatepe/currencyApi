package com.ohbsy.currencyapi.dataAccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Birikmeli sayacın Redis uygulaması — sınanan şey Redis değil <b>bizim kararlarımızdır</b>:
 * anahtar düzeni, TTL'in yalnız günlük sayaca ve yalnız ilk artışta kurulması, tek {@code MGET}
 * ile okuma ve fail-open davranış.
 */
@DisplayName("RedisApiKeyUsageCounter — anahtar düzeni, TTL ve fail-open")
class RedisApiKeyUsageCounterTest {

    /** 14 Ağustos 2026, TSİ 11:21. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-14T08:21:00Z"), ZoneOffset.UTC);

    private static final String TOTAL_KEY = "currency:apikeys:usage:anahtar-1:total";
    private static final String DAY_KEY = "currency:apikeys:usage:anahtar-1:d:2026-08-14";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisApiKeyUsageCounter counter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        counter = new RedisApiKeyUsageCounter(redis, FIXED);
    }

    @Test
    @DisplayName("İki sayaç artırılır: ömür boyu toplam + o günün anahtarı")
    void incrementsBothCounters() {
        when(values.increment(anyString())).thenReturn(1L);

        counter.record("anahtar-1");

        verify(values).increment(TOTAL_KEY);
        verify(values).increment(DAY_KEY);
    }

    /**
     * TTL yalnız İLK artışta kurulur. Her istekte {@code EXPIRE} çağrılsaydı gün penceresi
     * sürekli ileri kayar ve kayıt hiç düşmezdi.
     */
    @Test
    @DisplayName("Günlük anahtarın TTL'i YALNIZ ilk artışta kurulur")
    void setsDailyTtlOnlyOnFirstIncrement() {
        when(values.increment(TOTAL_KEY)).thenReturn(7L);
        when(values.increment(DAY_KEY)).thenReturn(1L);
        counter.record("anahtar-1");
        verify(redis).expire(DAY_KEY, Duration.ofDays(40));

        when(values.increment(DAY_KEY)).thenReturn(2L);
        counter.record("anahtar-1");

        verify(redis, never()).expire(TOTAL_KEY, Duration.ofDays(40));
        verify(redis).expire(anyString(), any(Duration.class));   // hâlâ tek çağrı
    }

    /**
     * Toplam sayaç <b>süresiz</b>dir: "bu anahtar hiç kullanıldı mı" sorusu, kullanımın
     * üstünden kaç gün geçtiğinden bağımsız olarak cevaplanabilmelidir.
     */
    @Test
    @DisplayName("Toplam sayaca TTL KONMAZ")
    void totalNeverExpires() {
        when(values.increment(anyString())).thenReturn(1L);

        counter.record("anahtar-1");

        verify(redis, never()).expire(org.mockito.ArgumentMatchers.eq(TOTAL_KEY),
                any(Duration.class));
    }

    /**
     * Panel 10 saniyede bir <i>her satır için</i> okur; iki ayrı {@code GET}, anahtar sayısı
     * kadar gereksiz gidiş-dönüş demekti.
     */
    @Test
    @DisplayName("Okuma tek MGET ile yapılır ve doğru sırayla eşlenir")
    void readsWithSingleMultiGet() {
        when(values.multiGet(List.of(TOTAL_KEY, DAY_KEY)))
                .thenReturn(List.of("250", "12"));

        ApiKeyUsageCounter.Usage usage = counter.of("anahtar-1");

        assertThat(usage.total()).isEqualTo(250);
        assertThat(usage.today()).isEqualTo(12);
    }

    /** Hiç kullanılmamış anahtarda Redis {@code null} döner — sıfır sayılır, patlanmaz. */
    @Test
    @DisplayName("Eksik değerler sıfır sayılır")
    void missingValuesAreZero() {
        when(values.multiGet(any())).thenReturn(Arrays.asList(null, null));

        assertThat(counter.of("anahtar-1")).isEqualTo(ApiKeyUsageCounter.Usage.none());
    }

    @Test
    @DisplayName("Bozuk değer sayfayı düşürmez, sıfır sayılır")
    void corruptValueIsZero() {
        when(values.multiGet(any())).thenReturn(Arrays.asList("bozuk", "3"));

        assertThat(counter.of("anahtar-1").total()).isZero();
        assertThat(counter.of("anahtar-1").today()).isEqualTo(3);
    }

    /**
     * <b>FAIL-OPEN, istisnasız:</b> bu bir gösterge alanıdır. Yazma hatası isteği düşürseydi,
     * Redis kesintisinde servis kur sunamaz hâle gelirdi — sırf bir sayaç yüzünden.
     */
    @Test
    @DisplayName("Redis erişilemezken record() İSTİSNA FIRLATMAZ")
    void recordFailsOpen() {
        when(values.increment(anyString())).thenThrow(new RuntimeException("redis erisilemez"));

        counter.record("anahtar-1");   // patlamamalı
    }

    @Test
    @DisplayName("Redis erişilemezken of() sıfır döner — panel çalışmaya devam eder")
    void readFailsOpen() {
        when(values.multiGet(any())).thenThrow(new RuntimeException("redis erisilemez"));

        assertThat(counter.of("anahtar-1")).isEqualTo(ApiKeyUsageCounter.Usage.none());
    }

    /** Statik anahtar / anonim istek: sayılacak bir satır yok, Redis'e hiç gidilmemeli. */
    @Test
    @DisplayName("null kimlik Redis'e HİÇ gitmez")
    void nullKeyTouchesNothing() {
        counter.record(null);

        assertThat(counter.of(null)).isEqualTo(ApiKeyUsageCounter.Usage.none());
        verify(values, never()).increment(anyString());
        verify(values, never()).multiGet(any());
    }

    @Test
    @DisplayName("kind() redis döner")
    void reportsKind() {
        assertThat(counter.kind()).isEqualTo("redis");
    }
}
