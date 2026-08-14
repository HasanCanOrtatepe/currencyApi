package com.ohbsy.currencyapi.core.integrations.evds.dtos;

import java.util.List;

/**
 * EVDS'in seri sorgusu cevabı — <b>bir tarih aralığındaki günlerin listesi</b>.
 *
 * <p>Tek bir gün değil aralık istememizin sebebi EVDS'in takvimi olduğu gibi vermesidir:
 * "bugünün kuru" diye tek gün sorulsaydı hafta sonu, resmî tatil ve bültenin henüz
 * yayınlanmadığı sabah saatleri boş cevapla dönerdi. Aralık isteyip <b>en yeni dolu günü</b>
 * seçmek, "yürürlükteki kur" sorusunun doğru cevabıdır.
 *
 * @param totalCount EVDS'in bildirdiği satır sayısı (boş değerli günler dahil)
 * @param days       gözlem satırları — sırası GARANTİ DEĞİLDİR, {@code EvdsRateMapper} sıralar
 */
public record EvdsSeriesDocument(int totalCount, List<EvdsObservation> days) {
}
