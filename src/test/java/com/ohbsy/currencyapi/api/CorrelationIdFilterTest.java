package com.ohbsy.currencyapi.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorrelationIdFilter — istek izleme kimliği")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private MockHttpServletRequest request(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rates");
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER, correlationId);
        }
        return request;
    }

    @Test
    @DisplayName("Tüketicinin gönderdiği kimlik KORUNUR ve cevapta yankılanır")
    void incomingIdIsPreserved() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("crm-abc-123"), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("crm-abc-123");
    }

    @Test
    @DisplayName("Kimlik gelmezse üretilir — tüketici cevaptan öğrenebilsin")
    void missingIdIsGenerated() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(null), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
    }

    /**
     * Satır sonu içeren bir değer log dosyasına sahte satır enjekte edebilirdi (log forging);
     * kimlik bir log alanıdır, gelen içeriğe olduğu gibi güvenilmez.
     */
    @Test
    @DisplayName("Güvensiz karakter içeren kimlik REDDEDİLİR, yerine yenisi üretilir")
    void unsafeIdIsReplaced() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("bad\nINFO sahte log satiri"), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).doesNotContain("\n");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).doesNotContain("sahte");
    }

    @Test
    @DisplayName("Aşırı uzun kimlik reddedilir")
    void overlyLongIdIsReplaced() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("x".repeat(500)), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).hasSizeLessThan(100);
    }

    /**
     * MDC iş parçacığına bağlıdır ve havuzdaki iş parçacığı bir SONRAKİ isteğe geçer:
     * temizlenmezse o istek de bu kimlikle loglanırdı.
     */
    @Test
    @DisplayName("İstek bitince MDC temizlenir (sonraki istek bu kimlikle loglanmasın)")
    void mdcIsClearedAfterRequest() throws Exception {
        filter.doFilter(request("crm-abc-123"), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
