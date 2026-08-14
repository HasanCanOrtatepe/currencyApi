package com.ohbsy.currencyapi.core.integrations.evds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohbsy.currencyapi.core.integrations.evds.dtos.EvdsSeriesDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EVDS cevabının okunması — <b>yalnız okuma</b>. Kurun geçerliliği, günü, birimi burada
 * sorgulanmaz; bu ayrım sayesinde EVDS bir alan adını değiştirdiğinde hata "geçersiz kur"
 * diye görünüp yanlış yerde aranmaz.
 */
@DisplayName("EvdsJsonReader — JSON → DTO")
class EvdsJsonReaderTest {

    private final EvdsJsonReader reader = new EvdsJsonReader(new ObjectMapper());

    /** EVDS'ten 14-08-2026'da alınmış gerçek cevabın kısaltılmışı. */
    private static final String REAL_RESPONSE = """
            {"totalCount":3,"items":[
              {"Tarih":"12-08-2026","TP_DK_USD_S":"47.73640000","TP_DK_EUR_S":"55.01000000",
               "UNIXTIME":{"$numberLong":"1786482000"}},
              {"Tarih":"13-08-2026","TP_DK_USD_S":"47.75370000","TP_DK_EUR_S":"55.04000000",
               "UNIXTIME":{"$numberLong":"1786568400"}},
              {"Tarih":"14-08-2026","TP_DK_USD_S":"47.77170000","TP_DK_EUR_S":"55.07440000",
               "UNIXTIME":{"$numberLong":"1786654800"}}]}""";

    @Test
    @DisplayName("Tarih ve kur sütunları okunur")
    void readsDatesAndColumns() {
        EvdsSeriesDocument document = reader.read(REAL_RESPONSE);

        assertThat(document.totalCount()).isEqualTo(3);
        assertThat(document.days()).extracting("date")
                .containsExactly("12-08-2026", "13-08-2026", "14-08-2026");
        assertThat(document.days().get(2).valueOf("TP_DK_USD_S")).isEqualTo("47.77170000");
    }

    /**
     * {@code UNIXTIME} bir NESNEDİR ({@code {"$numberLong": ...}}). Kur sütunu gibi taşınsaydı
     * "tam gün" kontrolü onu da sayar ve satırın eksikliğini gizlerdi.
     */
    @Test
    @DisplayName("Metin olmayan alanlar taşınmaz — UNIXTIME kur sütunu sanılmaz")
    void ignoresNonTextualFields() {
        EvdsSeriesDocument document = reader.read(REAL_RESPONSE);

        assertThat(document.days().get(0).values()).containsOnlyKeys(
                "TP_DK_USD_S", "TP_DK_EUR_S");
    }

    /**
     * Yayın olmayan gün satırı SİLİNMEZ, değerleri {@code null} gelir. Boş harita, "o gün
     * yayın yok" bilgisini aşağıya taşımanın en sade yoludur.
     */
    @Test
    @DisplayName("null değerli gün boş satır olarak okunur — hafta sonu")
    void nullValuesBecomeEmptyDay() {
        EvdsSeriesDocument document = reader.read("""
                {"totalCount":1,"items":[{"Tarih":"08-08-2026","TP_DK_USD_S":null}]}""");

        assertThat(document.days().get(0).hasNoValues()).isTrue();
        assertThat(document.days().get(0).date()).isEqualTo("08-08-2026");
    }

    @Test
    @DisplayName("Boş aralık okunur — hata DEĞİL")
    void readsEmptyRange() {
        EvdsSeriesDocument document = reader.read("""
                {"totalCount":0,"items":[]}""");

        assertThat(document.days()).isEmpty();
        assertThat(document.totalCount()).isZero();
    }

    /** Elimizdeki şey EVDS cevabı değilse okuma başarısızdır — sessizce boş dönmez. */
    @Test
    @DisplayName("items yoksa ya da bozuk JSON ise istisna")
    void rejectsForeignPayloads() {
        assertThatThrownBy(() -> reader.read("""
                {"message":"Required request header 'key' is not present"}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");

        assertThatThrownBy(() -> reader.read("bu json degil"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Tanımadığımız sütunlar sessizce taşınır; okuyucu yorumlamaz. */
    @Test
    @DisplayName("Bilinmeyen sütunlar belgeyi düşürmez")
    void toleratesUnknownColumns() {
        EvdsSeriesDocument document = reader.read("""
                {"totalCount":1,"items":[
                  {"Tarih":"14-08-2026","TP_DK_USD_S":"47.77","TP_YENI_SERI":"1.0"}]}""");

        assertThat(document.days().get(0).valueOf("TP_YENI_SERI")).isEqualTo("1.0");
    }
}
