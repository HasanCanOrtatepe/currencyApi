# currency-api — Pair 3 Kur Servisi

**TCMB'yi ana veri kaynağı alan kur servisi.** Merkez Bankası'nın günlük döviz kuru belgesini
çeker, cache'ler ve kendi JSON sözleşmesiyle sunar. Bağımsız bir mikroservistir: veritabanı yok,
Config Server'dan okumaz, servis kayıt defterine kaydolmaz.

> Kullanıcıya görünen ürün adı **Pair 3 Kur Servisi**'dir (tanıtım sayfası + admin paneli).
> `currency-api` teknik/depo adıdır ve kod, imaj, ortam değişkeni adlarında değişmez.

## Ne yapar

```
GET /api/v1/rates                    → tüm desteklenen kurlar
GET /api/v1/rates?symbols=USD,EUR    → yalnız istenenler
```

```json
{
  "base": "TRY",
  "rateDate": "2026-08-11",
  "fetchedAt": "2026-08-12T05:42:52Z",
  "provider": "tcmb",
  "cache": "FRESH_CACHE",
  "stale": false,
  "rates": [
    { "currency": "USD", "rate": 0.0209483748, "unitPrice": 47.7364000572 }
  ]
}
```

**İki kur yönü de sunulur, bilinçli olarak:** `rate` = 1 TL kaç USD (çevrim yönü),
`unitPrice` = 1 USD kaç TL (gösterim yönü). Tüketicilerin yarısı birini, yarısı diğerini bekler;
dönüşümü her tüketicinin ayrı yapması **sessiz yön hatalarının** kaynağıdır — ters çevrilmiş bir
kur da geçerli bir pozitif sayıdır ve hiçbir doğrulamaya takılmaz.

## Üç davranış vaadi

| | Nasıl | Kanıt |
|---|---|---|
| **Her istekte TCMB'ye gidilmez** | 15 dk tazelik penceresi; içindeyken sağlayıcı hiç çağrılmaz | `cache: "FRESH_CACHE"` |
| **TCMB düşse de kur döner** | Son geçerli kur sunulur | `cache: "STALE_CACHE"`, `stale: true` |
| **Hafta sonu/tatilde de çalışır** | TCMB o gün belge yayınlamaz (404); son iş gününün kuru sunulur | aynı yol, `NOT_PUBLISHED` sebebiyle |

Elde hiç kur yoksa (sağlayıcı erişilemez **ve** cache boş) uç **503 + `Retry-After`** döner.
Bayat kur ise **200**'dür: elinde kullanılabilir veri varken tüketiciyi hataya düşürmek yanlış olurdu.

## Mimari

```
api/controllers  ─ ExchangeRateController      (HTTP sözleşmesi, DTO'ya eşleme)
business/        ─ ExchangeRateService         (cache-aside + son geçerli kur — TEK yer)
core/integrations
  ExchangeRateProvider                          (SOYUTLAMA — ECB buraya eklenir)
  ChainedExchangeRateProvider                   (ÖNCELİK SIRASI — bkz. "Sağlayıcı zinciri")
  evds/  EvdsExchangeRateProvider               (HTTP — 1. sıra, doğru tarih)
         EvdsJsonReader       → dtos/           (JSON → DTO)
         EvdsRateMapper                         (DTO → domain: yön, birim TABLOSU, gün seçimi)
  tcmb/  TcmbExchangeRateProvider               (HTTP — 2. sıra, anahtar istemez)
         TcmbXmlReader        → dtos/           (XML → DTO)
         TcmbRateMapper                         (DTO → domain: yön çevirme, birim, tarih)
dataAccess/      ─ RateCache                    (Redis | bellek)
entities/        ─ ExchangeRateSnapshot         (domain — satıcıdan bağımsız)
simulator/       ─ sahte satıcı uçları + kaos   (tüketici testleri için, bkz. aşağıda)
```

