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
mvn test                       # 189 birim testi — ALTYAPISIZ (Redis/ağ gerekmez)
mvn spring-boot:run            # http://localhost:8095

# TAM YIĞIN — --force-recreate ZORUNLU, bkz. aşağıdaki not
podman compose up -d --build --force-recreate

cd admin-ui && npx ng test --watch=false   # 28 birim testi
cd admin-ui && npx ng build                # prod build
```

**`--build` tek başına dağıtım DEĞİLDİR (ölçülerek öğrenildi).** Bir dağıtımda üç imajın
üçü de yeniden derlendi (`podman images` hepsini "1 dakika önce" gösteriyordu) ama
konteynerlerin ikisi **yeniden oluşturulmadı**: `podman ps` onları hâlâ "Up 56 minutes"
diye gösteriyordu, yani eski kodu çalıştırmaya devam ediyorlardı. Arıza sessizdir — derleme
başarılı, konteynerler sağlıklı, yalnız yeni davranış ortada yoktur. Doğrulama şudur:
`podman ps` çıktısındaki **Up süresi** dağıtımdan yeni olmalıdır.

Java 25 gerekir (`pom.xml` → `java.version=25`). Homebrew Maven'ı varsayılan olarak başka bir
JDK'ya bakabilir; `JAVA_HOME` Java 25'i işaret etmelidir.

## Mimari

```
api/                 HTTP sözleşmesi — filtreler + controller'lar + DTO'lar
business/            abstracts/ (arayüz) + concretes/ (uygulama) — kullanım senaryoları
core/integrations/   ExchangeRateProvider SOYUTLAMASI + Chained… (öncelik sırası); satıcıya
                     özgü her şey kendi paketinde: tcmb/ (today.xml) · evds/ (EVDS API)
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
- **Kota ile kullanım AYRI şeylerdir ve karıştırılmamalıdır.** `RateLimiter` bir *kontrol*dür:
  sayacı 1 dakikalık penceredir ve pencere dolunca sıfırlanır. `ApiKeyUsageCounter` bir
  *ölçüm*dür: birikmelidir, azalmaz. Admin paneli önce yalnız birincisini gösteriyordu ve
  15 dakikada bir çağıran bir tüketici için o sayı **hep dolu** görünüyordu — sayı doğruydu,
  soru yanlıştı. Aynı sayaca ikisini birden yaptırma.
- **Satıcının tel formatı kendi paketinden ÇIKMAZ.** TCMB'nin XML'i
  `core/integrations/tcmb/dtos`, EVDS'in JSON'u `core/integrations/evds/dtos` içinde kalır;
  domaine mapper ile girer. Yeni sağlayıcı (ör. ECB) yalnız `core/integrations/<satıcı>/`
  altına eklenir + zincire yazılır; API sözleşmesi, cache ve iş katmanı DEĞİŞMEZ.
- **Kur yönü her sağlayıcının kendi mapper'ında biter** (`TcmbRateMapper`, `EvdsRateMapper`).
  Yön hatası sessizdir — ters çevrilmiş kur da geçerli bir pozitif sayıdır ve hiçbir
  doğrulamaya takılmaz. Tek koruma testtir; `EvdsRateMapperTest` ayrıca iki mapper'ın aynı
  girdi için aynı çıktıyı ürettiğini sabitler.
- **`EvdsRateMapper.UNIT` tablosu elle tutulur ve yanlışı 100 KATtır.** EVDS,
  `today.xml`'in `<Unit>` alanının karşılığını **göndermez**: JPY'nin 30.0482 değeri
  "1 JPY = 30 TL" değil "**100** JPY = 30 TL" demektir. Çarpan atlanırsa kur yine geçerli bir
  pozitif sayıdır. Tablo eksik bir para birimi için sessizce 1 varsaymaz, gürültülü patlar.
- **Sırlar depoya girmez.** `.env` gitignore'ludur; izlenen tek dosya anahtarsız
  `.env.example`'dır. Paylaşılmış (sohbet/ekran görüntüsü/PR) bir anahtar YANMIŞ sayılır.
- **Anahtarlar log'a, metriğe, hata gövdesine GİRMEZ** — yanlış anahtar bile bir sırdır
  (çoğu zaman BAŞKA bir ortamın geçerli anahtarıdır). Kimlik olarak `consumerName` kullanılır.
- **Testler altyapısızdır.** Saat enjekte edilir (`Clock`), Spring context kurulmaz,
  bileşenler `new` ile bağlanır. **Redis destekli sınıflar da bu kurala uyar:**
  `StringRedisTemplate` taklit edilir, çünkü sınanan şey Redis değil bizim kararlarımızdır
  (anahtar düzeni, KEYS taraması yapılmaması, fail-closed/fail-open ayrımı).

### Sağlayıcı zinciri — öncelik: en yeni → en eski

`ChainedExchangeRateProvider` sırayla dener, ilk başarılıyı döner. İş katmanı tek bir sağlayıcı
görür; kaç yol olduğunu bilmez.

| # | Basamak | Kaynak | Notu |
|---|---|---|---|
| 1 | ~~Saatlik~~ | **YOK** | Aranmadığı için değil, **olmadığı için** — aşağıya bkz. |
| 2 | Bugünkü | **EVDS** (`TP.DK.*.S`) | Her satırı kendi günüyle verir |
| 3 | Bugünkü (yedek) | `today.xml` | Anahtar istemez; tarih etiketi bir gün geriden |
| 4 | Dünkü / son geçerli | cache (`STALE_CACHE`) | Zincirde DEĞİL, `ExchangeRateServiceImpl`'de |

