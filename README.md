# currency-api

**TCMB'yi ana veri kaynağı alan kur servisi.** Merkez Bankası'nın günlük döviz kuru belgesini
çeker, cache'ler ve kendi JSON sözleşmesiyle sunar. Bağımsız bir mikroservistir: veritabanı yok,
Config Server'dan okumaz, servis kayıt defterine kaydolmaz.

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
  tcmb/  TcmbExchangeRateProvider               (HTTP)
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

### Yeni sağlayıcı eklemek (ör. ECB)

`core/integrations/<satıcı>/` altında `ExchangeRateProvider` uygulaması + kendi DTO'su + kendi
mapper'ı. Satıcının tel formatı o paketten dışarı çıkmaz; API sözleşmesi, cache ve iş katmanı
**değişmez**.

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
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | yalnız `type=redis` iken |

Sıfır konfigürasyonla çalışır: hiçbir ayar verilmeden gerçek TCMB'den kur çeker.

> **Neden `memory` varsayılan:** servis Redis olmadan da ayağa kalkabilmelidir, yoksa basit bir
> birim testi bile altyapı gerektirir ve "test edilebilir" iddiası boşa çıkar. Üretimde birden
> çok instance varsa `redis` olmalıdır: bellekte tutulursa her instance kendi kurunu çeker
> (satıcı isteği instance sayısıyla çarpılır) ve iki instance **farklı kur** döndürebilir.

## Simülatör yüzü (tüketici testleri)

Servis, gerçek satıcıların uçlarını taklit eden bir yüzey de sunar — tüketicilerin **kendi**
dayanıklılık davranışlarını sınayabilmesi için (gerçek TCMB'ye "şimdi çök" diyemezsiniz):

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
mvn test        # 15 test: yön çevirme, Unit çarpanı, XXE, cache-aside, son geçerli kur, retention
```

Testler **altyapısızdır** (Redis/ağ gerekmez): saat enjekte edilir, böylece 15 dakikalık tazelik
sınırı gerçekten beklemeden sınanır.
