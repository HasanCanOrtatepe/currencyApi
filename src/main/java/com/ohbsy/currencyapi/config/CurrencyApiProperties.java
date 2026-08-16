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
    private final Evds evds = new Evds();
    private final Ecb ecb = new Ecb();
    private final Auth auth = new Auth();
    private final RateLimit rateLimit = new RateLimit();
    private final Admin admin = new Admin();
    private final Simulator simulator = new Simulator();

    public Cache getCache() {
        return cache;
    }

    public Tcmb getTcmb() {
        return tcmb;
    }

    public Evds getEvds() {
        return evds;
    }

    public Ecb getEcb() {
        return ecb;
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

    public Simulator getSimulator() {
        return simulator;
    }

    /**
     * Sahte satıcı yüzeyi ({@code /kurlar/**}, {@code /stats/**}) ve kaos uçları
     * ({@code /__mode}, {@code /__settings}, {@code /__reset}).
     *
     * <p><b>Varsayılan KAPALI:</b> kaos uçları kimlik doğrulaması istemez (kaosu süren duman
     * testinin elinde anahtar yoktur) ve durum değiştirir. İnternete açılmış bir serviste bu
     * ikisi bir arada uzaktan erişilebilir bir arıza düğmesidir. Yalnız tüketici testlerinde
     * ({@code CURRENCY_SIMULATOR_ENABLED=true}) açılır.
     */
    public static class Simulator {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
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

        /**
         * Anonim isteğin adresini taşıyan başlık (ör. {@code CF-Connecting-IP}) — <b>varsayılan
         * BOŞ, yani kapalı</b>.
         *
         * <p>Tünel arkasında {@code getRemoteAddr()} her internet isteği için aynı adresi verir
         * ve "kota IP başına" sözü boşa düşer (bkz. {@code ApiClientResolver.clientIp}). Bu ayar
         * o durumu düzeltir; ancak <b>yalnız</b> portun önünde başlığı kendisi YAZAN bir vekil
         * varsa verilmelidir. Portu doğrudan güvenilmeyen bir ağa açan kurulumda başlık
         * uydurulabilir olurdu — bu yüzden varsayılan "güvenme"dir ve açmak bilinçli bir
         * dağıtım kararıdır ({@code Auth.enabled} ile aynı desen).
         */
        private String clientIpHeader = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientIpHeader() {
            return clientIpHeader;
        }

        /** Yer tutucu değerler ({@code __unset__}) elenir — {@code Evds#setKey} ile aynı tuzak. */
        public void setClientIpHeader(String clientIpHeader) {
            String trimmed = clientIpHeader == null ? "" : clientIpHeader.trim();
            this.clientIpHeader = trimmed.startsWith("__") ? "" : trimmed;
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
     * EVDS sağlayıcısının ayarları — TCMB'nin veri dağıtım sistemi.
     *
     * <p><b>{@link #key} tek anahtardır ve aynı zamanda AÇMA/KAPAMA düğmesidir:</b> boşsa
     * sağlayıcı zincire hiç girmez ve servis anahtarsız kurulumdaki davranışını aynen sürdürür
     * ({@code today.xml}). Ayrı bir {@code enabled} bayrağı bilinçli olarak YOKTUR — iki
     * düğmenin çelişebildiği bir yapılandırma ("açık ama anahtarsız") sessiz bir arıza
     * kaynağıdır; burada çelişki kurulamaz.
     *
     * <p><b>Anahtar depoya girmez</b> — yalnız ortam değişkeninden ({@code .env}). EVDS onu
     * HTTP <b>başlığında</b> beklediği için URL'lerde de görünmez; log ve stack trace'lerde
     * URL basmak güvenlidir.
     */
    public static class Evds {

        private String baseUrl = "https://evds3.tcmb.gov.tr";

        /** {@code key} başlığıyla gönderilir. Boş = sağlayıcı devre dışı. */
        private String key = "";

        /**
         * Kaç günlük geriye bakılacağı. Tek gün sorulsaydı hafta sonu, resmî tatil ve
         * bültenin henüz yayınlanmadığı sabah saatlerinde cevap boş dönerdi; en yeni
         * <b>dolu</b> gün seçilir. 10 gün, en uzun bayram zincirini kapsar.
         */
        private Duration lookback = Duration.ofDays(10);

        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);

        public String getBaseUrl() {
            return baseUrl;
        }

        /** Boş değer varsayılanı EZMEZ — {@link Tcmb#setBaseUrl} ile aynı konteyner tuzağı. */
        public void setBaseUrl(String baseUrl) {
            if (baseUrl != null && !baseUrl.isBlank()) {
                this.baseUrl = baseUrl.trim();
            }
        }

        public String getKey() {
            return key;
        }

        /**
         * Yer tutucu değerler ({@code __unset__} gibi) elenir: {@code .env} doldurulmadığında
         * servis, geçersiz bir anahtarla her 15 dakikada bir EVDS'ten 401 toplamamalıdır.
         */
        public void setKey(String key) {
            String trimmed = key == null ? "" : key.trim();
            this.key = trimmed.startsWith("__") ? "" : trimmed;
        }

        public Duration getLookback() {
            return lookback;
        }

        public void setLookback(Duration lookback) {
            this.lookback = lookback;
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
     * ECB sağlayıcısının ayarları — Avrupa Merkez Bankası, zincirin son ve tek <b>bağımsız</b>
     * basamağı.
     *
     * <p><b>Neden {@link #enabled} bayrağı var, EVDS'te yokken:</b> EVDS'te anahtarın kendisi
     * düğmedir ("açık ama anahtarsız" çelişkisi kurulamaz). ECB anahtar istemez, yani orada
     * kullanılan hile burada kurulamaz ve açık bir bayrak gerekir.
     *
     * <p><b>Varsayılan KAPALI ve bu bilinçlidir:</b> bu sağlayıcı devreye girdiğinde tüketiciye
     * sunulan sayı <b>başka bir kurumun</b> kuru olur (ECB referans kuru ≠ TCMB resmî satış
     * kuru). Görünürdür — cevapta {@code provider: "ecb"} yazar — ama yine de bir davranış
     * değişikliğidir ve bunu bilmeyen bir kurulum, imaj güncellemesiyle sessizce devralmamalıdır
     * ({@code Auth.enabled} ve {@code RateLimit.clientIpHeader} ile aynı ilke).
     */
    public static class Ecb {

        private boolean enabled = false;

        private String baseUrl = "https://www.ecb.europa.eu";

        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        /** Boş değer varsayılanı EZMEZ — {@link Tcmb#setBaseUrl} ile aynı konteyner tuzağı. */
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
         * Admin panelinin izinli origin'leri (virgülle ayrılır) — yalnız {@code /admin/**} için.
         * Genel/public API'ye (tarayıcı istemcisi hiç olmayan) dokunulmaz.
         *
         * <p><b>Neden iki değer:</b> panel yalnız loopback'e bağlıdır, ama tarayıcı adres
         * çubuğuna {@code localhost} da {@code 127.0.0.1} de yazılabilir ve {@code Origin}
         * başlığında hangisi yazıldıysa o gider. İkisi de listede olmasaydı, kullanıcının
         * hangi yazımı seçtiğine bağlı olarak panel sessizce çalışmazdı.
         *
         * <p>Önceki değer {@code http://*:8096} idi (host serbest): panel LAN'a açıkken
         * gerekiyordu. Loopback'e çekildikten sonra o gevşeklik gereksizdir.
         */
        private String corsOriginPattern = "http://localhost:8096,http://127.0.0.1:8096";

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
