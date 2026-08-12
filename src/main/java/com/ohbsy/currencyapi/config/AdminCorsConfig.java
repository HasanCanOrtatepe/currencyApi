package com.ohbsy.currencyapi.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS yalnız {@code /admin/**} için ve yalnız admin-ui'nin origin'i için açılır — Angular
 * arayüzü ile admin API farklı port/origin'lerde çalıştığından (bkz. {@code
 * CurrencyApiProperties.Admin}) tarayıcı isteği bu izin olmadan reddeder. Genel/public
 * {@code /api/v1/rates} ucunun tarayıcı istemcisi yok, oraya dokunulmaz.
 */
@Configuration
@ConditionalOnProperty(name = "currency-api.admin.enabled", havingValue = "true")
public class AdminCorsConfig implements WebMvcConfigurer {

    private final CurrencyApiProperties properties;

    public AdminCorsConfig(CurrencyApiProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Listeye YENİ BİR METOT EKLERKEN burayı güncellemeyi unutma: eksik metot yalnız
        // TARAYICIDA kırılır (curl CORS uygulamaz), yani birim testleri ve elle curl denemeleri
        // geçerken panel sessizce çalışmaz. PATCH bu şekilde bir kez atlandı.
        String[] origins = Arrays.stream(properties.getAdmin().getCorsOriginPattern().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);

        registry.addMapping("/admin/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE")
                .allowedHeaders("X-Admin-Token", "Content-Type");
    }
}