**Zincirin her adımı ayrı sınıftadır** çünkü her adım ayrı bir sebeple bozulur ve ayrı test
edilebilmelidir: "satıcıya ulaşamadık", "belgeyi okuyamadık", "kuru çeviremedik" farklı
arızalardır ve farklı yerlerde aranır.

### TCMB'nin üç tuzağı (mapper'da biter)

1. **Kur yönü TERSTİR** — TCMB `1 USD = 47,73 TL` yayınlar, sözleşmemiz `1 TL = 0,0209 USD`.
   Bu hata **sessizdir**: 47,73 de geçerli bir pozitif kurdur, hiçbir doğrulamaya takılmaz ve
   tutarları ~2000 kat şişirir. Tek koruma testtir.
2. **`Unit` 1 olmayabilir** — JPY 100 birim üzerinden yayınlanır; hesaba katılmazsa kur 100 kat yanlış.
3. **Günlük yayın** — gün içi güncellenmez, hafta sonu/tatilde belge yoktur.

**EVDS'in dördüncü tuzağı:** `Unit` alanının karşılığını **hiç göndermez**. JPY'nin `30.0482`
değeri "1 JPY = 30 TL" değil "**100** JPY = 30 TL" demektir; çarpan `EvdsRateMapper.UNIT`
tablosunda elle tutulur ve yanlışı 100 kattır. Tablo eksik bir para birimi için sessizce 1
varsaymaz, gürültülü patlar — o durumda zincir `today.xml`'e iner, yani hata servisi düşürmez.

### Sağlayıcı zinciri — öncelik: en yeni → en eski

`ChainedExchangeRateProvider` sırayla dener, ilk başarılıyı döner:

| # | Basamak | Kaynak | Notu |
|---|---|---|---|
| 1 | ~~Saatlik~~ | **YOK** | Aranmadığı için değil, olmadığı için (aşağıya bkz.) |
| 2 | Bugünkü | **EVDS** | Her satırı kendi günüyle verir |
| 3 | Bugünkü (yedek) | `today.xml` | Anahtar istemez; tarih etiketi bir gün geriden |
| 4 | Dünkü / son geçerli | cache (`STALE_CACHE`) | Zincirde değil, `ExchangeRateService`'te |

**Saatlik kaynak yoktur.** EVDS'in 671 veri grubu tarandı: günden sık tek bir frekans yok
(AYLIK 284 · ÜÇ AYLIK 163 · YILLIK 102 · HAFTALIK 88 · İŞ GÜNÜ 21 · GÜNLÜK 10 …), döviz
grupları GÜNLÜK. TCMB'nin "Saat Başı Belirlenen Döviz Kurları" sayfası veri değil **açıklama**
sayfasıdır — ağ izinde tek bir veri isteği yoktur. 1. basamak bilerek boştur.

