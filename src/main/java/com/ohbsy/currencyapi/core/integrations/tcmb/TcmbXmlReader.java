package com.ohbsy.currencyapi.core.integrations.tcmb;

import com.ohbsy.currencyapi.core.integrations.tcmb.dtos.TcmbCurrencyRow;
import com.ohbsy.currencyapi.core.integrations.tcmb.dtos.TcmbRatesDocument;
import com.ohbsy.currencyapi.core.utilities.SecureXml;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>XML → DTO.</b> Zincirin ilk adımı: belgeyi okur, hiçbir şeyi yorumlamaz.
 *
 * <p>Bu ayrım kasıtlıdır — burada "sayı geçerli mi", "kur pozitif mi", "yön doğru mu" gibi
 * sorular <b>sorulmaz</b>. Okuma ile yorumlama aynı sınıfta olsaydı, TCMB bir eleman adını
 * değiştirdiğinde hata "geçersiz kur" olarak görünür ve yanlış yerde aranırdı.
 *
 * <p><b>Tolerant reader:</b> tanımadığımız elemanlar/öznitelikler yok sayılır, eksik alan
 * {@code null} olarak taşınır. Belgenin kök elemanı bulunamazsa istisna fırlatır — o noktada
 * elimizdeki şey TCMB belgesi değildir.
 */
@Component
public class TcmbXmlReader {

    private static final String ROOT_ELEMENT = "Tarih_Date";
    private static final String CURRENCY_ELEMENT = "Currency";
    private static final String DATE_ATTRIBUTE = "Tarih";
    private static final String CODE_ATTRIBUTE = "Kod";

    /**
     * @throws IllegalArgumentException belge okunamazsa (bozuk XML, DOCTYPE, yanlış kök)
     */
    public TcmbRatesDocument read(String xml) {
        Document document = SecureXml.parse(xml);

        NodeList roots = document.getElementsByTagName(ROOT_ELEMENT);
        if (roots.getLength() == 0) {
            throw new IllegalArgumentException(
                    "TCMB belgesi degil: <" + ROOT_ELEMENT + "> bulunamadi");
        }
        String date = ((Element) roots.item(0)).getAttribute(DATE_ATTRIBUTE);

        NodeList currencies = document.getElementsByTagName(CURRENCY_ELEMENT);
        List<TcmbCurrencyRow> rows = new ArrayList<>(currencies.getLength());
        for (int i = 0; i < currencies.getLength(); i++) {
            Element currency = (Element) currencies.item(i);
            rows.add(new TcmbCurrencyRow(
                    trimmed(currency.getAttribute(CODE_ATTRIBUTE)),
                    childText(currency, "Unit"),
                    childText(currency, "ForexBuying"),
                    childText(currency, "ForexSelling")));
        }
        return new TcmbRatesDocument(trimmed(date), rows);
    }

    private String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : trimmed(nodes.item(0).getTextContent());
    }

    private String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
