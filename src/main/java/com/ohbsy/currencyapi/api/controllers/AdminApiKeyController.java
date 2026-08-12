package com.ohbsy.currencyapi.api.controllers;

import com.ohbsy.currencyapi.api.dtos.AdminApiKeyCreateRequest;
import com.ohbsy.currencyapi.api.dtos.AdminApiKeyCreatedResponse;
import com.ohbsy.currencyapi.api.dtos.AdminApiKeyRow;
import com.ohbsy.currencyapi.api.dtos.AdminApiKeysResponse;
import com.ohbsy.currencyapi.business.abstracts.ApiKeyService;
import com.ohbsy.currencyapi.business.concretes.ApiKeyCreationResult;
import com.ohbsy.currencyapi.business.concretes.ApiKeyUsageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Anahtar yönetimi ucu — servisin <b>admin yüzü</b>.
 *
 * <pre>
 * POST   /admin/keys       → yeni anahtar (rawKey yalnız BU cevapta görünür)
 * GET    /admin/keys       → aktif+iptal tüm anahtarlar, anlık kullanımla
 * DELETE /admin/keys/{id}  → iptal
 * </pre>
 *
 * <h2>Bu bean yalnız {@code currency-api.admin.enabled=true} iken var olur</h2>
 * Bu tek satır ({@code @ConditionalOnProperty}), bu controller'ın internete açık public
 * instance'ta hiç <b>var olmamasını</b> sağlar. Pratikte o instance'ta {@code /admin/**} yine
 * de 404 yerine 401 görür — {@link com.ohbsy.currencyapi.api.AdminAuthFilter} her instance'ta
 * (koşulsuz) çalışır ve admin kapalıyken token da boş olduğundan bu yol zaten kapalıdır; asıl
 * güvence yine de bu YOKLUK'tur, filtre yalnız ikinci bir katmandır. Bkz. {@code
 * CurrencyApiProperties.Admin} sınıf yorumu.
 *
 * <h2>Statik anahtarlar burada GÖRÜNMEZ</h2>
 * {@code CURRENCY_API_KEYS} anahtarları açılışta immutable bir bean'e gömülüdür, runtime'da
 * revoke edilmek üzere tasarlanmadılar — bu API yalnız admin'in runtime'da oluşturduğu dinamik
 * anahtarları yönetir.
 */
@RestController
@RequestMapping("/admin/keys")
@ConditionalOnProperty(name = "currency-api.admin.enabled", havingValue = "true")
public class AdminApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(AdminApiKeyController.class);

    private final ApiKeyService apiKeyService;

    public AdminApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    public ResponseEntity<AdminApiKeyCreatedResponse> create(
            @RequestBody AdminApiKeyCreateRequest request) {
        try {
            ApiKeyCreationResult result = apiKeyService.create(
                    request.consumerName(), request.rateLimitOverride());
            return ResponseEntity.status(HttpStatus.CREATED).body(new AdminApiKeyCreatedResponse(
                    result.id(), result.rawKey(), result.consumerName(), result.createdAt(),
                    result.rateLimitOverride()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            // Detay sizdirilmaz — depo yazilamadiginda tuketici/anahtar bilgisi govdeye girmez.
            log.error("api anahtari olusturulamadi sebep={}", e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @GetMapping
    public ResponseEntity<AdminApiKeysResponse> list() {
        List<AdminApiKeyRow> rows = apiKeyService.list().stream()
                .map(this::toRow)
                .toList();
        return ResponseEntity.ok(new AdminApiKeysResponse(rows));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        return apiKeyService.revoke(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private AdminApiKeyRow toRow(ApiKeyUsageView view) {
        return new AdminApiKeyRow(
                view.id(),
                view.consumerName(),
                view.keyPreview(),
                view.createdAt(),
                view.revokedAt(),
                view.isActive(),
                view.rateLimitOverride(),
                view.lastUsedAt(),
                view.usageLimit(),
                view.usageRemaining());
    }
}
