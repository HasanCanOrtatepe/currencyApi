package com.ohbsy.currencyapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final Auth auth = new Auth();
    private final RateLimit rateLimit = new RateLimit();
    private final Admin admin = new Admin();

    public Cache getCache() {
        return cache;
    }

    public Tcmb getTcmb() {
        return tcmb;
    }

    public Auth getAuth() {
        return auth;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Admin getAdmin() {
        return admin;
    }

    /**
     * API anahtarı doğrulaması.
     *
     * <p><b>Varsayılan KAPALI ve bu bilinçlidir:</b> açık gelseydi anahtar tanımlamamış her
     * kurulum bir anda 401 alırdı. Açmak bir dağıtım kararıdır; açıldığında ise anahtarsız
     * istek kesinlikle reddedilir.
     *
     * <p><b>Anahtar değerleri koda/Config'e YAZILMAZ</b> — yalnız ortam değişkeninden
     * (`.env`) gelir. Depoya giren bir anahtar, geri alınamaz biçimde yanmıştır.
     */
    public static class Auth {

        private boolean enabled = false;

        /**
         * {@code anahtar → tüketici adı}. Ad yalnız log/metrik içindir: "hangi tüketici kotayı
         * doldurdu" sorusu anahtarla değil <b>adla</b> cevaplanmalıdır, çünkü anahtar loglara
         * girmemelidir.
         */
        private Map<String, String> keys = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, String> getKeys() {
            return keys;
        }

        /**
         * Anahtarlar <b>tek bir dizgiden</b> okunur: {@code "anahtar1=crm,anahtar2=reporting"}.
         *
         * <p><b>Neden map değil</b> (ölçülerek öğrenildi): YAML'de map ANAHTARI içindeki
         * {@code ${ENV}} yer tutucusu <b>çözülmez</b> — yer tutucu çözümü değerlere uygulanır,
         * anahtar adlarına değil. {@code "[${CURRENCY_CRM_KEY}]": crm} yazımı, ortam
         * değişkeninin adını harfi harfine anahtar olarak kaydeder ve doğru anahtarla gelen
         * istek bile 401 alır. Arıza sessizdir: yapılandırma doğru <i>görünür</i>.
         * Tek dizgi hem env'den güvenle gelir hem de sırrı YAML'e yazmaya gerek bırakmaz.
         */
        public void setKeySpec(String spec) {
            Map<String, String> parsed = new LinkedHashMap<>();
            if (spec != null && !spec.isBlank()) {
                for (String entry : spec.split(",")) {
                    String[] parts = entry.split("=", 2);
                    String key = parts[0].trim();
                    // Tanımsız/yer tutucu değerler sessizce elenir: .env doldurulmadığında
                    // servis "__unset__" diye bir anahtarı geçerli saymamalıdır.
                    if (key.isEmpty() || key.startsWith("__")) {
                        continue;
                    }
                    parsed.put(key, parts.length > 1 && !parts[1].isBlank()
                            ? parts[1].trim() : "unnamed");
                }
            }
            this.keys = parsed;
        }
    }

    /**
     * İstek hızı sınırı — <b>doğru davranan tüketiciyi kısmak için DEĞİL</b>, yanlış davrananın
     * yarıçapını sınırlamak için.
     *
     * <p>Cache'ini kullanan bir tüketici (CRM: 15 dk) saatte ~4 istek atar ve bu sınıra asla
     * yaklaşmaz. Sınır, cache'i devre dışı kalan ya da döngüye giren bir tüketici içindir —
     * ve varsayılan bilinçli olarak <b>cömerttir</b>: CRM'in kendi Redis'i düşerse cache
     * fail-open olduğu için her isteği bize gelir; o an tüketiciyi büsbütün durdurmak,
     * zaten bozulmuş bir durumu servis kesintisine çevirirdi.
     */
    public static class RateLimit {

        private boolean enabled = true;

        /** Pencere başına izin verilen istek sayısı. */
        private int limit = 120;

        /** Sabit pencere uzunluğu. */
        private Duration window = Duration.ofMinutes(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
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

    /**
     * Anahtar yönetimi admin API'si ({@code /admin/keys}).
     *
     * <p><b>Varsayılan KAPALI — {@code Auth.enabled} ile aynı gerekçe, artı bir tane daha:</b>
     * açık gelseydi token tanımlamamış her kurulum açılışta düşerdi — ve bu servisin zaten
     * CANLI çalışan bir kurulumu varsa, bu özelliğin eklendiği bir imaj güncellemesi o
     * kurulumu {@code .env} güncellenmeden kırardı. Kapalı varsayılan, yükseltmeyi
     * etkisiz/kesintisiz kılar; özellik yalnız bilinçli olarak açıldığında devreye girer.
     *
     * <p><b>Admin uçları {@code server.port} DIŞINDA ayrı bir portta sunulur</b> (bkz. {@link
     * #getPort()}) — bu servisin herkese açık tünellenmesi (ör. Cloudflare quick tunnel)
     * TÜM portu yönlendirir, path bazlı bir filtre yeterli olmazdı. Ayrı port, admin
     * yüzeyinin internetten yapısal olarak erişilemez olmasını sağlar; token kontrolü
     * ({@code AdminAuthFilter}) bunun üzerine ikinci bir savunma katmanıdır, tek başına
     * sınır değildir.
     */
    public static class Admin {

        private boolean enabled = false;

        /** {@code X-Admin-Token} ile karşılaştırılır. Depoya YAZILMAZ, yalnız ortam değişkeninden. */
        private String token = "";

        private int port = 8097;

        /**
         * Angular admin-ui'nin origin'i için bir DESEN — yalnız {@code /admin/**} için CORS'a
         * izin verilir. Genel/public API'ye (tarayıcı istemcisi hiç olmayan) dokunulmaz.
         *
         * <p><b>Neden sabit bir origin değil, desen:</b> admin-ui aynı LAN'daki başka bir
         * cihazdan {@code http://<mac-ip>:8096} ile açılabilir; tarayıcı isteğin {@code Origin}
         * başlığında GERÇEKTEN bağlandığı adresi gönderir ({@code http://192.168.1.5:8096}),
         * {@code localhost} değil. Sabit {@code http://localhost:8096} yalnız Mac'in kendi
         * tarayıcısından erişimi karşılardı, LAN'daki diğer cihazlardan gelen isteği CORS
         * sessizce reddederdi. {@code http://*:8096} deseni portu sabit tutar, host'u serbest
         * bırakır.
         */
        private String corsOriginPattern = "http://*:8096";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getCorsOriginPattern() {
            return corsOriginPattern;
        }

        public void setCorsOriginPattern(String corsOriginPattern) {
            this.corsOriginPattern = corsOriginPattern;
        }
    }
}
