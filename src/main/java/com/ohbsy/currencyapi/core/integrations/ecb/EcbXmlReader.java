package com.ohbsy.currencyapi.core.integrations.ecb;

import com.ohbsy.currencyapi.core.integrations.ecb.dtos.EcbDailyRates;
import com.ohbsy.currencyapi.core.integrations.ecb.dtos.EcbRatesDocument;
import com.ohbsy.currencyapi.core.utilities.SecureXml;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>XML → DTO.</b> ECB belgesini okur, hiçbir şeyi yorumlamaz — {@code TcmbXmlReader} ile aynı
 * ayrım: "belgeyi okuyamadık" ile "kuru çeviremedik" farklı arızalardır ve farklı yerlerde aranır.
 *
 * <h2>Üç ayrı şey de {@code <Cube>} adını taşır</h2>
 * ECB belgesinin tuhaflığı budur: sarmalayıcı, gün ve kur satırı <b>aynı eleman adını</b>
 * kullanır ve yalnız öznitelikleriyle ayrılırlar.
 * <pre>{@code
 * <Cube>                                    <-- sarmalayıcı: özniteliği YOK
 *   <Cube time="2026-08-14">                <-- gün: time özniteliği VAR
 *     <Cube currency="USD" rate="1.1665"/>  <-- kur: currency + rate VAR
 * }</pre>
 * Bu yüzden "tüm Cube'ları düz gez" yaklaşımı yanlıştır: kur satırlarını gün sanardı. Günler
 * {@code time} özniteliğiyle seçilir, kurlar ise <b>yalnız o günün DOĞRUDAN çocukları</b>
 * arasından okunur — {@code getElementsByTagName} torunları da getirdiği için burada
 * kullanılmaz, yoksa iki günlü bir belgede ilk günün altına ikinci günün kurları da girerdi.
 *
 * <p><b>Ad alanı önekleri umursanmaz:</b> {@code SecureXml} ayrıştırıcıyı bilinçli olarak ad
 * alanı duyarsız kurar; ECB'nin {@code gesmes:} önekli üst elemanları zaten okunmaz.
 *
 * <p><b>Tolerant reader:</b> tanımadığımız eleman/öznitelik yok sayılır, eksik alan taşınmaz.
 */
@Component
public class EcbXmlReader {

    private static final String CUBE_ELEMENT = "Cube";
    private static final String TIME_ATTRIBUTE = "time";
    private static final String CURRENCY_ATTRIBUTE = "currency";
    private static final String RATE_ATTRIBUTE = "rate";

    /**
     * @throws IllegalArgumentException belge okunamazsa (bozuk XML, DOCTYPE) ya da hiç
     *         {@code <Cube>} içermiyorsa — o noktada elimizdeki şey ECB belgesi değildir
     */
    public EcbRatesDocument read(String xml) {
        Document document = SecureXml.parse(xml);

        NodeList cubes = document.getElementsByTagName(CUBE_ELEMENT);
        if (cubes.getLength() == 0) {
            throw new IllegalArgumentException(
                    "ECB belgesi degil: <" + CUBE_ELEMENT + "> bulunamadi");
        }

        List<EcbDailyRates> days = new ArrayList<>();
        for (int i = 0; i < cubes.getLength(); i++) {
            Element cube = (Element) cubes.item(i);
            String date = trimmed(cube.getAttribute(TIME_ATTRIBUTE));
            if (date == null) {
                continue; // sarmalayıcı ya da kur satırı — gün değil
            }
            days.add(new EcbDailyRates(date, ratesOf(cube)));
        }
        return new EcbRatesDocument(List.copyOf(days));
    }

    /** Yalnız DOĞRUDAN çocuklar: torunlar başka bir güne aittir (bkz. sınıf açıklaması). */
    private Map<String, String> ratesOf(Element day) {
        Map<String, String> rates = new LinkedHashMap<>();
        NodeList children = day.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child) || !CUBE_ELEMENT.equals(child.getTagName())) {
                continue;
            }
            String currency = trimmed(child.getAttribute(CURRENCY_ATTRIBUTE));
            String rate = trimmed(child.getAttribute(RATE_ATTRIBUTE));
            if (currency != null && rate != null) {
                rates.put(currency, rate);
            }
        }
        return Map.copyOf(rates);
    }

    private String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
