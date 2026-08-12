# CLAUDE.md

Bu dosya Claude Code'a bu repoda çalışırken rehberlik eder.
**Doküman, yorum ve commit dili Türkçe'dir** — bu dilde devam et.

## Proje

`currency-api` — TCMB'yi ana veri kaynağı alan bağımsız kur mikroservisi. Java 25 +
Spring Boot 4, tek modüllü Maven projesi. Veritabanı yok, Config Server'dan okumaz, servis
kayıt defterine kaydolmaz; tüketicileri onu URL ile bulur.

**Tüketicisi:** [heyitsyigit/Akademi_CRM_Lite](https://github.com/heyitsyigit/Akademi_CRM_Lite)
(Etiya Akademi CRM Lite, `dev` dalı). Bu servis **bilinçli olarak o reponun DIŞINDADIR**:
taklit ettiği şey bir *dış* hizmettir; CRM ile birlikte derlenip sürümlenseydi "dış hizmete
bağımlı değiliz" iddiasını sınayan şeyin kendisi CRM'in parçası olurdu. Aynı gerekçeyle
`admin-ui/` de Maven reaktörüne dahil değildir.

Kullanıcıya görünen ürün adı **Pair 3 Kur Servisi**'dir (tanıtım sayfası + admin paneli);
`currency-api` teknik/depo adıdır ve kod, imaj, ortam değişkeni adlarında DEĞİŞMEZ.

## Komutlar

```bash
mvn test                       # 95 birim testi — ALTYAPISIZ (Redis/ağ gerekmez)
mvn spring-boot:run            # http://localhost:8095
podman compose up -d --build   # tam yığın: redis + api + admin api + admin panel

cd admin-ui && npx ng test --watch=false   # 25 birim testi
cd admin-ui && npx ng build                # prod build
```

Java 25 gerekir (`pom.xml` → `java.version=25`). Homebrew Maven'ı varsayılan olarak başka bir
JDK'ya bakabilir; `JAVA_HOME` Java 25'i işaret etmelidir.

## Mimari

```
api/                 HTTP sözleşmesi — filtreler + controller'lar + DTO'lar
business/            abstracts/ (arayüz) + concretes/ (uygulama) — kullanım senaryoları
core/integrations/   ExchangeRateProvider SOYUTLAMASI; satıcıya özgü her şey kendi paketinde
core/utilities/      Durumsuz yardımcılar (SecureXml, ApiKeyHasher)
dataAccess/          RateCache · RateLimiter · ApiKeyStore — her biri memory|redis çiftli
entities/            Domain modeli — hiçbir satıcının tel formatı değildir
simulator/           Sahte satıcı yüzü + kaos uçları (VARSAYILAN KAPALI, bkz. aşağıda)
admin-ui/            Angular admin paneli — bağımsız npm projesi, Maven'a dahil DEĞİL
```

### Değişmez kurallar

- **`dataAccess/` deseni tektir:** bir arayüz + `InMemory*` (`matchIfMissing=true`) +
  `Redis*`, ikisi de `@ConditionalOnProperty(currency-api.cache.type)` ile seçilir. Yeni bir
  depo eklerken bu desenden sapma.
- **Satıcının tel formatı kendi paketinden ÇIKMAZ.** TCMB'nin XML'i
  `core/integrations/tcmb/dtos` içinde kalır, domaine mapper ile girer. Yeni sağlayıcı
  (ör. ECB) yalnız `core/integrations/<satıcı>/` altına eklenir; API sözleşmesi, cache ve iş
  katmanı DEĞİŞMEZ.
- **Kur yönü tek yerde biter** (`TcmbRateMapper`). Yön hatası sessizdir — ters çevrilmiş kur da
  geçerli bir pozitif sayıdır ve hiçbir doğrulamaya takılmaz. Tek koruma testtir.
- **Sırlar depoya girmez.** `.env` gitignore'ludur; izlenen tek dosya anahtarsız
  `.env.example`'dır. Paylaşılmış (sohbet/ekran görüntüsü/PR) bir anahtar YANMIŞ sayılır.
- **Anahtarlar log'a, metriğe, hata gövdesine GİRMEZ** — yanlış anahtar bile bir sırdır
  (çoğu zaman BAŞKA bir ortamın geçerli anahtarıdır). Kimlik olarak `consumerName` kullanılır.
- **Testler altyapısızdır.** Saat enjekte edilir (`Clock`), Spring context kurulmaz,
  bileşenler `new` ile bağlanır. Redis destekli sınıfların birim testi YOKTUR ve bu
  belgelenmiş bir boşluktur (testcontainers bağımlılığı yok); onlar `podman compose` ile
  sınanır.

### Fail-open / fail-closed — ikisi de bilinçli

Kod tabanının genel felsefesi **fail-open**'dır: yardımcı bir bileşenin (cache, hız sayacı,
sağlık göstergesi) arızası asıl işlevi düşürmemelidir. Redis erişilemezken servis kuru
sağlayıcıdan çekmeye devam eder.

