package com.ohbsy.currencyapi.api.controllers;

import com.ohbsy.currencyapi.api.dtos.ExchangeRateRow;
import com.ohbsy.currencyapi.api.dtos.ExchangeRatesResponse;
import com.ohbsy.currencyapi.api.dtos.RatePreviewResponse;
import com.ohbsy.currencyapi.business.abstracts.ExchangeRateService;
import com.ohbsy.currencyapi.business.concretes.RateResult;
import com.ohbsy.currencyapi.entities.CurrencyCode;
import com.ohbsy.currencyapi.entities.ExchangeRateSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kur ucu — servisin <b>ürün yüzü</b>.
 *
 * <pre>
 * GET /api/v1/rates                      → tüm desteklenen kurlar
 * GET /api/v1/rates?symbols=USD,EUR      → yalnız istenenler
 * </pre>
 *
 * <h2>İki durum kodu, iki farklı anlam</h2>
 * <ul>
 *   <li><b>200</b> — kur var. Satıcı düşmüş ve <b>son geçerli kur</b> sunuluyor olsa bile 200
 *       döner; bu bir hata değildir ve {@code stale}/{@code cache} alanları durumu söyler.
 *       Bayat kuru 5xx ile bildirmek, tüketiciyi elinde kullanılabilir veri varken hataya
 *       düşürürdü.</li>
 *   <li><b>503</b> — elde hiç kur yok (satıcı erişilemez <b>ve</b> cache boş). Tüketici
 *       burada kendi yedek davranışına geçmelidir; {@code Retry-After} ile ne zaman tekrar
 *       denemesi gerektiği söylenir.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/rates")
public class ExchangeRateController {

    /** Tanıtım panosunda gösterilenler — sıra ekrandaki sırayla aynıdır. */
    private static final List<CurrencyCode> PREVIEW_CURRENCIES =
            List.of(CurrencyCode.USD, CurrencyCode.EUR, CurrencyCode.GBP, CurrencyCode.JPY);

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @GetMapping
    public ResponseEntity<ExchangeRatesResponse> rates(
            @RequestParam(required = false) String symbols) {

        RateResult result = exchangeRateService.currentRates();
        if (!result.hasRates()) {
            // Gövde YOK: söyleyecek kur yok. Durumu statü kodu ve Retry-After taşır.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "60")
                    .build();
        }
        return ResponseEntity.ok(toResponse(result, parseSymbols(symbols)));
    }

    /**
     * Tanıtım sayfasının kur panosu — <b>anahtar İSTEMEZ</b> ({@code ApiGuardFilter} bu yolu
     * kimlik doğrulamasından muaf tutar; hız sınırı IP başına uygulanmaya devam eder).
     *
     * <h2>Neden anahtarsız bir uç var</h2>
     * Ana sayfadaki pano gerçek kuru göstermelidir; sabit yazılmış sayılar bir <i>kur
     * servisinin</i> vitrininde eskiyip yanlışa döner. Alternatif olan "sayfaya anahtar gömmek"
     * ise anahtarı yakmak demektir: herkes kaynağı görüp kotayı harcayabilirdi.
     *
     * <p><b>Ürünün yerine geçmez</b> ve bu bilinçlidir: yalnız birkaç para birimi, yalnız
     * gösterim alanları ({@code unitPrice}) döner. Entegrasyon için gereken çevrim yönü
     * ({@code rate}), tazelik ve sağlayıcı bilgisi burada YOKTUR. Yeni bir satıcı isteği de
     * üretmez — aynı 15 dakikalık cache'ten okur.
     */
    @GetMapping("/preview")
    public ResponseEntity<RatePreviewResponse> preview() {
        RateResult result = exchangeRateService.currentRates();
        if (!result.hasRates()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "60")
                    .build();
        }

        ExchangeRateSnapshot snapshot = result.snapshot();
        List<RatePreviewResponse.Row> rows = PREVIEW_CURRENCIES.stream()
                .filter(snapshot.availableCurrencies()::contains)
                .map(code -> new RatePreviewResponse.Row(
                        code.name(), snapshot.unitPriceOf(code)))
                .toList();

        return ResponseEntity.ok(
                new RatePreviewResponse(snapshot.rateDate(), snapshot.fetchedAt(), rows));
    }

    /**
     * Tanınmayan kod <b>hata değildir</b>, süzgeçte yok sayılır: istemcinin desteklemediğimiz
     * bir kod sorması onu 400'e düşürmemeli, yalnız o kodu almamalıdır. Süzgeç boşsa hepsi döner.
     */
    private Set<CurrencyCode> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(symbols.split(","))
                .map(CurrencyCode::fromCode)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toSet());
    }

    private ExchangeRatesResponse toResponse(RateResult result, Set<CurrencyCode> wanted) {
        ExchangeRateSnapshot snapshot = result.snapshot();

        List<ExchangeRateRow> rows = snapshot.availableCurrencies().stream()
                .filter(code -> wanted.isEmpty() || wanted.contains(code))
                // Sıra kararlı olmalı: aksi halde aynı cevap her istekte farklı sıralanır ve
                // istemci tarafındaki karşılaştırmalar/cache'ler boşuna geçersizleşir.
                .sorted(Comparator.comparing(Enum::name))
                .map(code -> new ExchangeRateRow(
                        code.name(),
                        snapshot.rateOf(code),
                        snapshot.unitPriceOf(code)))
                .toList();

        return new ExchangeRatesResponse(
                snapshot.base().name(),
                snapshot.rateDate(),
                snapshot.fetchedAt(),
                result.provider(),
                result.status().name(),
                result.isStale(),
                rows);
    }
}