**EVDS neden 1. sırada, sayılar aynı olduğu halde:** ikisi de TCMB'nin aynı resmî satış
kurudur, rakamlar birebir aynıdır. Kazanılan tek şey **doğru tarihtir** — `today.xml`'in
`Tarih` özniteliği bir gün geriden gelir (14.08 sabahı "13.08.2026" der, ama içindeki sayılar
14-08'in sayılarıdır). "Elimizdeki kur bugünün mü" sorusu `today.xml` ile cevaplanamaz.

`CURRENCY_EVDS_KEY` **aynı zamanda düğmedir**: boşsa EVDS zincire hiç girmez, servis eski
davranışını sürdürür. Anahtar düşse bile zincir `today.xml`'e iner — tek anahtar servisi
düşüremez (tatbikatla doğrulandı).

### Yeni sağlayıcı eklemek (ör. ECB)

`core/integrations/<satıcı>/` altında `ExchangeRateProvider` uygulaması + kendi DTO'su + kendi
mapper'ı, sonra `ChainedExchangeRateProvider`'ın kurucusunda sıraya yazılır. Satıcının tel
formatı o paketten dışarı çıkmaz; API sözleşmesi, cache ve iş katmanı **değişmez**.

> Sıra `@Order` anotasyonlarıyla değil **kodda** durur: öncelik bu servisin en kritik
> kararlarından biridir ve üç dosya açmadan okunabilmelidir.

## Çalıştırma

```bash
mvn spring-boot:run                                   # http://localhost:8095
curl "localhost:8095/api/v1/rates?symbols=USD,EUR"
```

| Ayar | Varsayılan | Not |
|---|---|---|
| `currency-api.cache.type` | `memory` | **Çok instance'lı kurulumda `redis`** — kur paylaşılan durumdur |
| `currency-api.cache.ttl` | `15m` | Tazelik: içindeyken TCMB'ye gidilmez |
| `currency-api.cache.retention` | `7d` | Saklama: tazelik dolsa da kayıt silinmez |
| `currency-api.tcmb.base-url` | `https://www.tcmb.gov.tr` | |
| `CURRENCY_EVDS_KEY` | — | EVDS anahtarı; **boşsa EVDS zincire hiç girmez** (ayrı `enabled` bayrağı yok) |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | yalnız `type=redis` iken |
| `CURRENCY_AUTH_ENABLED` | `false` | anahtar doğrulaması (401) |
| `CURRENCY_API_KEYS` | — | `anahtar=tuketici,anahtar2=tuketici2` |
| `CURRENCY_RATE_LIMIT` | `120` | tüketici başına dakikalık kota (429) |

Sıfır konfigürasyonla çalışır: hiçbir ayar verilmeden gerçek TCMB'den kur çeker.

> **Neden `memory` varsayılan:** servis Redis olmadan da ayağa kalkabilmelidir, yoksa basit bir
> birim testi bile altyapı gerektirir ve "test edilebilir" iddiası boşa çıkar. Üretimde birden
> çok instance varsa `redis` olmalıdır: bellekte tutulursa her instance kendi kurunu çeker
> (satıcı isteği instance sayısıyla çarpılır) ve iki instance **farklı kur** döndürebilir.

### Kalıcı sunucu olarak çalıştırma (ayrı makine)

Servis, tüketicisinden **bağımsız yaşayabilmelidir**. Depodaki `podman-compose.yml` bunun için
vardır: servisi kendi Redis'iyle birlikte tek komutla ayağa kaldırır ve makine yeniden başlasa
da geri getirir (`restart: unless-stopped`).

```bash
cp .env.example .env      # CURRENCY_API_KEYS=<anahtar>=crm
# --force-recreate ZORUNLU: --build imajı yeniler ama konteyneri yenilemeyebilir (ölçüldü)
podman compose up -d --build --force-recreate
curl -H "X-API-Key: <anahtar>" "http://<bu-makine>:8095/api/v1/rates?symbols=USD"
```

Tüketici tarafında (CRM) **kod değişmez**, yalnız üç ortam değişkeni:

```
CRM_CURRENCY_PROVIDER=currencyapi
CRM_CURRENCY_CURRENCYAPI_BASE_URL=http://<bu-makine>:8095
CRM_CURRENCY_CURRENCYAPI_API_KEY=<aynı anahtar>
```

Farklar CRM'in geliştirme compose'undaki bloğa göre bilinçlidir:

| | CRM compose'undaki blok | Bu dosya |
|---|---|---|
| Amaç | geliştirme kolaylığı | kalıcı hizmet |
| Yaşam döngüsü | CRM yığınıyla doğar/ölür | bağımsız, `restart: unless-stopped` |
| Anahtar doğrulaması | varsayılan **kapalı** (yalnız compose ağında görünür) | varsayılan **açık** (8095 makinenin ağ arayüzünde) |
| Redis | CRM'in Redis'i | kendi Redis'i, **kalıcı** (`appendonly`) |

> **Redis kalıcılığı burada bir ayrıntı değil:** `retention` penceresi (7g) hafta sonu ve resmî
> tatillerde "son geçerli kur"u taşıyan güvenlik ağıdır. Bellekte tutulsaydı cumartesi günü bir
> yeniden başlatma o ağı silerdi ve TCMB yayın yapmadığı için servis pazartesiye kadar kursuz
> kalırdı — yani ağın en çok gerektiği anda. Doğrulandı (2026-08-12): tam yeniden başlatmanın
> ardından ilk istek `FRESH_CACHE` döndü.

> **8095'i düz HTTP ile internete yönlendirmeyin.** Anahtar açık gider ve yol boyunca
> okunabilir. Dışarı açılacaksa önüne TLS sonlandıran bir katman konur — aşağıdaki tünel
> kurulumu tam olarak bunu yapar.

### İnternete açma (Cloudflare Tunnel + alan adı)

Tüketiciler farklı ağlardaysa LAN adresi yetmez; IP de DHCP ile değişebilir. Kalıcı çözüm,
router'a dokunmadan dışarı doğru açılan **isimli bir Cloudflare tüneli**dir: TLS'i Cloudflare
sonlandırır, makinenin gerçek IP'si görünmez ve adres bir daha değişmez.

```bash
cloudflared tunnel login                 # tarayıcıdan alan adı yetkilendirmesi
cloudflared tunnel create currency-api
cloudflared tunnel route dns currency-api kur.<alan-adiniz>
```

`~/.cloudflared/config.yml` — **yalnız 8095 yönlendirilir:**

```yaml
tunnel: <tunnel-id>
credentials-file: /Users/<siz>/.cloudflared/<tunnel-id>.json
ingress:
  # Admin yüzeyi (8096/8097) BİLİNÇLİ olarak yok: tünel path değil TÜM portu taşır,
  # dolayısıyla buraya eklenmeyen bir port internetten yapısal olarak erişilemez.
  - hostname: kur.<alan-adiniz>
    service: http://localhost:8095
  - service: http_status:404
```

Tüketici tarafında (CRM) tek fark base URL'dir:

```
CRM_CURRENCY_PROVIDER=currencyapi
CRM_CURRENCY_CURRENCYAPI_BASE_URL=https://kur.<alan-adiniz>
CRM_CURRENCY_CURRENCYAPI_API_KEY=<admin panelden üretilmiş anahtar>
```

> **Anahtarsız iki yol var:** kök yol (`/`) servisi tanıtan statik sayfadır ve
> `GET /api/v1/rates/preview` o sayfadaki panoyu besler. Önizleme ucu **ürünün yerine geçmez**
> — yalnız birkaç para birimi ve yalnız `unitPrice` döner; çevrim yönü (`rate`), tazelik ve
> sağlayıcı bilgisi yoktur. Anahtarsızdır çünkü herkese açık bir sayfaya anahtar gömmek onu
> yakmak olurdu; buna karşılık **kota uygulanmaya devam eder** ve yeni bir TCMB
> isteği üretmez (aynı 15 dakikalık cache'ten okur).
>
> Kotanın kime yazılacağı tünel arkasında `getRemoteAddr()` ile **belirlenemez**: konteynere
> gelen bağlantının kaynağı tüm internet trafiği için aynıdır, yani sayaç tek bir kova olurdu
> ve tek bir çağıran bu ucu herkes adına tüketebilirdi. Adres bu yüzden
> `CURRENCY_CLIENT_IP_HEADER` ile bildirilen başlıktan (üretimde `CF-Connecting-IP`) okunur.
> **Varsayılan boştur**: başlığa güvenmek, portun önünde onu kendisi yazan bir vekil olduğunu
> bilmeyi gerektirir.

## Anahtar ve kota (ticari API davranışı)

Servis, kendisini ticari bir API gibi sunabilir: istek `X-API-Key` header'ı ister ve her tüketici
için dakikalık bir kota uygular. **İkisi de varsayılan olarak kapalıya yakındır** — anahtar
doğrulaması kapalı, kota açık ama cömert (120/dk).

```bash
CURRENCY_AUTH_ENABLED=true CURRENCY_API_KEYS="$(openssl rand -hex 24)=crm" mvn spring-boot:run
curl -H "X-API-Key: <anahtar>" "localhost:8095/api/v1/rates?symbols=USD"
```

| Durum | Cevap |
|---|---|
| Anahtar yok / tanınmıyor | `401` — istek servise **inmez**, anahtar cevaba sızmaz |
| Kota içinde | `200` + `X-RateLimit-Limit` / `X-RateLimit-Remaining` |
| Kota aşıldı | `429` + `Retry-After` (tüketici ne zaman döneceğini tahmin etmek zorunda kalmaz) |
| `/actuator/health` | anahtar istemez — orkestratörün elinde anahtar yoktur |
| Diğer `/actuator/*` | anahtar **ister**; public kurulumda zaten yalnız `health` yayınlanır |

**Başarısız anahtar denemesi de kota harcar.** Kota kontrolü 401'den **önce** işler: aksi hâlde
anahtar denemesi sınırsız olurdu ve her deneme (dinamik anahtar biçimindeyse) bir Redis okuması
harcatırdı. 256 bitlik anahtara kaba kuvvet zaten hesaplanamaz; kapatılan şey, anonim bir
çağıranın servisten sınırsız iş çekebildiği yoldur.

**Kota neden cömert:** düzgün davranan bir tüketici bu sınıra hiç yaklaşmaz. CRM ölçüldü
(2026-08-12): kotası 3/dk'ya sabitlenmiş bir servise karşı **12 CRM isteği yalnız 1 üst istek**
üretti — kendi 15 dakikalık cache'i araya girdiği için. Sınır, doğru davranan tüketiciyi kısmak
için değil, cache'i devre dışı kalan ya da döngüye giren bir tüketicinin yarıçapını sınırlamak
içindir. Aynı ölçümde CRM cache'i kasten devre dışı bırakıldığında kota doldu, servis `429`
döndü ve **CRM'in ucu yine 200 döndü** (`degraded=true`): kota aşımı gösterimi kısar, satışı
durdurmaz.

> **Anahtar YAML'de map ANAHTARI olarak yazılamaz.** İlk kurulum
> `keys: "[${CURRENCY_CRM_KEY}]": crm` biçimindeydi ve **sessizce** bozuktu: YAML map
> anahtarındaki yer tutucu çözülmez (çözüm değerlere uygulanır), servis ortam değişkeninin
> *adını* anahtar sanar ve doğru anahtarla gelen istek bile 401 alır. Yapılandırma doğru
> *görünür*, birim testler geçer, yalnız canlı istek reddedilir. Bu yüzden anahtarlar tek bir
> dizgiden (`CURRENCY_API_KEYS`) ayrıştırılır ve bağlama `CurrencyApiPropertiesTest` ile
> kilitlidir.

## Admin API — dinamik anahtar yönetimi (`/admin/keys`)

`CURRENCY_API_KEYS` yalnız açılışta okunan **statik** anahtarlar içindir; yeni bir tüketiciye
anahtar vermek ya da birini iptal etmek için servisi yeniden başlatmak istemiyorsanız admin
API'sini kullanın: runtime'da anahtar oluşturur/listeler/iptal eder, hiçbir restart gerekmez.

```
POST   /admin/keys       {"consumerName": "reporting", "rateLimitOverride": 30}
GET    /admin/keys                                    → aktif+iptal tüm anahtarlar, anlık kullanımla
DELETE /admin/keys/{id}                                → iptal
```

**Varsayılan KAPALI ve AYRI bir portta çalışır** (`CURRENCY_ADMIN_PORT`, varsayılan `8097`) —
`server.port` (8095) **DEĞİL**. Bu bilinçlidir: servis genellikle bir tünelle (ör. Cloudflare
quick tunnel) internete açılır ve tünel yalnız tek bir portu bilir/yönlendirir; admin uçlarını
aynı portta path filtresiyle "gizlemek" yetmezdi, tünel yine de o porta gelen HER isteği taşır.
Ayrı port, admin yüzeyini tünelin doğası gereği hiç görmediği, dolayısıyla internetten **yapısal
olarak erişilemez** bir yüzey yapar; LAN'dan yine erişilebilir. `X-Admin-Token` kontrolü
(`CURRENCY_ADMIN_TOKEN`) bunun üzerine ikinci bir katmandır, tek başına sınır değildir.

```bash
openssl rand -hex 32   # cikan degeri CURRENCY_ADMIN_TOKEN olarak .env'e yazin
curl -X POST localhost:8097/admin/keys -H "X-Admin-Token: <token>" -d '{"consumerName":"reporting"}'
```

**Dinamik anahtarlar Redis'te tutulur ve Redis kesintisinde FAIL-CLOSED'dır** (401) — servisin
geri kalanının fail-open felsefesinden bilinçli bir sapma: burada Redis doğrulamanın kaynağıdır,
hızlandırıcısı değil, fail-open burada "doğrulayamıyorsam geçir" anlamına gelirdi. Redis
kesintisinde ayakta kalması gereken bir tüketiciye dinamik değil **statik** (`CURRENCY_API_KEYS`)
anahtar verin.

Bir de `admin-ui/` altında bu API'nin üstüne ince bir Angular arayüzü vardır (anahtar listesi,
oluşturma formu, tek seferlik gösterim, limit düzenleme, iptal) —
`podman compose up -d --build --force-recreate` ile `http://localhost:8096`'da ayağa kalkar.

### Kota ile kullanım AYRI şeylerdir

Panel her satır için iki farklı sayı gösterir ve karıştırılmaları bir hata kaynağıdır:

| Sütun | Ne ölçer | Davranışı |
|---|---|---|
| **Kota** (`usageLimit`) | dakikalık pencerede izin verilen istek | sabit |
| *(kota altındayken)* `usageRemaining` | **şu anki dakikada** kalan hak | pencere dolunca kotaya döner |
| **Bugün** (`usageToday`) | bugün (TSİ) yapılan istek | **birikmeli — azalmaz** |
| **Toplam** (`usageTotal`) | anahtar üretildiğinden beri toplam | **birikmeli — azalmaz** |

Panel başlangıçta yalnız `usageRemaining` gösteriyordu ve **donmuş görünüyordu**: 15 dakikada
bir çağıran bir tüketici için o sayı 120'den 119'a inip saniyeler içinde 120'ye dönüyordu.
Sayı doğruydu, **soru yanlıştı** — "şu an kaç hakkın kaldı" ile "ne kadar kullanıyorsun"
farklı sorulardır. `usageRemaining` artık yalnız kotanın altındayken gösterilir: kotaya eşitken
hiçbir bilgi taşımaz, altındayken tam olarak bakılması gereken sayıdır.

Sayım **kotayı geçen** isteklere yapılır (429 alan istek servis edilmedi, kullanım sayılmaz) ve
**anahtar kimliğine** yazılır. Statik `CURRENCY_API_KEYS` anahtarlarının panelde satırı yoktur,
dolayısıyla sayılmazlar.

> **Admin yüzeyi yalnız loopback'e (`127.0.0.1`) bağlıdır.** Önce tüm arayüzlere bağlıydı ama bu
> sabit bir ev ağı varsayımıydı: burası bir dizüstü ve güvenilmeyen bir Wi-Fi'a bağlandığı anda
> panel o ağdaki herkese açılırdı (token LAN'da düz HTTP ile gider, makinenin güvenlik duvarı da
> kapalı olabilir). Başka bir cihazdan yönetmek gerekirse SSH tüneli kullanılır:
> ```bash
> ssh -L 8097:127.0.0.1:8097 -L 8096:127.0.0.1:8096 <kullanici>@<bu-makine>
> ```

## Nerede çalışıyor (ve neyin ne zaman öleceği)

**Cloudflare hiçbir şey barındırmaz.** Her şey servisin kurulu olduğu makinede çalışır;
`cloudflared` o makineden Cloudflare'e **dışarı doğru** bir tünel açar ve gelen istekler o
hazır tünelden geri itilir. Router'da port yönlendirme gerekmemesinin sebebi budur.

```
istemci → Cloudflare kenarı → (cloudflared'in açtığı tünel) → makine:8095
                                                               └ podman VM
                                                                 ├ currency-api  :8095
                                                                 ├ redis
                                                                 ├ admin API     :8097  (yalnız loopback)
                                                                 └ admin panel   :8096  (yalnız loopback)
```

| Ne olursa | Sonuç |
|---|---|
| Konteyner **çökerse** | `restart: unless-stopped` geri getirir |
| Konteyner **elle durdurulursa** | Geri gelmez — elle durdurma kasıtlı sayılır |
| **Redis** düşerse | Dinamik anahtarlar 401 (fail-closed); statik anahtar ve kur sunumu çalışır |
| **cloudflared** çökerse | Dışarısı erişemez, yerel çalışır; LaunchDaemon `KeepAlive` geri getirir |
| **Makine yeniden başlarsa** | Zincir kendiliğinden toparlanır (ölçüldü: ~18 sn) |
| **Makine kapanırsa** | Her şey durur — hiçbir yerde kopyası yoktur |
| İnternet keserse | Dışarısı erişemez; yerel çalışmaya devam eder |

> **Makine yeniden başlayınca konteynerlerin geri gelmesi kendiliğinden OLMAZ ve bu
> ölçülerek öğrenildi.** `restart: unless-stopped` yalnız konteyner çökerse devreye girer;
> VM'in kendisi yeniden başladığında konteynerleri geri getiren şey `podman-restart.service`'tir
> ve varsayılan olarak **kapalıdır**. Üstelik konteynerler **rootless** çalıştığı için servisin
> `root` seviyesinde etkinleştirilmesi işe yaramaz (root, kullanıcının konteynerlerini görmez);
> ayrıca **linger** açılmalıdır, yoksa kullanıcının systemd yöneticisi açılışta hiç başlamaz:
>
> ```bash
> podman machine ssh 'systemctl --user enable --now podman-restart.service'
> podman machine ssh 'sudo loginctl enable-linger core'
> ```
>
> Doğrulama, iddiaya güvenmeden yapılmalıdır — VM durdurulup başlatılır ve **hiçbir şey elle
> yapılmadan** servisin geri gelip gelmediğine bakılır.

## İşletim (`ops/`)

```bash
./ops/backup-redis.sh                    # Redis yedeği al (varsayılan: son 14 kopya saklanır)
./ops/backup-redis.sh --restore <dosya>  # geri yükle
./ops/healthcheck.sh                     # zincirin tamamını public URL üzerinden kontrol et
./ops/rotate-logs.sh                     # 10 MB'ı aşan logları döndür (son 3 kopya)
```

**Yedek neden gerekli:** dağıtılmış anahtarların hash'leri ve 7 günlük "son geçerli kur" ağı
yalnız `currency-redis` volume'unda yaşar. O volume kaybolursa **tüketicilere dağıtılmış tüm
anahtarlar** bir anda çalışmaz olur ve geri getirilemez (ham anahtarlar tasarım gereği hiçbir
yerde saklanmaz).

**Sağlık kontrolü neden public URL'den:** konteynerin kendi healthcheck'i yalnız süreci görür.
Tünel düşerse ya da servis ayakta olduğu hâlde kur veremez hâle gelirse konteyner "sağlıklı"
görünmeye devam eder — oysa dışarıdaki tüketici için servis yoktur. Kontrol, tüketicinin
gördüğü yerden yapılır ve yalnız **durum değiştiğinde** bildirir (arıza → toparlanma).

İkisi de `launchd` ile zamanlanabilir (sağlık 5 dk'da bir, yedek her gece 03:30):

```bash
cp ops/com.currencyapi.healthcheck.plist ops/com.currencyapi.backup.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.currencyapi.healthcheck.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.currencyapi.backup.plist
```

Uzaktayken de haber almak için isteğe bağlı: `CURRENCY_ALERT_WEBHOOK=https://...`
(Slack/Discord/ntfy). Verilmezse yalnız macOS bildirimi kullanılır.

**Log rotasyonu** ayrı bir zamanlayıcı istemez: `healthcheck.sh` zaten dakikalar arayla
çalıştığı için rotasyonu o çağırır ve asıl iş yalnız dosya eşiği aştığında yapılır.
`cloudflared` kendi logunu döndürmez; sınırsız büyüyen bir log sessiz bir disk doluşudur —
hiçbir hata vermez, yalnız bir gün disk biter ve o gün her şey aynı anda bozulur. Döndürme
dosyayı **silmez, keser**: silinseydi yazan süreç açık dosya tanıtıcısına yazmaya devam eder
ve loglar görünmez olurdu.

## Simülatör yüzü (tüketici testleri) — **varsayılan KAPALI**

Servis, gerçek satıcıların uçlarını taklit eden bir yüzey de sunar — tüketicilerin **kendi**
dayanıklılık davranışlarını sınayabilmesi için (gerçek TCMB'ye "şimdi çök" diyemezsiniz).

> **Açmak için `CURRENCY_SIMULATOR_ENABLED=true` gerekir; verilmezse bu uçlar 404'tür.**
> Sebep: kaos uçları kimlik doğrulaması **istemez** (kaosu süren duman testinin elinde anahtar
> yoktur) ve durum **değiştirir**. İnternete açılmış bir serviste bu ikisi bir arada, uzaktan
> erişilebilir bir arıza düğmesidir — `TCMB_BASE_URL` simülatöre çevrildiği anda yabancı biri
> kur akışını durdurabilirdi.

```
GET  /kurlar/today.xml                       TCMB şekli
GET  /stats/eurofxref/eurofxref-daily.xml    ECB şekli
POST /__mode?source=tcmb|ecb&mode=success|error|timeout|garbage|holiday
POST /__settings?jitter=true&delayMillis=10000
POST /__reset
```

Modlar **kaynak başınadır**: sınanacak asıl davranış "kur kaynağı çöktü" değil
**"TCMB çöktü ama ECB ayakta"**dır. `holiday` (404) TCMB'nin her hafta yaşanan davranışıdır.
Kaos uçları `__` ile başlar; gerçek satıcılarda böyle bir yüzey yoktur ve bu uçlar taklidin
değil **test edilebilirliğin** parçasıdır.

> ⚠️ Kaos ucunu konteynerde koşarken **konteynerin içinden** sürün. Host'ta 8095'i tutan başıboş
> bir süreç publish edilen portu gölgeler; kaos o kopyaya yazılır, tüketici konteynere gider ve
> testler **sessizce yanlış şeyi ölçer** (ölçülerek öğrenildi).

## Test

```bash
mvn test                                   # 189 test — backend
cd admin-ui && npx ng test --watch=false   # 28 test — admin paneli
```

Backend testleri **altyapısızdır** (Redis/ağ gerekmez): saat enjekte edilir, böylece 15 dakikalık
tazelik sınırı gerçekten beklemeden sınanır. Kapsam: yön çevirme, `Unit` çarpanı, XXE,
cache-aside, son geçerli kur, retention, anahtar/kota kapıları, dinamik anahtar yaşam döngüsü,
admin token kapısı, correlation ID ve dil çözümü.

İkisi de her push/PR'da GitHub Actions ile koşar (`.github/workflows/ci.yml`).

> **Redis destekli sınıflar da testlidir ve yine altyapısız.** Sınanan şey Redis'in çalışıp
> çalışmadığı değil, bizim kararlarımızdır: anahtar düzeni, `KEYS` taraması yapılmaması,
> TTL'in yalnız ilk artışta kurulması, `peek`'in sayaç artırmaması ve en önemlisi
> `ApiKeyStore.findByHash`'in **fail-closed**, cache/sayaç sınıflarının **fail-open** olması.
> Bunların hepsi `StringRedisTemplate` taklidiyle sınanır — testcontainers'a gerek kalmadan.