**Tek istisna `ApiKeyStore.findByHash()`'tir ve fail-CLOSED'dır.** Orada Redis bir hızlandırıcı
değil, **yetkilendirmenin kaynağıdır**; fail-open "doğrulayamıyorsam geçir" demek olurdu.
Aynı ilkenin farklı uygulanışıdır, ilkeden sapma değil. Statik `CURRENCY_API_KEYS` anahtarları
bu riskten muaftır (bellek içi) — Redis kesintisinde ayakta kalması gereken tüketiciye
**statik** anahtar verilir.

### Üç yüzey, üç farklı erişim sınırı

| Yüzey | Port | Sınır |
|---|---|---|
| Tüketici API (`/api/v1/rates`) | 8095 | İnternete açık (Cloudflare tüneli), `X-API-Key` ister |
| Tanıtım sayfası (`/`) + önizleme (`/api/v1/rates/preview`) | 8095 | Anahtarsız, ama **kota IP başına uygulanır** |
| Admin API (`/admin/**`) | 8097 | **Yalnız loopback** (`127.0.0.1`); `X-Admin-Token` ister |
| Admin panel (Angular) | 8096 | **Yalnız loopback** (`127.0.0.1`) |

Admin portları önce tüm arayüzlere bağlıydı ("LAN'dan da yönetebileyim"); bu **sabit bir ev ağı
varsayımıydı**. Burası bir dizüstü: kafede/otelde bir Wi-Fi'a bağlandığı anda admin yüzeyi o
ağdaki herkese açılırdı — üstelik token LAN'da düz HTTP ile gider (tünel yalnız 8095'i TLS'ler)
ve makinenin güvenlik duvarı kapalıdır. Başka bir cihazdan yönetmek gerekirse SSH tüneli:
`ssh -L 8097:127.0.0.1:8097 -L 8096:127.0.0.1:8096 <kullanici>@<makine>`.

**Admin yüzeyi asla tünele eklenmez.** Ayrı port, path filtresinden bağımsız *yapısal* bir
sınırdır: tünel path değil TÜM portu yönlendirir. Token kontrolü bunun üzerine ikinci
katmandır, tek başına sınır değildir. Admin kapalıyken `/admin/**` **404** döner (401 değil —
401 o yüzeyin varlığını doğrulardı).

### Simülatör — varsayılan KAPALI

`simulator/` altındaki sahte satıcı ve kaos uçları (`/__mode`, `/__settings`, `/__reset`)
yalnız `currency-api.simulator.enabled=true` iken vardır. Kaos uçları **kimlik doğrulaması
istemez** (kaosu süren duman testinin elinde anahtar yoktur) ve **durum değiştirir**; internete
açık bir serviste bu ikisi bir arada uzaktan erişilebilir bir arıza düğmesidir. Yalnız tüketici
testlerinde açılır.

## Sözleşme notları

- **Cevap zarfı YOKTUR** — CRM'in `ApiResponse<T>` zarfının aksine bu servis çıplak JSON döner
  (`{"base": ..., "rates": [...]}`, hata için `{"error": "..."}`). Bu **bilinçlidir**: servis
  bir *dış satıcı* gibi davranır ve ticari kur API'leri zarf kullanmaz. CRM tarafında bu şekli
  `CurrencyApiWireFormat` karşılar; **değiştirmek tüketiciyi kırar**.
- **Hata metinleri `Accept-Language` ile tr/en çözülür** (`ApiMessages` + `messages*.properties`),
  CRM'in i18n sözleşmesiyle hizalıdır.
- **`X-Correlation-Id`** okunur, MDC'ye konur (her log satırında görünür) ve cevapta yankılanır.
  Gelen değere güvenilmez: yalnız harf/rakam/`-`/`_` ve makul uzunluk kabul edilir (log forging).
- **İki kur yönü de sunulur** (`rate` = 1 TRY kaç birim, `unitPrice` = 1 birim kaç TRY).
  Dönüşümü her tüketicinin ayrı yapması sessiz yön hatalarının kaynağıdır.
- **`/api/v1/rates/preview` anahtarsızdır ve ürünün yerine geçmez:** yalnız birkaç para birimi
  ve yalnız `unitPrice` döner; `rate` (çevrim yönü), `provider`, `cache`, `stale` YOKTUR.
  Tanıtım sayfasındaki panoyu besler — sayfaya anahtar gömmek onu yakmak olurdu, sabit yazılmış
  sayılar ise bir kur servisinin vitrininde eskiyip yanlışa dönerdi. `ApiGuardFilter`'da
  **tam eşleşmeyle** auth'tan muaftır ama filtreden çıkarılmaz: hız sınırı uygulanmaya devam eder.
- **Bayat kur 200'dür**, 5xx değil: elde kullanılabilir veri varken tüketiciyi hataya
  düşürmek yanlış olurdu. Hiç kur yoksa 503 + `Retry-After`.
