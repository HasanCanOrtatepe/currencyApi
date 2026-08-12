package com.ohbsy.currencyapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Zaman ve serileştirme altyapısı.
 *
 * <p><b>{@link Clock} neden bean:</b> tazelik kararı ("cache 15 dakikadan eski mi") zamana
 * bağlıdır. {@code Instant.now()} doğrudan çağrılsaydı bu kararı test etmek için gerçekten 15
 * dakika beklemek gerekirdi. Enjekte edilen saat, testin zamanı ileri sarmasına izin verir.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Cache serileştirmesi için ayrı bir {@link ObjectMapper}. Web katmanının mapper'ı
     * paylaşılmaz: cache biçimi <b>bizim iç meselemizdir</b> ve API sözleşmesindeki bir
     * değişiklik (alan adı, biçim) saklanmış kayıtları okunamaz hale getirmemelidir.
     */
    @Bean
    public ObjectMapper cacheObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