**Saatlik kaynak yoktur — ölçülerek kanıtlandı.** EVDS'in 671 veri grubunun tamamı tarandı;
frekanslar AYLIK 284 · ÜÇ AYLIK 163 · YILLIK 102 · HAFTALIK 88 · İŞ GÜNÜ 21 · GÜNLÜK 10 …
Günden sık tek bir frekans yok, döviz grupları (`bie_dkdovytl`) GÜNLÜK. TCMB'nin "Saat Başı
Belirlenen Döviz Kurları" sayfası da veri değil **açıklama** sayfasıdır: tarayıcı ağ izinde tek
bir veri isteği ve tek bir "USD" metni yoktur. 1. basamak **bilerek boştur**; kaynak çıkarsa
zincirin başına eklenir, altındaki hiçbir şey değişmez.

**EVDS neden 1. sırada — sayılar aynı olduğu halde.** İki kaynak da TCMB'nin aynı resmî satış
kurudur ve rakamları birebir aynıdır (ölçüldü: USD 47.7717, JPY 0.300482 — tam eşleşme).
Kazanılan tek şey **doğru tarihtir**: `today.xml`'in `Tarih` özniteliği bir gün geriden gelir
(14.08 sabahı yayınlanan belge "13.08.2026" der, ama içindeki sayılar EVDS'in 14-08'e yazdığı
sayılardır). "Elimizdeki kur bugünün mü" sorusu `today.xml` ile **cevaplanamaz**.

**`CURRENCY_EVDS_KEY` aynı zamanda düğmedir.** Boşsa EVDS zincire hiç girmez ve servis eski
davranışını aynen sürdürür. Ayrı bir `enabled` bayrağı YOKTUR: "açık ama anahtarsız" diye
çelişkili bir yapılandırma kurulamasın diye. Anahtar `key` **başlığında** gider (sorgu
dizesinde `403` alınır) — bu ayrıca URL'leri sırsız tutar, yani log ve stack trace'lerde URL
basmak güvenlidir.

**`name()` zincire göre DEĞİŞMEZ, `tcmb`de sabittir.** Sağlayıcı adı hem cache anahtarıdır hem
cevaptaki `provider` alanı; yola göre değişseydi her yol değişiminde cache **bölünür** ve
tüketicinin gördüğü alan bizim iç seçimimize göre oynardı. İki yol da aynı kurumun aynı
kurudur, farklı olan yalnız kapıdır — hangi kapıdan girildiği **log'dadır**, sözleşmede değil.

Yedek yol tatbikatla doğrulandı (iddia yeterli değildir): bozuk anahtarlı tek kullanımlık bir
konteynerde EVDS `UNAUTHORIZED` aldı, zincir `today.xml`'e indi, **servis kur sunmayı
sürdürdü** — yalnız tarih 13.08'e döndü ve tek satır WARN düştü.

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

## Çalışma zamanı — kalıcılık zinciri

Cloudflare **barındırmaz**, yalnız tüneller: her şey makinede, podman VM'i içinde çalışır.
Makine yeniden başladığında zincir şu sırayla toparlanır — her halka ayrı kurulmuştur ve
biri eksikse servis ölü kalır:

1. `com.currencyapi.podman-machine` (LaunchDaemon) → VM'i başlatır
2. VM içinde **kullanıcı** seviyesindeki `podman-restart.service` + `loginctl enable-linger core`
   → konteynerleri geri getirir
3. `com.currencyapi.cloudflared` (LaunchDaemon, `KeepAlive`) → tüneli yeniden kurar

**2. halka en kolay atlanandır ve sessizdir:** `restart: unless-stopped` yalnız konteyner
ÇÖKERSE devreye girer, VM yeniden başladığında değil. Servis root seviyesinde etkinleştirilirse
de işe yaramaz — konteynerler rootless'tır ve root onları görmez. Linger olmadan da kullanıcının
systemd yöneticisi açılışta hiç başlamaz. Bu üçü ölçülerek öğrenildi: ilk tatbikatta VM
yeniden başladı ve konteynerler geri GELMEDİ.

Değişiklik yapıldığında doğrulama şudur (iddia yeterli değildir):
`podman machine stop && podman machine start`, sonra **elle hiçbir şey yapmadan** servisin
kendiliğinden dönmesini beklemek. Ölçülen toparlanma: ~18 saniye.

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
- **`/api/v1/rates/preview` anahtarsızdır ve ürünün yerine geçmez:** yalnız birkaç para birimi,
  satırlarda yalnız `unitPrice`; `rate` (çevrim yönü), `provider`, `cache`, `stale` YOKTUR.
  Gövdede `rateDate` ve `fetchedAt` bulunur — pano ikisini birden göstermelidir: yalnız bülten
  günü gösterilince "bugün 14'ü, neden 13 yazıyor, servis mi takıldı?" sorusu doğuyordu. Cevap
  "en son ne zaman baktığımız"dır; TCMB bülteni gün içinde bir kez yayınlar.
  Tanıtım sayfasındaki panoyu besler — sayfaya anahtar gömmek onu yakmak olurdu, sabit yazılmış
  sayılar ise bir kur servisinin vitrininde eskiyip yanlışa dönerdi. `ApiGuardFilter`'da
  **tam eşleşmeyle** auth'tan muaftır ama filtreden çıkarılmaz: hız sınırı uygulanmaya devam eder.
- **Bayat kur 200'dür**, 5xx değil: elde kullanılabilir veri varken tüketiciyi hataya
  düşürmek yanlış olurdu. Hiç kur yoksa 503 + `Retry-After`.
