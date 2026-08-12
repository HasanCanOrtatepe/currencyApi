package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.dataAccess.RateLimiter;
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
import java.util.Optional;

/**
 * Ticari kur API'lerinin iki kapısı: <b>anahtar</b> ve <b>hız sınırı</b>.
 *
 * <pre>
 * anahtar yok/tanınmıyor  → 401
 * kota aşıldı             → 429 + Retry-After
 * </pre>
 *
 * <h2>Actuator MUAFTIR — bilinçli</h2>
 * {@code /actuator/**} anahtar istemez: sağlık ucunu çağıran taraf orkestratördür (compose
 * healthcheck, Kubernetes probe) ve <b>elinde anahtar yoktur</b>. Anahtar istenseydi her
 * kurulum sağlıksız görünür, orkestratör de çalışan servisi sürekli yeniden başlatırdı —
 * güvenlik kazanmadan erişilebilirlik kaybedilirdi.
 *
 * <h2>Anahtar loglanmaz</h2>
 * Reddedilen istek bile anahtarı yazmaz: yanlış anahtarın kendisi de bir sırdır (çoğu zaman
 * BAŞKA bir ortamın geçerli anahtarıdır). Log'a yalnız "anahtar yok/tanınmadı" bilgisi girer.
 */
@Component
public class ApiGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiGuardFilter.class);

    private static final String LIMIT_HEADER = "X-RateLimit-Limit";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private final ApiClientResolver clients;
    private final RateLimiter rateLimiter;

    public ApiGuardFilter(ApiClientResolver clients, RateLimiter rateLimiter) {
        this.clients = clients;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Sağlık/metrik uçları, simülatörün kaos uçları ve admin yüzeyi kapıların dışındadır.
     * Admin trafiği kendi filtre zincirine ({@code AdminAuthFilter}) sahiptir ve bir "tüketici"
     * değildir — hız sınırına tabi tutulması ya da consumer anahtarı istenmesi anlamsızdır.
     *
     * <p>Kök yol ({@code /}) da muaftır: {@code static/index.html} orada servis tanıtımını
     * (anahtar YOK iken) gösterir — domaine direkt giren biri anahtar istemeden önce servisin
     * ne olduğunu görebilmelidir. Bu sayfa hiçbir veri döndürmez, yalnız bilgilendirme metnidir.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/") || path.equals("/index.html")
                || path.startsWith("/actuator") || path.startsWith("/__")
                || path.startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // TEK cozumleme: dinamik bir anahtar icin Redis'e (kimlik + lastUsedAt yazimi icin)
        // yalnizca BIR kez gidilir, asagida hem kimlik hem override ayni sonuctan turetilir.
        Optional<ApiClientResolver.ResolvedClient> resolved = clients.resolveClient(request);

        if (clients.isAuthEnabled() && resolved.isEmpty()) {
            log.warn("gecersiz ya da eksik API anahtari path={} remote={}",
                    request.getRequestURI(), request.getRemoteAddr());
            write(response, HttpStatus.UNAUTHORIZED, "invalid or missing API key");
            return;
        }

        String identity = resolved.map(ApiClientResolver.ResolvedClient::consumerName)
                .orElseGet(() -> "ip:" + request.getRemoteAddr());
        Integer rateLimitOverride = resolved
                .map(ApiClientResolver.ResolvedClient::rateLimitOverride)
                .orElse(null);

        RateLimiter.Decision decision = rateLimiter.tryConsume(identity, rateLimitOverride);
        response.setHeader(LIMIT_HEADER, String.valueOf(decision.limit()));
        response.setHeader(REMAINING_HEADER, String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            // Retry-After: tüketici ne zaman döneceğini TAHMİN ETMEK zorunda kalmamalı.
            response.setHeader(RETRY_AFTER_HEADER, String.valueOf(decision.retryAfterSeconds()));
            write(response, HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded");
            return;
        }

        chain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
