package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * API anahtarını <b>tüketici kimliğine</b> çevirir.
 *
 * <h2>Neden anahtar değil "kimlik" döner</h2>
 * Anahtarın kendisi log'a, metriğe ve hata mesajına <b>girmez</b>. "Hangi tüketici kotayı
 * doldurdu" sorusu bir ada (`crm`, `reporting`) bakarak cevaplanabilmelidir; anahtara bakarak
 * cevaplanan bir sistem, o soruyu her soruşta sırrı bir yere daha yazar.
 */
@Component
public class ApiClientResolver {

    private static final Logger log = LoggerFactory.getLogger(ApiClientResolver.class);

    /** Ticari kur API'lerinin yaygın başlığı — tüketiciler için tanıdık olsun. */
    public static final String API_KEY_HEADER = "X-API-Key";

    private final CurrencyApiProperties properties;

    public ApiClientResolver(CurrencyApiProperties properties) {
        this.properties = properties;
    }

    /**
     * Yanlış yapılandırma <b>açılışta ve gürültülü</b> düşer: kimlik doğrulama açık ama hiç
     * anahtar tanımlı değilse servis her isteği 401'leyerek "çalışıyor" görünürdü — sessiz ve
     * teşhisi zor bir arıza. Ayağa kalkmaması, yanlış çalışmasından iyidir.
     */
    @PostConstruct
    void validateConfiguration() {
        if (properties.getAuth().isEnabled() && properties.getAuth().getKeys().isEmpty()) {
            throw new IllegalStateException(
                    "currency-api.auth.enabled=true ama hic anahtar tanimli degil "
                            + "(currency-api.auth.keys). Anahtarlar ortam degiskeninden verilir.");
        }
        if (properties.getAuth().isEnabled()) {
            log.info("API anahtari dogrulamasi ACIK, tanimli tuketici sayisi={}",
                    properties.getAuth().getKeys().size());
        } else {
            log.info("API anahtari dogrulamasi KAPALI (currency-api.auth.enabled=false)");
        }
    }

    public boolean isAuthEnabled() {
        return properties.getAuth().isEnabled();
    }

    /** İstekteki anahtara karşılık gelen tüketici adı; anahtar yok/tanınmıyorsa boş. */
    public Optional<String> resolve(HttpServletRequest request) {
        String key = request.getHeader(API_KEY_HEADER);
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(properties.getAuth().getKeys().get(key.trim()));
    }

    /**
     * Hız sınırının sayacı hangi kimliğe yazılacak.
     *
     * <p>Kimlik doğrulama kapalıyken <b>uzak adrese</b> düşülür: sınırın hiç uygulanmaması,
     * kaçak bir döngünün serbest kalması demek olurdu. Mükemmel bir kimlik değildir (NAT
     * arkasında tüm tüketiciler tek adres görünür) ama sınırın amacı kimlik doğrulamak değil,
     * yarıçapı sınırlamaktır.
     */
    public String rateLimitIdentity(HttpServletRequest request) {
        return resolve(request).orElseGet(() -> "ip:" + request.getRemoteAddr());
    }
}
