package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AdminAuthFilter — /admin/** token kapısı")
class AdminAuthFilterTest {

    private CurrencyApiProperties properties;
    private AdminAuthFilter filter;

    @BeforeEach
    void setUp() {
        properties = new CurrencyApiProperties();
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("dogru-token");
        filter = new AdminAuthFilter(properties);
    }

    private MockHttpServletRequest adminRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/keys");
        if (token != null) {
            request.addHeader("X-Admin-Token", token);
        }
        return request;
    }

    @Test
    @DisplayName("Token yok → 401")
    void missingTokenRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(adminRequest(null), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Yanlış token → 401")
    void wrongTokenRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(adminRequest("yanlis-token"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Doğru token → chain'e geçer")
    void correctTokenPasses() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(adminRequest("dogru-token"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("/admin dışındaki yollar muaftır")
    void nonAdminPathIsExempt() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rates");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    /**
     * Tarayıcı CORS preflight'ı (OPTIONS) token TAŞIMAZ — bu KASITLIDIR. Burada da token
     * istenseydi preflight 401 alır, tarayıcı asıl (token'lı) isteği hiç göndermezdi.
     */
    @Test
    @DisplayName("OPTIONS (CORS preflight) token olmadan da chain'e geçer")
    void optionsPreflightIsExempt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(adminRequestWithMethod("OPTIONS", null), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest adminRequestWithMethod(String method, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/admin/keys");
        if (token != null) {
            request.addHeader("X-Admin-Token", token);
        }
        return request;
    }

    @Test
    @DisplayName("admin.enabled=true ama token boş → AÇILIŞTA düşer")
    void enabledWithoutTokenFailsFast() {
        CurrencyApiProperties broken = new CurrencyApiProperties();
        broken.getAdmin().setEnabled(true);

        assertThatThrownBy(() -> new AdminAuthFilter(broken).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token bos");
    }
}
