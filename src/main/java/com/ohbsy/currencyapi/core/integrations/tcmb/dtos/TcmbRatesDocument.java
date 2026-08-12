package com.ohbsy.currencyapi.core.integrations.tcmb.dtos;

import java.util.List;

/**
 * <b>TCMB'nin tel formatı (DTO).</b> Satıcının belgesini <i>olduğu gibi</i> taşır: alan adları
 * TCMB'nin, yön TCMB'nin, birim çarpanı TCMB'nin. Hiçbir düzeltme yapmaz.
 *
 * <p><b>Neden domain modeline doğrudan gidilmiyor.</b> Ayrı bir DTO katmanı, "satıcı ne dedi"
 * ile "biz ne anlıyoruz" sorularını ayırır. XML okuma hatası ile eşleme hatası aynı sınıfta
 * karışsaydı, TCMB bir alan adını değiştirdiğinde hatanın ayrıştırmada mı yoksa iş kuralında mı
 * olduğu anlaşılmazdı. Ayrıca DTO test edilebilir bir ara ürün verir: "XML'i doğru okuduk mu"
 * sorusu, "kuru doğru çevirdik mi" sorusundan bağımsız yanıtlanır.
 *
 * @param date TCMB'nin yayın günü ({@code Tarih="11.08.2026"}) — ham metin, ayrıştırılmamış
 * @param rows belgedeki tüm para birimi satırları (bizim tanımadıklarımız dahil)
 */
public record TcmbRatesDocument(String date, List<TcmbCurrencyRow> rows) {

    public TcmbRatesDocument {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
