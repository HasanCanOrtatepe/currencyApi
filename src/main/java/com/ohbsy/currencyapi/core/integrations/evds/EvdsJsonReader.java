package com.ohbsy.currencyapi.core.integrations.evds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohbsy.currencyapi.core.integrations.evds.dtos.EvdsObservation;
import com.ohbsy.currencyapi.core.integrations.evds.dtos.EvdsSeriesDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>JSON → DTO.</b> {@code TcmbXmlReader} ile aynı rolün EVDS karşılığı: belgeyi okur,
 * hiçbir şeyi yorumlamaz. "Kur pozitif mi", "hangi gün geçerli", "birim çarpanı kaç" soruları
 * burada <b>sorulmaz</b> — okuma ile yorumlama ayrı sınıflardadır ki EVDS bir alan adını
 * değiştirdiğinde hata "geçersiz kur" diye görünüp yanlış yerde aranmasın.
 *
 * <h2>Neden DTO'lar Jackson'a bağlanmadı</h2>
 * Sütun adları çalışma zamanında belirlenir ({@code TP.DK.USD.S} → {@code TP_DK_USD_S}), bu
 * yüzden sabit alanlı bir sınıfa bağlanamaz. Ağaç üzerinde yürümek, DTO'ları da satıcıya özgü
 * Jackson anotasyonlarından temiz tutar.
 *
 * <h2>Tolerant reader</h2>
 * Tanımadığımız alanlar yok sayılır. Metin OLMAYAN alanlar da taşınmaz — {@code UNIXTIME}
 * bir nesnedir ({@code {"$numberLong": ...}}) ve kur sütunu gibi görünmesinin hiçbir faydası
 * yoktur. {@code items} bir dizi değilse istisna fırlatılır: o noktada elimizdeki şey EVDS
 * cevabı değildir.
 */
@Component
public class EvdsJsonReader {

    private static final String ITEMS_FIELD = "items";
    private static final String TOTAL_COUNT_FIELD = "totalCount";
    private static final String DATE_FIELD = "Tarih";

    private final ObjectMapper objectMapper;

    public EvdsJsonReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @throws IllegalArgumentException belge okunamazsa (bozuk JSON, {@code items} yok/dizi değil)
     */
    public EvdsSeriesDocument read(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("EVDS cevabi ayristirilamadi", e);
        }

        JsonNode items = root.path(ITEMS_FIELD);
        if (!items.isArray()) {
            throw new IllegalArgumentException(
                    "EVDS cevabi degil: '" + ITEMS_FIELD + "' dizisi yok");
        }

        List<EvdsObservation> days = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            days.add(toObservation(item));
        }
        return new EvdsSeriesDocument(root.path(TOTAL_COUNT_FIELD).asInt(days.size()), days);
    }

    private EvdsObservation toObservation(JsonNode item) {
        String date = item.path(DATE_FIELD).isTextual() ? item.path(DATE_FIELD).asText() : null;

        Map<String, String> values = new LinkedHashMap<>();
        item.properties().forEach(field -> {
            // Boş gün (hafta sonu/tatil) null değer taşır; haritaya HİÇ girmez, böylece
            // "sütun yok" ile "sütun var ama boş" ayrımını aşağıya taşımak gerekmez.
            if (!DATE_FIELD.equals(field.getKey()) && field.getValue().isTextual()) {
                values.put(field.getKey(), field.getValue().asText());
            }
        });

        return new EvdsObservation(date, values);
    }
}
