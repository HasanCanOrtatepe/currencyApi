package com.ohbsy.currencyapi.api.controllers;

import com.ohbsy.currencyapi.api.dtos.AdminApiKeyCreateRequest;
import com.ohbsy.currencyapi.api.dtos.AdminApiKeyRateLimitRequest;
import com.ohbsy.currencyapi.business.abstracts.ApiKeyService;
import com.ohbsy.currencyapi.business.concretes.ApiKeyCreationResult;
import com.ohbsy.currencyapi.business.concretes.ApiKeyServiceImpl;
import com.ohbsy.currencyapi.business.concretes.ApiKeyUsageView;
import com.ohbsy.currencyapi.config.CurrencyApiProperties;
import com.ohbsy.currencyapi.dataAccess.InMemoryApiKeyStore;
import com.ohbsy.currencyapi.dataAccess.InMemoryRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdminApiKeyController — create/list/revoke")
class AdminApiKeyControllerTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-12T10:00:00Z"), ZoneOffset.UTC);

    private ApiKeyServiceImpl realService;
    private AdminApiKeyController controller;

    @BeforeEach
    void setUp() {
        CurrencyApiProperties properties = new CurrencyApiProperties();
        realService = new ApiKeyServiceImpl(new InMemoryApiKeyStore(),
                new InMemoryRateLimiter(properties, FIXED), FIXED);
        controller = new AdminApiKeyController(realService);
    }

    @Test
    @DisplayName("create → 201, gövdede rawKey dolu")
    void createReturns201WithRawKey() {
        ResponseEntity<?> response = controller.create(
                new AdminApiKeyCreateRequest("crm", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("list → 200 ile satırları döner")
    void listReturns200() {
        controller.create(new AdminApiKeyCreateRequest("crm", null));

        var response = controller.list();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().keys()).hasSize(1);
    }

    @Test
    @DisplayName("revoke bilinmeyen id → 404")
    void revokeUnknownReturns404() {
        var response = controller.revoke("hic-var-olmayan-id");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("revoke bilinen id → 204")
    void revokeKnownReturns204() {
        var created = controller.create(new AdminApiKeyCreateRequest("crm", null)).getBody();

        var response = controller.revoke(created.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("PATCH rate-limit bilinen id → 204, bilinmeyen → 404")
    void updateRateLimitStatuses() {
        var created = controller.create(new AdminApiKeyCreateRequest("crm", null)).getBody();

        assertThat(controller.updateRateLimit(created.id(),
                new AdminApiKeyRateLimitRequest(42)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.updateRateLimit("hic-var-olmayan-id",
                new AdminApiKeyRateLimitRequest(42)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PATCH rate-limit geçersiz değer → 400")
    void updateRateLimitRejectsInvalidValue() {
        var created = controller.create(new AdminApiKeyCreateRequest("crm", null)).getBody();

        assertThat(controller.updateRateLimit(created.id(),
                new AdminApiKeyRateLimitRequest(0)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Depo hatası → 503, detay sızdırmaz")
    void storeFailureYields503WithoutLeakingDetail() {
        ApiKeyService failing = new ApiKeyService() {
            @Override
            public ApiKeyCreationResult create(String consumerName, Integer rateLimitOverride) {
                throw new IllegalStateException("redis cok gizli baglanti dizgisi hatasi");
            }

            @Override
            public List<ApiKeyUsageView> list() {
                return List.of();
            }

            @Override
            public boolean revoke(String id) {
                return false;
            }

            @Override
            public boolean updateRateLimit(String id, Integer rateLimitOverride) {
                return false;
            }
        };
        AdminApiKeyController failingController = new AdminApiKeyController(failing);

        var response = failingController.create(new AdminApiKeyCreateRequest("crm", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNull();
    }
}
