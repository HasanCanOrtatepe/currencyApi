package com.ohbsy.currencyapi.api.dtos;

import java.math.BigDecimal;
import java.time.Instant;
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
 * <h2>Neden {@code fetchedAt} da var</h2>
 * Yalnız {@code rateDate} gösterilince kaçınılmaz bir soru doğuyor: "bugün 14'ü, sayfada
 * 13'ün kuru yazıyor — servis mi takıldı?" Takılmıyor; TCMB bülteni gün içinde bir kez
 * yayınlar ve o ana kadar geçerli olan son iş gününün kurudur. Bunu ancak <b>en son ne zaman
 * baktığımızı</b> da göstererek anlatabiliriz: "bülten 13.08, ama biz 10:21'de kontrol ettik"
 * cümlesi soruyu sormadan cevaplar.
 *
 * @param rateDate  TCMB'nin yayın günü
 * @param fetchedAt bizim TCMB'ye en son gidip veriyi aldığımız an
 * @param rates     birim fiyatlar (1 birim kaç TRY)
 */
public record RatePreviewResponse(LocalDate rateDate, Instant fetchedAt, List<Row> rates) {

    /** @param unitPrice 1 {@code currency} kaç TRY eder */
    public record Row(String currency, BigDecimal unitPrice) {
    }
}
