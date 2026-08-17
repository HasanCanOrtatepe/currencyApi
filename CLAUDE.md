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

### `oyun/` ve `sunum/` — kur servisiyle İLGİSİZDİR

Depoda kur servisiyle **hiçbir kod paylaşmayan** iki Node projesi durur. İkisi de sıfır npm
bağımlılığına sahiptir, **Maven reaktörüne dahil DEĞİLDİR**, ayrı imaj/ayrı konteyner/ayrı
portta çalışır ve aynı depoda olmalarının tek sebebi aynı domaini ve aynı Cloudflare tünelini
paylaşmalarıdır. Kur tarafında bir şey değiştirirken ikisine de dokunma; onlarda bir şey
değiştirirken `mvn test` çalıştırmak gerekmez.

| Dizin | Ne | Port · hostname | Test |
|---|---|---|---|
| `oyun/` | **Etiya Vampir Köylü** oyun sunucusu | 8098 · `oyun.etiyapi.com` | `node --test 'oyun/test/*.test.js'` |
| `sunum/` | **Sunum destesi** — şifreli + senkron | 8099 · `sunum.etiyapi.com` | — |

`sunum/` desteyi şifre arkasında sunar ve aktif slaytı sunucuda tutar: biri ilerlettiğinde
herkesin ekranı birlikte geçer (SSE). **Desteyi DÜZENLEMEZ** — `sunum/public/deste.html`
konuşmacının dosyasının birebir kopyasıdır, senkron kancası sunum anında enjekte edilir; çapa
bulunamazsa sunucu **hiç açılmaz** (sessizce senkronsuz açılmak sahnede fark edilirdi).
Ayrıntı: `sunum/README.md`.

**Tünelde artık üç hostname var** (`~/.cloudflared/config.yml`): `kur` → 8095, `oyun` → 8098,
`sunum` → 8099. Admin yüzeyleri (8096/8097) hâlâ **bilinçli olarak yoktur.**

## Komutlar

