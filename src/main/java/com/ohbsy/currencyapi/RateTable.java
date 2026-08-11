package com.ohbsy.currencyapi;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * T34 Faz 7 — bellekteki kur tablosu. Değerler <b>gerçekçidir ama gerçek değildir</b>: bu servis
 * bir test ikilisidir, piyasa iddiası taşımaz.
 *
 * <p><b>İki tablo, iki farklı yön — kasıtlı.</b> TCMB "1 yabancı birim kaç TL" yayınlar, ECB
 * ise "1 EUR kaç yabancı birim". Sahte servis bu farkı <b>düzeltmez</b>: düzeltseydi CRM
 * tarafındaki iki dönüşüm (TCMB'de ters çevirme, ECB'de çapraz kur) sahte yığında hiç
 * çalışmaz ve gerçek satıcıya geçişte ilk kez orada patlardı. Sahtenin işi kolaylık sağlamak
 * değil, <b>gerçeğin zorluklarını taşımaktır</b>.
 */
@Component
public class RateTable {

    /** TCMB yönü: 1 yabancı birim = X TL. */
    private static final Map<String, BigDecimal> TRY_PER_UNIT = new LinkedHashMap<>(Map.of(
            "USD", new BigDecimal("34.0100"),
            "EUR", new BigDecimal("36.9500"),
            "GBP", new BigDecimal("43.3000"),
            "JPY", new BigDecimal("23.2000")));   // Unit=100 ile yayınlanır

    /** ECB yönü: 1 EUR = X yabancı birim. */
    private static final Map<String, BigDecimal> PER_EURO = new LinkedHashMap<>(Map.of(
            "USD", new BigDecimal("1.0876"),
            "GBP", new BigDecimal("0.8534"),
            "TRY", new BigDecimal("36.9500"),
            "JPY", new BigDecimal("159.4200")));

    private final ChaosState chaos;

    public RateTable(ChaosState chaos) {
        this.chaos = chaos;
    }

    public Map<String, BigDecimal> tryPerUnit() {
        return jittered(TRY_PER_UNIT);
    }

    public Map<String, BigDecimal> perEuro() {
        return jittered(PER_EURO);
    }

    /**
     * Jitter açıksa her kur ±%0,5 oynatılır. Amacı gerçekçilik değil <b>gözlemlenebilirliktir</b>:
     * CRM cache'i çalışırken ekrandaki tutar SABİT kalmalıdır; jitter açıkken tutarın hâlâ
     * değişmemesi, cache'in gerçekten çalıştığının çıplak gözle kanıtıdır.
     */
    private Map<String, BigDecimal> jittered(Map<String, BigDecimal> source) {
        if (!chaos.isJitter()) {
            return source;
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        source.forEach((code, rate) -> {
            double factor = 1 + ThreadLocalRandom.current().nextDouble(-0.005, 0.005);
            out.put(code, rate.multiply(BigDecimal.valueOf(factor))
                    .setScale(4, RoundingMode.HALF_UP));
        });
        return out;
    }
}
