package com.ohbsy.currencyapi.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
        registry.addMapping("/admin/**")
                .allowedOriginPatterns(properties.getAdmin().getCorsOriginPattern())
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("X-Admin-Token", "Content-Type");
    }
}
