package com.ohbsy.currencyapi.core.utilities;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;

/**
 * Dış XML'i <b>güvenli</b> okuma. Ayrıştırıcının güvenli kurulumu ortaktır ve tek yerde durur;
 * satıcıya özgü olan yalnız XML'in <i>anlamıdır</i>.
 *
 * <h2>Neden varsayılan ayrıştırıcı kullanılmaz</h2>
 * JAXP varsayılanı harici varlıkları (external entity) çözer. Satıcı güvenilir görünse de cevabı
 * <b>ağdan gelir</b>: araya giren ya da ele geçirilmiş bir kaynak
 * {@code <!ENTITY xxe SYSTEM "file:///etc/passwd">} gönderip servisin dosya sistemini veya iç
 * ağını okutabilir (XXE) — istek <b>bizim</b> sunucumuzdan çıktığı için ağ sınırı korumaz.
 * Burada DOCTYPE'ın tamamı reddedilir: TCMB/ECB belgelerinde DOCTYPE yoktur, yani yasak meşru
 * bir cevabı düşürmez; düşürseydi de doğru davranış zaten "bu yükü kullanma"dır.
 */
public final class SecureXml {

    private SecureXml() {
    }

    /**
     * @throws IllegalArgumentException belge ayrıştırılamazsa. Çağıran sağlayıcı bunu
     *         {@code ProviderUnavailableException}'a çevirmekle yükümlüdür — ham ayrıştırma
     *         hatası iş katmanına sızmaz.
     */
    public static Document parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML govdesi bos");
        }
        try {
            return newBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML ayristirilamadi: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder newBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // Asıl kilit: DOCTYPE yoksa harici varlık da tanımlanamaz (XXE yapısal olarak biter).
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        // Ad alanı duyarsız: elemanlar yerel adla aranır. Ad alanı öneki satıcının biçim
        // kararıdır; değişirse okumamız kırılmamalı (tolerant reader).
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder();
    }
}
