package com.ohbsy.currencyapi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS izinleri YALNIZ tarayıcıda uygulanır: eksik bir metot birim testlerinde ve elle
 * {@code curl} denemelerinde görünmez, yalnız admin paneli sessizce çalışmayı bıraktığında
 * fark edilir. {@code PATCH} bu şekilde bir kez atlandı (limit düzenleme özelliği eklendiğinde
 * izin listesine yazılmamıştı) — bu test o hatanın tekrarını engeller.
 */
@DisplayName("AdminCorsConfig — /admin/** CORS izinleri")
class AdminCorsConfigTest {

    /** {@code getCorsConfigurations()} protected'tır; okumak için alt sınıf gerekir. */
    private static class ExposedCorsRegistry extends CorsRegistry {
        @Override
        public Map<String, CorsConfiguration> getCorsConfigurations() {
            return super.getCorsConfigurations();
        }
    }

    private CorsConfiguration adminConfiguration() {
        ExposedCorsRegistry registry = new ExposedCorsRegistry();
        new AdminCorsConfig(new CurrencyApiProperties()).addCorsMappings(registry);
        return registry.getCorsConfigurations().get("/admin/**");
    }

    @Test
    @DisplayName("Controller'ın sunduğu TÜM metotlara izin verilir (PATCH dahil)")
    void allControllerMethodsAreAllowed() {
        assertThat(adminConfiguration().getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PATCH", "DELETE");
    }

    /**
     * Panel yalnız loopback'e bağlıdır ama tarayıcıya {@code localhost} da {@code 127.0.0.1} de
     * yazılabilir; {@code Origin} hangisi yazıldıysa o gider. Biri eksik olsaydı panel,
     * kullanıcının hangi yazımı seçtiğine bağlı olarak sessizce çalışmazdı.
     */
    @Test
    @DisplayName("localhost ve 127.0.0.1 yazımlarının İKİSİ de kabul edilir")
    void bothLoopbackSpellingsAreAllowed() {
        assertThat(adminConfiguration().getAllowedOriginPatterns())
                .containsExactlyInAnyOrder("http://localhost:8096", "http://127.0.0.1:8096");
    }

    @Test
    @DisplayName("Token başlığına izin verilir — yoksa tarayıcı isteği hiç göndermez")
    void adminTokenHeaderIsAllowed() {
        assertThat(adminConfiguration().getAllowedHeaders()).contains("X-Admin-Token");
    }

    /** Genel/public API'nin tarayıcı istemcisi yoktur; oraya CORS açmak gereksiz gevşemedir. */
    @Test
    @DisplayName("Yalnız /admin/** için CORS açılır, public API'ye dokunulmaz")
    void onlyAdminPathIsMapped() {
        ExposedCorsRegistry registry = new ExposedCorsRegistry();
        new AdminCorsConfig(new CurrencyApiProperties()).addCorsMappings(registry);

        assertThat(registry.getCorsConfigurations()).containsOnlyKeys("/admin/**");
    }
}
