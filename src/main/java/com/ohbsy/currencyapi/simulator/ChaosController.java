package com.ohbsy.currencyapi.simulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * T34 Faz 7 — arıza enjeksiyon ucu. Duman testleri kaosu buradan sürer; <b>servis yeniden
 * başlatılmaz</b>.
 *
 * <p>Bu, CRM tarafındaki stub'ın çözemediği sorunu çözer: {@code crm.currency.stub.mode}
 * Config deposundadır ve restart'sız çevrilemiyordu (Faz 6 bulgusu), çünkü {@code env} ucu
 * kapalı. Sahte servis <b>bize ait</b> olduğu için kendi kaos ucunu açabilir — dış bir
 * satıcının yapamayacağı tek şey de budur zaten.
 *
 * <p><b>Yol {@code /__mode}'dur ve altçizgiyle başlar</b>: satıcının gerçek yüzeyiyle
 * karışmasın. Gerçek TCMB/ECB'de böyle bir uç yoktur ve olmamalıdır — bu uç, taklidin
 * kendisinin değil <b>test edilebilirliğin</b> parçasıdır.
 *
 * <h2>Varsayılan KAPALI — pazarlıksız</h2>
 * Bu uç <b>kimlik doğrulaması istemez</b> ({@code ApiGuardFilter} {@code /__} önekini muaf
 * tutar; kaosu süren duman testinin elinde anahtar yoktur) ve <b>durum değiştirir</b>. Servis
 * bir tünelin ardından internete açıldığında bu ikisi bir arada, uzaktan erişilebilir bir
 * "servisi bozma düğmesi" demektir: {@code TCMB_BASE_URL} simülatöre çevrildiği anda
 * ({@code README}'de anlatılan tüketici testi senaryosu) yabancı biri kur akışını
 * durdurabilirdi. Bu yüzden simülatör yüzeyi ancak <b>bilinçli olarak</b>
 * {@code currency-api.simulator.enabled=true} verildiğinde vardır; verilmezse bean hiç
 * kurulmaz ve yol düz 404'tür.
 */
@RestController
@ConditionalOnProperty(name = "currency-api.simulator.enabled", havingValue = "true")
public class ChaosController {

    private final ChaosState chaos;

    public ChaosController(ChaosState chaos) {
        this.chaos = chaos;
    }

    /**
     * Bir kaynağın modunu değiştirir.
     *
     * <p>{@code source} verilmezse <b>her iki kaynak birden</b> ayarlanır. Ama asıl senaryo
     * kaynak bazlıdır: {@code source=tcmb&mode=holiday} ile TCMB düşer, ECB ayakta kalır ve
     * zincirin devraldığı görülür.
     */
    @PostMapping("/__mode")
    public ResponseEntity<Map<String, Object>> setMode(
            @RequestParam(required = false) String source,
            @RequestParam String mode) {
        ChaosState.Mode parsedMode = parseMode(mode);
        if (source == null || source.isBlank()) {
            for (ChaosState.Source each : ChaosState.Source.values()) {
                chaos.setMode(each, parsedMode);
            }
        } else {
            chaos.setMode(parseSource(source), parsedMode);
        }
        return ResponseEntity.ok(state());
    }

    /** Jitter ve gecikme süresi — kur oynatma ve zaman aşımı denemeleri için. */
    @PostMapping("/__settings")
    public ResponseEntity<Map<String, Object>> settings(
            @RequestParam(required = false) Boolean jitter,
            @RequestParam(required = false) Long delayMillis) {
        if (jitter != null) {
            chaos.setJitter(jitter);
        }
        if (delayMillis != null) {
            chaos.setDelayMillis(delayMillis);
        }
        return ResponseEntity.ok(state());
    }

    /** Tüm kaynakları normale döndürür — duman testi koşum başına çağırır (tekrarlanabilirlik). */
    @PostMapping("/__reset")
    public ResponseEntity<Map<String, Object>> reset() {
        chaos.reset();
        return ResponseEntity.ok(state());
    }

    @GetMapping("/__mode")
    public ResponseEntity<Map<String, Object>> current() {
        return ResponseEntity.ok(state());
    }

    private Map<String, Object> state() {
        Map<String, Object> body = new LinkedHashMap<>();
        chaos.modes().forEach((source, mode) ->
                body.put(source.name().toLowerCase(Locale.ROOT), mode.name().toLowerCase(Locale.ROOT)));
        body.put("jitter", chaos.isJitter());
        body.put("delayMillis", chaos.getDelayMillis());
        return body;
    }

    /**
     * Tanınmayan değer <b>sessizce yok sayılmaz</b>, 400 ile reddedilir. Kaos ucunda fail-open
     * yanlış olurdu: yazım hatası yüzünden mod değişmeseydi, duman testi "arıza enjekte ettim"
     * sanıp normal yolu ölçer ve <b>yeşil yanardı</b> — sahte pozitif, hiç test etmemekten kötüdür.
     */
    private ChaosState.Mode parseMode(String raw) {
        try {
            return ChaosState.Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bilinmeyen mode: " + raw
                    + " (success|error|timeout|garbage|holiday)");
        }
    }

    private ChaosState.Source parseSource(String raw) {
        try {
            return ChaosState.Source.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bilinmeyen source: " + raw + " (tcmb|ecb)");
        }
    }
}
