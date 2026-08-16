package com.ohbsy.currencyapi.core.integrations.ecb.dtos;

import java.util.List;

/**
 * ECB {@code eurofxref} belgesinin ham hâli — <b>gün listesi</b>.
 *
 * <p><b>Neden liste, günlük dosyada tek gün olduğu hâlde:</b> ECB aynı şemayı üç dosyada
 * kullanır (günlük · son 90 gün · tüm tarih). Tekil bir alan, taban URL bir gün 90 günlük
 * dosyaya çevrildiğinde <b>sessizce</b> yanlış günü seçerdi. Liste + "en yenisini al" kuralı
 * her üç dosyada da doğru davranır ve maliyeti bir {@code for} döngüsüdür.
 *
 * @param days belgedeki günler — satıcının verdiği SIRAYLA, sıralanmadan
 */
public record EcbRatesDocument(List<EcbDailyRates> days) {
}
