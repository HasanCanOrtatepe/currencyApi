package com.ohbsy.currencyapi.api;

import com.ohbsy.currencyapi.dataAccess.ApiKeyUsageCounter;
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
import java.util.Set;

/**
 * Ticari kur API'lerinin iki kapısı: <b>anahtar</b> ve <b>hız sınırı</b>.
 *
 * <pre>
 * kota aşıldı             → 429 + Retry-After   (ÖNCE bu: başarısız deneme de kota harcar)
 * anahtar yok/tanınmıyor  → 401
 * </pre>
 *
 * <h2>Muaf olan yalnız SAĞLIK ucudur, {@code /actuator/**} DEĞİL</h2>
 * {@code /actuator/health} anahtar istemez: onu çağıran taraf orkestratördür (compose
 * healthcheck, Kubernetes probe) ve <b>elinde anahtar yoktur</b>. Anahtar istenseydi her
 * kurulum sağlıksız görünür, orkestratör de çalışan servisi sürekli yeniden başlatırdı —
 * güvenlik kazanmadan erişilebilirlik kaybedilirdi.
 *
 * <p>Muafiyet önce {@code /actuator} ÖNEKİNİN tamamınaydı ve bu, tünelin taşıdığı public portta
 * ölçülerek doğrulanmış bir sızıntıydı: {@code /actuator/metrics} anahtarsız <b>ve kotasız</b>
 * cevap veriyor, JDK sürümünü ({@code jvm.info}), çalışma süresini, disk boşluğunu ve
 * {@code http.server.requests} üzerinden tüm uç listesini dışarı veriyordu. Sürüm bilgisi
 * bir saldırgan için CVE eşlemesidir; kotasızlık ise sınırın hiç uygulanmadığı bir yüzeydir.
 * Sağlık ucu dışındaki her actuator yolu artık kapıdan geçer. Sondaların
 * ({@code /actuator/health/liveness}, {@code /readiness}) muafiyeti korunur.
 *
 * <h2>Anahtar loglanmaz</h2>
 * Reddedilen istek bile anahtarı yazmaz: yanlış anahtarın kendisi de bir sırdır (çoğu zaman
 * BAŞKA bir ortamın geçerli anahtarıdır). Log'a yalnız "anahtar yok/tanınmadı" bilgisi girer.
 */
