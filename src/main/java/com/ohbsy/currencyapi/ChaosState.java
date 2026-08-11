package com.ohbsy.currencyapi;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T34 Faz 7 — arıza enjeksiyonu durumu. Modlar <b>kaynak BAŞINA</b> tutulur ve bu, bu servisin
 * asıl değeridir:
 *
 * <p>Yeni mimarinin sınanacak davranışı "kur kaynağı çöktü" değil <b>"TCMB çöktü ama ECB
 * ayakta"</b>dır — yani zincirin devralması. Tek bir küresel mod bunu ifade edemezdi; iki
 * kaynak birlikte düşer, yedekleme yolu hiç çalışmazdı ve {@code FallbackExchangeRateClient}
 * canlı yığında bir kez bile sınanmamış olurdu.
 */
@Component
public class ChaosState {

    /** Kaynağın hangi arızayı üreteceği. */
    public enum Mode {
        /** Normal cevap. */
        SUCCESS,
        /** 500 — sunucu hatası. */
        ERROR,
        /**
         * Gecikme. <b>Stub'ın aksine GERÇEKTEN bekler</b> ve bu karşıtlık bilinçlidir:
         * {@code StubExchangeRateClient} beklemeden kısa devre yapar çünkü orada ölçülen şey
         * "bizim kodumuz bloke olmuyor mu"dur. Burada ölçülen şey ise <b>HTTP zaman aşımımızın
         * gerçekten çalışıp çalışmadığıdır</b> — beklemeyen bir sahte servis onu sınayamaz.
         */
        TIMEOUT,
        /** Sözdizimi bozuk XML — tolerant reader'ın değil, ayrıştırıcının sınırı. */
        GARBAGE,
        /**
         * 404 — <b>TCMB'nin hafta sonu/tatil davranışı.</b> Planın {@code unauthorized} modunun
         * yerini aldı: seçilen iki satıcı da anahtarsızdır, dolayısıyla 401 üretmek gerçekte
         * karşılığı olmayan bir senaryo olurdu. 404 ise takvimden bilinen, <b>her hafta yaşanan</b>
         * durumdur ve yedeğin var oluş sebebidir.
         */
        HOLIDAY
    }

    /** Kaynak kimliği — CRM tarafındaki {@code providerName()} değerleriyle aynı sözcükler. */
    public enum Source { TCMB, ECB }

    private final Map<Source, Mode> modes = new ConcurrentHashMap<>(new EnumMap<>(Map.of(
            Source.TCMB, Mode.SUCCESS,
            Source.ECB, Mode.SUCCESS)));

    /** Kurların her istekte hafifçe oynaması — cache/bayatlık davranışı gözle görülür olsun. */
    private volatile boolean jitter = false;

    /** {@link Mode#TIMEOUT} modunda beklenecek süre (ms). */
    private volatile long delayMillis = 10_000;

    public Mode mode(Source source) {
        return modes.getOrDefault(source, Mode.SUCCESS);
    }

    public void setMode(Source source, Mode mode) {
        modes.put(source, mode);
    }

    public Map<Source, Mode> modes() {
        return Map.copyOf(modes);
    }

    public boolean isJitter() {
        return jitter;
    }

    public void setJitter(boolean jitter) {
        this.jitter = jitter;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    /** Tüm kaynakları normale döndürür — duman testleri koşum başına bunu çağırır. */
    public void reset() {
        modes.replaceAll((source, mode) -> Mode.SUCCESS);
        jitter = false;
        delayMillis = 10_000;
    }
}
