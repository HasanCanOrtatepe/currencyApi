package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /admin/**} için token kapısı.
 *
 * <h2>Savunma derinliği — asıl sınır DEĞİL</h2>
 * Admin yüzeyinin internetten erişilemez olmasını sağlayan asıl mekanizma, bu servisin
 * {@code currency-api.admin.port} ile <b>ayrı bir portta</b> çalışması ve tünelin (Cloudflare
 * quick tunnel) yalnız public portu (8095) bilmesidir — bkz. {@code
 * CurrencyApiProperties.Admin}. Bu filtre onun ÜZERİNE ikinci bir katmandır: aynı LAN'daki
 * yetkisiz bir cihazın da anahtar yönetemesin diye.
 *
 * <h2>Sabit-zamanlı karşılaştırma</h2>
 * {@code String.equals} erken çıkar ve karşılaştırma süresi doğru karakter sayısına sızabilir
 * (timing attack). {@link MessageDigest#isEqual} sabit zamanlıdır.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final CurrencyApiProperties properties;
    private final ApiMessages messages;

    public AdminAuthFilter(CurrencyApiProperties properties, ApiMessages messages) {
        this.properties = properties;
        this.messages = messages;
    }

    /**
     * {@code admin.enabled=true} ama token tanımsızsa açılışta gürültülü düşer — sessizce her
     * admin isteğini reddetmek yerine, yanlış yapılandırmayı hemen görünür kılar. {@link
     * ApiClientResolver#validateConfiguration()} ile aynı desen.
     */
    @PostConstruct
    void validateConfiguration() {
        if (properties.getAdmin().isEnabled() && properties.getAdmin().getToken().isBlank()) {
            throw new IllegalStateException(
                    "currency-api.admin.enabled=true ama currency-api.admin.token bos. "
                            + "Token ortam degiskeninden verilir (CURRENCY_ADMIN_TOKEN).");
        }
    }

    /**
     * {@code OPTIONS} (CORS preflight) muaftır: tarayıcı bu isteği KASITLI olarak token/özel
     * başlık olmadan gönderir (CORS ön-kontrolü budur). Burada da token istenseydi, preflight
     * 401 alır ve tarayıcı asıl isteği (token'lı olanı) hiç göndermezdi — admin-ui gerçek bir
     * tarayıcıda tamamen kırılırdı. Preflight'ın kendisi Spring'in CORS mekanizmasınca
     * ({@code AdminCorsConfig}) cevaplanır, buradan geçmesi güvenlik açığı değildir: hiçbir
     * veri döndürmez, yalnız hangi origin/metotların izinli olduğunu söyler.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Admin KAPALIYKEN 404 — 401 DEĞİL. 401, "burada bir admin yüzeyi var ama token'ın
        // yanlış" demektir ve internete açık public instance'ta admin API'sinin VARLIĞINI
        // doğrular. Kapalı bir kurulumda o yüzey gerçekten yoktur; cevabı da öyle olmalıdır.
        if (!properties.getAdmin().isEnabled()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        String provided = request.getHeader(ADMIN_TOKEN_HEADER);
        String expected = properties.getAdmin().getToken();

        if (provided == null || expected.isBlank()
                || !MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8))) {
            log.warn("gecersiz ya da eksik admin token path={} remote={}",
                    request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\""
                    + messages.get(request, "error.adminUnauthorized") + "\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
