package com.ohbsy.currencyapi.core.integrations.tcmb.dtos;

/**
 * TCMB belgesindeki tek {@code <Currency>} satırı — <b>ham</b>.
 *
 * <pre>{@code
 * <Currency Kod="USD" CurrencyCode="USD">
 *   <Unit>1</Unit>
 *   <ForexBuying>47.7000</ForexBuying>
 *   <ForexSelling>47.7300</ForexSelling>
 * </Currency>
 * }</pre>
 *
 * <p><b>Üç tuzağı da olduğu gibi taşır</b> (düzeltmek mapper'ın işi):
 * <ol>
 *   <li><b>Yön TERSTİR:</b> değer "1 yabancı birim kaç TL", bizim sözleşmemiz ise "1 TL kaç
 *       yabancı birim".</li>
 *   <li><b>{@code unit} 1 olmayabilir:</b> TCMB bazı kodları 100 birim üzerinden yayınlar
 *       (JPY). Hesaba katılmazsa kur 100 kat yanlış olur.</li>
 *   <li><b>Sayılar metin olarak gelir</b> ve nokta ondalık ayracıdır.</li>
 * </ol>
 *
 * @param code         {@code Kod} özniteliği (ham metin)
 * @param unit         {@code Unit} — kaç birim üzerinden yayınlandığı
 * @param forexBuying  döviz alış (bilgi amaçlı taşınır)
 * @param forexSelling döviz satış — <b>kullandığımız değer</b> (müşteriye gösterilen tutar,
 *                     o parayı alacak tarafın göreceği değerdir; ikisi arasında ~%0,3 fark
 *                     vardır ve seçim bilinçli olmalıdır)
 */
public record TcmbCurrencyRow(
        String code,
        String unit,
        String forexBuying,
        String forexSelling
) {
}