@Component
public class ApiGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiGuardFilter.class);

    /** Tanıtım sayfasının panosu — anahtarsız, ama hız sınırına tabi (bkz. doFilterInternal). */
    private static final String PUBLIC_PREVIEW_PATH = "/api/v1/rates/preview";

    /** Orkestratörün sorduğu tek uç. Altındaki sondalar (liveness/readiness) da muaftır. */
    private static final String HEALTH_PATH = "/actuator/health";

    private static final String LIMIT_HEADER = "X-RateLimit-Limit";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private final ApiClientResolver clients;
    private final RateLimiter rateLimiter;
    private final ApiKeyUsageCounter usageCounter;
    private final ApiMessages messages;

    public ApiGuardFilter(ApiClientResolver clients, RateLimiter rateLimiter,
                          ApiKeyUsageCounter usageCounter, ApiMessages messages) {
        this.clients = clients;
        this.rateLimiter = rateLimiter;
        this.usageCounter = usageCounter;
        this.messages = messages;
    }

    /**
     * Sağlık ucu, simülatörün kaos uçları ve admin yüzeyi kapıların dışındadır.
     * Admin trafiği kendi filtre zincirine ({@code AdminAuthFilter}) sahiptir ve bir "tüketici"
     * değildir — hız sınırına tabi tutulması ya da consumer anahtarı istenmesi anlamsızdır.
     *
     * <p>Tanıtım sayfası ve VARLIKLARI da muaftır: domaine direkt giren biri anahtar istemeden
     * önce servisin ne olduğunu görebilmelidir. Yalnız sayfanın kendisi muaf tutulup varlıkları
     * unutulursa sayfa açılır ama sekme ikonu 401 alır (ölçülerek bulundu) — bu yüzden liste
     * TAM ADLARLA yazılır: yeni bir varlık eklendiğinde buraya da eklenmesi gerekir ve
     * "/static altındaki her şey serbest" gibi geniş bir kural açılmaz.
     */
    private static final Set<String> PUBLIC_STATIC_PATHS = Set.of(
            "/", "/index.html",
            "/favicon.svg", "/favicon-32.png", "/apple-touch-icon.png", "/logo.svg");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_STATIC_PATHS.contains(path)
                || isHealthProbe(path) || path.startsWith("/__")
                || path.startsWith("/admin");
    }

    /**
     * Tam eşleşme ya da sonda alt yolu — {@code /actuator/health-detay} gibi bir uç
     * uydurulup muafiyete sızamasın diye önek kontrolü ayırıcıyı DA içerir.
     */
    private static boolean isHealthProbe(String path) {
        return HEALTH_PATH.equals(path) || path.startsWith(HEALTH_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // TEK cozumleme: dinamik bir anahtar icin Redis'e (kimlik + lastUsedAt yazimi icin)
        // yalnizca BIR kez gidilir, asagida hem kimlik hem override ayni sonuctan turetilir.
        Optional<ApiClientResolver.ResolvedClient> resolved = clients.resolveClient(request);

        // Tanıtım panosunun önizleme ucu ANAHTAR İSTEMEZ ama filtreden ÇIKARILMAZ: shouldNotFilter
        // ile muaf tutulsaydı hız sınırı da uygulanmazdı ve herkese açık bir uç sınırsız kalırdı.
        // Böylece anonim istek IP kimliğiyle sayılmaya devam eder.
        boolean unauthenticated =
                clients.isAuthEnabled() && resolved.isEmpty() && !isPublicPreview(request);

        String identity = resolved.map(ApiClientResolver.ResolvedClient::consumerName)
                .orElseGet(() -> "ip:" + clients.clientIp(request));
        Integer rateLimitOverride = resolved
                .map(ApiClientResolver.ResolvedClient::rateLimitOverride)
                .orElse(null);

        // KOTA, 401'DEN ÖNCE İŞLER — bilinçli. Sıra tersken başarısız kimlik doğrulaması hiç
        // sayılmıyordu: anahtar denemesi SINIRSIZDI ve dinamik anahtar biçimindeki her deneme
        // ayrıca bir Redis okuması harcatıyordu. 256 bitlik anahtara kaba kuvvet zaten
        // hesaplanamaz ölçüde pahalıdır; kapatılan şey kaba kuvvet değil, anonim bir çağıranın
        // servisten SINIRSIZ iş çekebildiği tek yoldur.
        RateLimiter.Decision decision = rateLimiter.tryConsume(identity, rateLimitOverride);
        response.setHeader(LIMIT_HEADER, String.valueOf(decision.limit()));
        response.setHeader(REMAINING_HEADER, String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            // Retry-After: tüketici ne zaman döneceğini TAHMİN ETMEK zorunda kalmamalı.
            response.setHeader(RETRY_AFTER_HEADER, String.valueOf(decision.retryAfterSeconds()));
            write(response, HttpStatus.TOO_MANY_REQUESTS,
                    messages.get(request, "error.rateLimitExceeded"));
            return;
        }

        if (unauthenticated) {
            // remote= artık ÇÖZÜLMÜŞ adrestir: tünel arkasında getRemoteAddr() her istek için
            // aynı değeri verdiğinden bu satır kimseyi işaret etmiyordu.
            log.warn("gecersiz ya da eksik API anahtari path={} remote={}",
                    request.getRequestURI(), clients.clientIp(request));
            write(response, HttpStatus.UNAUTHORIZED,
                    messages.get(request, "error.unauthorized"));
            return;
        }

        // Kotayı GEÇEN istek sayılır. 429 alanlar sayılsaydı panelde "kullanım" sütunu, servis
        // edilmemiş istekleri de gösterir ve tüketiciye kesilen faturayla uyuşmazdı; kaçak bir
        // döngü zaten hız sınırı WARN'ında görünür. Anonim/statik anahtarda keyId null'dır ve
        // sayaç sessizce atlanır — onların panelde satırı yoktur.
        usageCounter.record(resolved.map(ApiClientResolver.ResolvedClient::keyId).orElse(null));

        chain.doFilter(request, response);
    }

    /** Tam eşleşme: {@code /api/v1/rates} ile başlayan diğer yollar anahtar istemeye devam eder. */
    private boolean isPublicPreview(HttpServletRequest request) {
        return PUBLIC_PREVIEW_PATH.equals(request.getRequestURI());
    }

    private void write(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
