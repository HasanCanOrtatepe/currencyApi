package com.ohbsy.currencyapi.core.utilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sınanan şey satıcının cevabı değil <b>bizim kararımız</b>: gövdeyi boyutuna bakmadan belleğe
 * almayı reddetmek. Bu testler olmasa sınır ilk kez üretimde, devasa bir cevap geldiğinde
 * sınanırdı — ve sınav sonucu sürecin ölmesi olurdu ({@code -XX:+ExitOnOutOfMemoryError}).
 */
@DisplayName("BoundedHttpBody — üst sınırlı gövde okuma")
class BoundedHttpBodyTest {

    private static HttpHeaders headers(String contentType) {
        Map<String, List<String>> map = contentType == null
                ? Map.of()
                : Map.of("content-type", List.of(contentType));
        return HttpHeaders.of(map, (k, v) -> true);
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    @Test
    @DisplayName("Sınırın altındaki gövde aynen okunur")
    void readsBodyUnderLimit() throws Exception {
        String xml = "<Tarih_Date Tarih=\"14.08.2026\"><Currency/></Tarih_Date>";

        String result = BoundedHttpBody.read(
                stream(xml.getBytes(StandardCharsets.UTF_8)), headers("application/xml"));

        assertThat(result).isEqualTo(xml);
    }

    @Test
    @DisplayName("Tam sınır kadar gövde HÂLÂ kabul edilir (sınır dahil)")
    void acceptsExactlyMaxBytes() throws Exception {
        byte[] body = new byte[BoundedHttpBody.MAX_BYTES];
        java.util.Arrays.fill(body, (byte) 'a');

        assertThat(BoundedHttpBody.read(stream(body), headers(null)))
                .hasSize(BoundedHttpBody.MAX_BYTES);
    }

    /**
     * Asıl korunan şey budur: sınırsız bir gövde yığını tüketir, süreç ölür ve konteyner
     * yeniden başlar — yani her istekte tekrarlanabilir bir kesinti.
     */
    @Test
    @DisplayName("Sınırı AŞAN gövde reddedilir")
    void rejectsBodyOverLimit() {
        byte[] body = new byte[BoundedHttpBody.MAX_BYTES + 1];

        assertThatThrownBy(() -> BoundedHttpBody.read(stream(body), headers(null)))
                .isInstanceOf(BoundedHttpBody.TooLargeException.class)
                .hasMessageContaining("sinirini asti");
    }

    @Test
    @DisplayName("Content-Type'taki charset kullanılır")
    void honorsDeclaredCharset() throws Exception {
        String text = "TÜRK LİRASI";

        String result = BoundedHttpBody.read(
                stream(text.getBytes("ISO-8859-9")),
                headers("application/xml; charset=ISO-8859-9"));

        assertThat(result).isEqualTo(text);
    }

    /** TCMB {@code application/xml} döner (charset'siz) — davranış {@code ofString()} ile aynı. */
    @Test
    @DisplayName("Charset bildirilmemişse UTF-8")
    void defaultsToUtf8() throws Exception {
        String text = "TÜRK LİRASI";

        assertThat(BoundedHttpBody.read(
                stream(text.getBytes(StandardCharsets.UTF_8)), headers("application/xml")))
                .isEqualTo(text);
    }

    /** Okunabilir bir belgeyi sırf etiketi tanınmadı diye çöpe atmak yanlış olurdu. */
    @Test
    @DisplayName("Tanınmayan charset UTF-8'e düşer, patlamaz")
    void unknownCharsetFallsBackToUtf8() throws Exception {
        String text = "USD";

        assertThat(BoundedHttpBody.read(
                stream(text.getBytes(StandardCharsets.UTF_8)),
                headers("application/xml; charset=boyle-bir-sey-yok")))
                .isEqualTo(text);
    }
}
