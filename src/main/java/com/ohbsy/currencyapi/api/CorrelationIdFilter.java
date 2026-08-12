package com.ohbsy.currencyapi.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * İstek izleme kimliği — <b>tüketicinin kimliğiyle bizim logumuzu birleştiren tek ip ucu</b>.
 *
 * <p>Tüketici (CRM) her isteğine {@code X-Correlation-Id} ekler ve bunu kendi logunda taşır.
 * Bu filtre aynı değeri okuyup {@link MDC}'ye koyar, böylece <b>o isteğin</b> ürettiği her log
 * satırı aynı kimlikle damgalanır: "CRM'de saat 14:02'de patlayan istek burada ne yaptı?"
 * sorusu, zaman damgalarını gözle eşleştirmeden cevaplanabilir.
 *
 * <h2>Değer neden yankılanıyor</h2>
 * Cevaba aynı başlık geri konur. Tüketici kimliği kendisi üretmediyse (başlıksız geldiyse)
 * bizim ürettiğimizi öğrenmesinin başka yolu yoktur; öğrenemezse de o isteği bize sorarken
 * elinde gösterecek bir şey kalmaz.
 *
 * <h2>Gelen değere neden güvenilmiyor</h2>
 * Kimlik <b>log alanıdır, yetki değildir</b>; yine de sınırsız uzunlukta ya da satır sonu
 * içeren bir değer log dosyasına sahte satır enjekte edebilirdi (log forging). Bu yüzden
 * yalnız güvenli karakterler ve makul bir uzunluk kabul edilir, gerisi atılıp yenisi üretilir.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // MDC iş parçacığına bağlıdır ve havuzdaki iş parçacığı bir SONRAKİ isteğe geçer:
            // temizlenmezse o istek de bu kimlikle loglanır (sessiz ve yanıltıcı bir arıza).
            MDC.remove(MDC_KEY);
        }
    }

    private String sanitize(String incoming) {
        if (incoming == null || incoming.isBlank() || incoming.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        String trimmed = incoming.trim();
        return trimmed.chars().allMatch(CorrelationIdFilter::isSafe)
                ? trimmed
                : UUID.randomUUID().toString();
    }

    private static boolean isSafe(int ch) {
        return Character.isLetterOrDigit(ch) || ch == '-' || ch == '_';
    }
}
