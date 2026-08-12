package com.ohbsy.currencyapi.api.dtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tanıtım sayfasının kur panosu için <b>anahtarsız</b> önizleme.
 *
 * <p><b>Neden asıl cevabın kendisi değil:</b> bu uç herkese açıktır ve ürünün yerine
 * geçmemelidir. Bu yüzden yalnız <b>gösterim</b> alanlarını taşır — {@code rate} (çevrim yönü),
 * {@code provider}, {@code cache}, {@code stale} gibi entegrasyonun ihtiyaç duyduğu alanlar
 * bilinçli olarak YOKTUR. Kur verisinin kendisi zaten TCMB'nin herkese açık yayınıdır;
 * gizlenen bir şey değil, sözleşmenin tamamıdır.
 *
 * @param rateDate TCMB'nin yayın günü
 * @param rates    birim fiyatlar (1 birim kaç TRY)
 */
public record RatePreviewResponse(LocalDate rateDate, List<Row> rates) {

    /** @param unitPrice 1 {@code currency} kaç TRY eder */
    public record Row(String currency, BigDecimal unitPrice) {
    }
}
