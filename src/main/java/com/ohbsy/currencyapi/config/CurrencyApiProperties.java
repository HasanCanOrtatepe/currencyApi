package com.ohbsy.currencyapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Servis ayarları ({@code currency-api.*}).
 *
 * <p><b>Java varsayılanları bilinçlidir:</b> hiçbir yapılandırma verilmeden servis ayağa kalkar
 * ve gerçek TCMB'den kur çeker. Sıfır konfigürasyonla çalışmak, "çalıştır ve gör" için ön
 * koşuldur; ayar dosyası unutulduğunda servis sessizce yanlış bir yere bağlanmaz.
 */
@Component
@ConfigurationProperties(prefix = "currency-api")
public class CurrencyApiProperties {

    private final Cache cache = new Cache();
    private final Tcmb tcmb = new Tcmb();

    public Cache getCache() {
        return cache;
    }

    public Tcmb getTcmb() {
        return tcmb;
    }

    /** Cache pencereleri — ikisi FARKLI şeydir, bkz. {@code RateCache}. */
    public static class Cache {

        /**
         * <b>Tazelik</b> penceresi. İçindeyken satıcıya hiç gidilmez. 15 dakika, TCMB'nin
         * günlük yayın ritmi için fazlasıyla yeterlidir: satıcı zaten gün içinde
         * değişmeyen bir veri sunar, daha sık sormak yalnız kota ve gecikme üretir.
         */
        private Duration ttl = Duration.ofMinutes(15);

        /**
         * <b>Saklama</b> süresi. Tazelik dolduğunda kayıt SİLİNMEZ; "son geçerli kur" olarak
         * bu süre boyunca durur. 7 gün, en uzun resmî tatil zincirini (9 günlük bayramlar
         * hariç) ve hafta sonlarını rahatça kapsar — silinseydi tatil boyunca sistem kursuz
         * kalırdı.
         */
        private Duration retention = Duration.ofDays(7);

        /** {@code memory} | {@code redis} — çok instance'lı kurulumda {@code redis} olmalıdır. */
        private String type = "memory";

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /** TCMB sağlayıcısının ayarları. */
    public static class Tcmb {

        private String baseUrl = "https://www.tcmb.gov.tr";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);

        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * Boş değer varsayılanı <b>EZMEZ</b>. Konteyner ortamlarında yaygın bir tuzaktır:
         * compose'daki {@code ${VAR:-}} biçimi değişken verilmediğinde boş dizgi gönderir ve
         * ortam değişkeni Java varsayılanından önceliklidir — sessizce host'suz bir URL'ye
         * bağlanmaya çalışılırdı.
         */
        public void setBaseUrl(String baseUrl) {
            if (baseUrl != null && !baseUrl.isBlank()) {
                this.baseUrl = baseUrl.trim();
            }
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
