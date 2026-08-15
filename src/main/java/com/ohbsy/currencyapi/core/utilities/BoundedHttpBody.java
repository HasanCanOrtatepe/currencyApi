package com.ohbsy.currencyapi.core.utilities;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;

/**
 * Dış satıcının cevap gövdesini <b>üst sınırlı</b> okuma. {@link SecureXml} ile aynı gerekçeyle
 * burada durur: satıcıdan bağımsız, paylaşılan bir güvenlik detayı, tek yerde.
 *
 * <h2>Neden {@code BodyHandlers.ofString()} kullanılmıyor</h2>
 * O yol gövdenin TAMAMINI, boyutuna bakmadan belleğe alır. Satıcı güvenilir görünse de cevap
 * <b>ağdan gelir</b>: ele geçirilmiş ya da bozulmuş bir kaynağın (veya yanlış yapılandırılmış
 * bir {@code base-url}'in) döndüreceği devasa bir gövde, servisin yığınını tüketirdi. Konteyner
 * {@code -XX:+ExitOnOutOfMemoryError} ile çalıştığı için sonuç sessiz bir yavaşlama değil,
 * sürecin ölmesi ve yeniden başlaması olurdu — yani her istekte tekrarlanabilir bir kesinti.
 *
 * <p>Sınır aşıldığında gövdenin geri kalanı okunmaz: akış kapatılır ve bağlantı bırakılır.
 * Sayı gerçek belgelerin (TCMB {@code today.xml} ~9 KB, EVDS serisi birkaç yüz KB) yanında
 * cömerttir; amacı normal bir büyümeyi kırmak değil, sınırsızlığı kaldırmaktır.
 *
 * <h2>Karakter kümesi</h2>
 * {@code Content-Type} başlığındaki {@code charset} kullanılır, yoksa UTF-8 — yani
 * {@code ofString()} ile <b>aynı</b> çözümleme. TCMB {@code application/xml} döner (charset'siz),
 * dolayısıyla davranış değişmez.
 */
public final class BoundedHttpBody {

    /** Cömert bir tavan: en büyük gerçek belge bunun binde biri kadardır. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    private BoundedHttpBody() {
    }

    /**
     * Gövdeyi en çok {@link #MAX_BYTES} bayt okur.
     *
     * @throws TooLargeException sınır aşılırsa. Çağıran sağlayıcı bunu
     *         {@code ProviderUnavailableException}'a çevirmekle yükümlüdür — ham okuma hatası
     *         iş katmanına sızmaz ({@link SecureXml} ile aynı sözleşme).
     * @throws IOException akış okunamazsa (taşıma arızası).
     */
    public static String read(InputStream body, HttpHeaders headers) throws IOException {
        // Sınırdan BİR fazlası istenir: dönen dizi sınırı aşıyorsa gövde de aşmış demektir.
        byte[] bytes = body.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new TooLargeException(
                    "cevap govdesi " + MAX_BYTES + " bayt sinirini asti");
        }
        return new String(bytes, charsetOf(headers));
    }

    /** Sınır aşımı bir <b>yük</b> sorunudur, taşıma arızası değil — ayrı tip, ayrı sınıflandırma. */
    public static class TooLargeException extends RuntimeException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    private static Charset charsetOf(HttpHeaders headers) {
        return headers.firstValue("content-type")
                .map(BoundedHttpBody::parseCharset)
                .orElse(StandardCharsets.UTF_8);
    }

    private static Charset parseCharset(String contentType) {
        for (String part : contentType.split(";")) {
            String token = part.trim();
            if (token.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String name = token.substring("charset=".length()).trim().replace("\"", "");
                try {
                    return Charset.forName(name);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    // Satıcının bildirdiği küme tanınmıyorsa UTF-8'e düşülür: burada patlamak,
                    // okunabilir bir belgeyi sırf etiketi yüzünden çöpe atmak olurdu.
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
