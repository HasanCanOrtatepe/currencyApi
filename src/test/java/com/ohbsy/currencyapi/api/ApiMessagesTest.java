package com.ohbsy.currencyapi.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hata metinlerinin isteğin diline çözüldüğünü doğrular. GERÇEK {@code messages*.properties}
 * dosyaları kullanılır: eksik bir çeviri anahtarı burada yakalanmazsa ancak canlıda görülürdü.
 */
@DisplayName("ApiMessages — Accept-Language ile dil çözümü")
class ApiMessagesTest {

    private final ApiMessages messages = TestMessages.create();

    private MockHttpServletRequest request(String acceptLanguage) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (acceptLanguage != null) {
            request.addHeader("Accept-Language", acceptLanguage);
        }
        return request;
    }

    @Test
    @DisplayName("Accept-Language: tr → Türkçe metin")
    void turkishResolves() {
        assertThat(messages.get(request("tr"), "error.unauthorized"))
                .isEqualTo("API anahtari gecersiz ya da eksik");
    }

    @Test
    @DisplayName("tr-TR gibi bölgeli değer de Türkçeye çözülür")
    void turkishWithRegionResolves() {
        assertThat(messages.get(request("tr-TR,tr;q=0.9"), "error.rateLimitExceeded"))
                .isEqualTo("istek siniri asildi");
    }

    @Test
    @DisplayName("Başlık yoksa varsayılan İngilizce")
    void defaultsToEnglish() {
        assertThat(messages.get(request(null), "error.unauthorized"))
                .isEqualTo("invalid or missing API key");
    }

    /** Bilinmeyen dil bir hata değildir, yalnız çevirisi yoktur. */
    @Test
    @DisplayName("Desteklenmeyen dil varsayılana düşer, hata FIRLATMAZ")
    void unsupportedLanguageFallsBack() {
        assertThat(messages.get(request("de-DE"), "error.unauthorized"))
                .isEqualTo("invalid or missing API key");
    }

    @Test
    @DisplayName("Tüm hata anahtarları her iki dilde de tanımlı")
    void allKeysExistInBothLanguages() {
        for (String code : new String[] {
                "error.unauthorized", "error.rateLimitExceeded", "error.adminUnauthorized"}) {
            assertThat(messages.get(request("tr"), code)).isNotBlank();
            assertThat(messages.get(request("en"), code)).isNotBlank();
        }
    }
}