```bash
mvn test                       # 230 birim testi — ALTYAPISIZ (Redis/ağ gerekmez)
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
                     özgü her şey kendi paketinde: tcmb/ (today.xml) · evds/ (EVDS API) ·
                     ecb/ (eurofxref, çapraz kur)
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
  `core/integrations/tcmb/dtos`, EVDS'in JSON'u `core/integrations/evds/dtos`, ECB'nin
  EUR tabanlı XML'i `core/integrations/ecb/dtos` içinde kalır;
  domaine mapper ile girer. Yeni sağlayıcı yalnız `core/integrations/<satıcı>/` altına
  eklenir + zincire yazılır; API sözleşmesi, cache ve iş katmanı DEĞİŞMEZ. **Bu iddia ECB
  eklenirken fiilen sınandı:** yeni paket + zincire bir satır + bir `enabled` bayrağı; iş
  katmanı, controller ve DTO'lar hiç açılmadı. Tek istisna `ExchangeRateSnapshot.source`'tur
  ve o da bilinçlidir — bkz. aşağıdaki `name()` notu.
- **Satıcı cevabı SINIRSIZ okunmaz.** Gövde `BoundedHttpBody` ile en çok 4 MB okunur
  (`ofString()` boyuta bakmadan tamamını belleğe alırdı). Cevap ağdan gelir; devasa bir gövde
  `-XX:+ExitOnOutOfMemoryError` altında sessiz bir yavaşlama değil, sürecin ölmesi ve her
  istekte tekrarlanan bir kesinti demektir. `SecureXml` ile aynı gerekçe: dış yükün *biçimine*
  değil, *bize ne yaptırabileceğine* bakılır.
- **Kur yönü her sağlayıcının kendi mapper'ında biter** (`TcmbRateMapper`, `EvdsRateMapper`,
  `EcbRateMapper`).
  Yön hatası sessizdir — ters çevrilmiş kur da geçerli bir pozitif sayıdır ve hiçbir
  doğrulamaya takılmaz. Tek koruma testtir; `EvdsRateMapperTest` ve `EcbRateMapperTest`
  ayrıca mapper'ların *aynı gerçek* için aynı çıktıyı ürettiğini sabitler.
- **Birim çarpanı her satıcıda AYRI bir karardır.** `EvdsRateMapper.UNIT` tablosu elle
  tutulur ve yanlışı 100 KATtır; `EcbRateMapper`'da ise çarpan **hiç yoktur ve olmamalıdır**
  (ECB JPY'yi 1 birim üzerinden verir). Tabloyu bir mapper'dan diğerine kopyalamak, tam da
  bu yüzden en tehlikeli hamledir. EVDS,
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
| 4 | Bugünkü (**bağımsız**) | **ECB** (`eurofxref-daily.xml`) | Farklı kurum; varsayılan KAPALI; yalnız bugünse konuşur |
| 5 | Dünkü / son geçerli | cache (`STALE_CACHE`) | Zincirde DEĞİL, `ExchangeRateServiceImpl`'de |

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

**ECB neden var, neden 4. sırada ve neden hafta sonunu ÇÖZMEZ.** EVDS ile `today.xml` **aynı
kurumun** iki kapısıdır: o kurum erişilemez olduğunda ikisi birden düşer ve elde yalnız kendi
bayat kurumuz kalır — yani TCMB tek nokta arızasıydı. ECB farklı kurum, farklı altyapı, farklı
ülkedir; birlikte düşmeleri için ortak sebep yoktur. Kapatılan şey budur. **Takvim değildir:**
ECB de hafta sonu/tatilde yayın yapmaz, o iş cache'in **7 günlük saklamasınındır**. 4. sırada
olmasının sebebi ise TRY'nin resmî kurunu TCMB'nin belirlemesidir: ECB'nin *referans* kuru
yakın ama aynı sayı değildir ve **iki bölmeyle** (çapraz kur) elde edilir. ECB bir alternatif
değil, "hiç kur yok"a karşı bir sigortadır.

**ECB yalnız BUGÜNÜN belgesine sahipse konuşur.** ECB'nin günlük dosyası hafta sonu
*kaybolmaz*, cuma gününü göstermeye devam eder. Bu kural olmasaydı her cumartesi sunulan kur
TCMB'den ECB'ye **atlar** ve hiçbir şey bozulmadığı hâlde tüketicinin gördüğü rakam kurum
değiştirirdi. Bugüne ait değilse `NOT_PUBLISHED` ile susar, servis kendi son geçerli kurunu
sunar — o kur da TCMB'nindir, yani kurum değişmez.

**`CURRENCY_ECB_ENABLED` varsayılan KAPALIDIR** ve EVDS'in aksine ayrı bir bayrağı vardır:
ECB anahtar istemediği için "anahtar aynı zamanda düğmedir" hilesi burada kurulamaz. Kapalı
varsayılanın gerekçesi `auth.enabled` ile aynıdır — bu sağlayıcı **sunulan sayının hangi
kurumdan geldiğini değiştirebilir** ve bunu bilmeyen bir kurulum imaj güncellemesiyle sessizce
devralmamalıdır.

**`EcbRateMapper`'da birim çarpanı YOKTUR ve EKLENMEMELİDİR.** EVDS ve `today.xml` JPY'yi 100
birim üzerinden verir; ECB vermez — `JPY 171.85` satırı tam olarak "1 EUR = 171,85 JPY"dir.
`EvdsRateMapper.UNIT` tablosunu buraya kopyalamak kuru **100 kat** bozar ve sonuç yine geçerli
bir pozitif sayıdır. `EcbRateMapperTest` iki mapper'ın *aynı gerçek* için aynı kuru ürettiğini
sabitler (TCMB "100 JPY = 25 TL" ↔ ECB "1 EUR = 200 JPY, 1 EUR = 50 TRY" → ikisi de 4).

**`name()` zincire göre DEĞİŞMEZ, `tcmb`de sabittir — ama artık cevaptaki `provider` ondan
gelmez.** Bu ad **cache yuvasının kimliğidir**: yola göre değişseydi cache **bölünür**, ECB'ye
düşülen bir gün TCMB'nin kaydı ayrı yuvada eskimeye devam eder ve TCMB döndüğünde elde farklı
yaşlarda iki tablo olurdu. Cevaptaki `provider` alanı ise **kaydın kendisinden** okunur
(`ExchangeRateSnapshot.source`) ve gerçekten değişir. İki kural çelişmez, farklı soruları
cevaplar: yuva "bu tabloyu nereye koyayım", `source` "bu sayıyı kim yayınladı". EVDS ve
`today.xml` için `source` ikisinde de `tcmb`dir — aynı kurumun aynı kuru, farklı olan yalnız
kapıdır ve hangi kapı olduğu **log'dadır**, sözleşmede değil. ECB için `ecb`dir ve **öyle
olmalıdır**: başka bir kurumun sayısını TCMB adıyla sunmak, bu servisin bütün kurallarının
kapatmaya çalıştığı sessiz yanlışlığın ta kendisi olurdu.

Yedek yol tatbikatla doğrulandı (iddia yeterli değildir): bozuk anahtarlı tek kullanımlık bir
konteynerde EVDS `UNAUTHORIZED` aldı, zincir `today.xml`'e indi, **servis kur sunmayı
sürdürdü** — yalnız tarih 13.08'e döndü ve tek satır WARN düştü.

**ECB basamağı da tatbikatla doğrulandı (16.08.2026).** TCMB'si ulaşılamaz, EVDS'i anahtarsız,
cache'i boş tek kullanımlık bir konteyner kuruldu:

1. **Gerçek uçla:** ECB çağrıldı, gerçek belge okundu ve **bilinçle reddedildi** —
   `ECB belgesi bugune ait degil: belge=2026-08-14 bugun=2026-08-16` (hafta sonu).
   `NOT_PUBLISHED` işaretlendi, `TRANSPORT` değil; zincirin `combined()`'ı da doğru davrandı ve
   *gerçek* arızayı (TCMB `TRANSPORT`) takvimin önüne aldı. **"Yalnız bugün" kuralı çalışıyor.**
2. **Tarihi bugüne çekilmiş GERÇEK belgeyle:** ECB kur sundu, cevapta `provider: "ecb"` yazdı.
   Sayılar canlı TCMB kurlarıyla karşılaştırıldı:

   | | TCMB (satış) | ECB (çapraz) | fark |
   |---|---|---|---|
   | USD | 47,7717 | 47,8844 | +0,24 % |
   | EUR | 55,0744 | 55,3879 | +0,57 % |
   | GBP | 64,5356 | 64,8191 | +0,44 % |
   | JPY | 0,300482 | 0,301136 | **+0,22 %** |

   Fark, *satış* kuru ile *referans* kuru arasında beklenen aralıktadır. **Bu tablo aynı zamanda
   iki sessiz hatanın da testidir:** yön ters olsaydı sapma binlerce kat, JPY'ye ×100
   uygulansaydı 30,11 (ya da 0,00301) çıkardı — ikisi de bu tabloda **anında görünürdü**.

Ayrıca `source` alanının eklenmesi **canlıda göç davranışını da gösterdi**: Redis'teki eski kayıt
`source` taşımadığı için kanonik kurucu patladı, `RedisRateCache` fail-open ile cache-miss saydı
ve kur bir kez EVDS'ten yeniden çekildi — tek satır WARN, kesinti yok.

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
| Tanıtım sayfası (`/`) + önizleme (`/api/v1/rates/preview`) | 8095 | Anahtarsız, ama **kota istemci IP'si başına uygulanır** (bkz. aşağıdaki not) |
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

**Dördüncü bir yüzey vardı ve görünmezdi: `/actuator`.** `ApiGuardFilter` başta `/actuator`
önekinin TAMAMINI muaf tutuyordu; tünel de tüm portu taşıdığı için `/actuator/metrics`
internete **anahtarsız ve kotasız** açıktı (canlıda ölçüldü: `jvm.info` → JDK sürümü,
`disk.free`, `http.server.requests` → tüm uç listesi ve istek sayıları). Muafiyetin gerekçesi
orkestratördü ama orkestratör yalnız **sağlık ucunu** sorar. Artık muaf olan yalnız
`/actuator/health` (+ liveness/readiness sondaları); üstelik public instance `health` dışında
hiçbir ucu **yayınlamaz** (`CURRENCY_ACTUATOR_EXPOSE`). Metrikler yalnız loopback'e bağlı
admin instance'ında açıktır ve orada da anahtar ister. Genel kural: bir yolu `shouldNotFilter`'a
eklerken **önek değil tam ad** yazılır — burada bedeli internete açık bir yüzeydi.

**Kota kimliği tünel arkasında `getRemoteAddr()` DEĞİLDİR.** Konteynere gelen bağlantının
kaynağı tüm internet trafiği için aynıdır (ölçüldü: `remote=10.89.0.3`), yani "kota IP başına"
cümlesi pratikte "tek kova, tüm dünya" demekti: tek bir çağıran anahtarsız önizleme ucunu
dakikada 120 istekle herkese `429` yaptırabiliyordu ve kötüye kullanım WARN'ı kimseyi işaret
etmiyordu. Kimlik artık `currency-api.rate-limit.client-ip-header` ile okunur (üretimde
`CF-Connecting-IP`; o başlığı cloudflared kendisi yazar, tünelden uydurulamaz). **Varsayılan
BOŞTUR** — başlığa güvenmek, portun önünde onu yazan bir vekil olduğunu bilmeyi gerektirir;
bilmeyen bir kurulum bunu devralmamalıdır. Değer yalnız **anonim** istekte kullanılır: anahtarlı
tüketicinin kimliği adıdır, uydurulabilir bir alan onu ezemez.

**Başarısız kimlik doğrulaması da kota harcar.** Sıra önce 401, sonra kotaydı; o hâliyle anahtar
denemesi sınırsızdı ve dinamik anahtar biçimindeki her deneme bir Redis okuması harcatıyordu.
256 bitlik anahtara kaba kuvvet zaten hesaplanamaz — kapatılan şey, anonim bir çağıranın
servisten sınırsız iş çekebildiği tek yoldur.

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
