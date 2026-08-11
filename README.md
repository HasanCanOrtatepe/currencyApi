# currency-api — sahte kur satıcısı (T34 Faz 7)

TCMB ve ECB'nin günlük kur uçlarını, **gerçek satıcıların yollarını ve XML şekillerini birebir
kullanarak** taklit eden bağımsız bir Spring Boot servisi.

> **CRM'in parçası değildir.** `backend/` reaktörüne girmez, Config Server'dan okumaz, Eureka'ya
> kaydolmaz, veritabanı taşımaz. Taklit ettiği şeyin yerinde durması gerekir: reaktöre girseydi
> CRM ile birlikte derlenir, sürümlenir ve kırılırdı — yani "dış hizmet" olmaktan çıkardı.
> `frontend/` ile aynı gerekçe.

## Neden gerçek satıcının yolunu ve şeklini taklit ediyor

CRM tarafında `crm.currency.provider=fake` ile `real` arasındaki farkın **yalnız base URL**
olması gerekiyordu (T34 K3). Bu servis kendine kolay bir sözleşme uydursaydı (`/rates` + JSON),
CRM'de ikinci bir adaptör yazmak gerekirdi ve o adaptör gerçek satıcıya geçişte **hiçbir şey
kanıtlamazdı**. Taklit edilen şey satıcının rahatlığı değil, **bizim adaptörümüzün doğruluğudur**.

Aynı sebeple iki tablonun **yönleri düzeltilmez**: TCMB "1 yabancı birim = X TL" yayınlar, ECB
"1 EUR = X yabancı birim". Sahte servis bunları normalize etseydi CRM'deki iki dönüşüm (TCMB'de
ters çevirme, ECB'de çapraz kur) sahte yığında hiç çalışmaz ve gerçek satıcıya geçişte ilk kez
orada patlardı.

## Uçlar

| Uç | Ne | Not |
|---|---|---|
| `GET /kurlar/today.xml` | TCMB günlük kur belgesi | `Unit` alanı taşır (JPY: 100) |
| `GET /stats/eurofxref/eurofxref-daily.xml` | ECB referans kurları | taban EUR; **EUR'un kendi satırı yoktur** |
| `POST /__mode?source=<tcmb\|ecb>&mode=<...>` | arıza enjeksiyonu | `source` verilmezse ikisi birden |
| `POST /__settings?jitter=<bool>&delayMillis=<n>` | kur oynatma / gecikme süresi | |
| `POST /__reset` | tüm kaynakları normale döndürür | duman testi koşum başına çağırır |
| `GET /__mode` · `GET /actuator/health` | durum | |

Kaos uçları `__` ile başlar: gerçek TCMB/ECB'de böyle bir yüzey yoktur ve olmamalıdır — bunlar
taklidin değil **test edilebilirliğin** parçasıdır.

## Kaos modları

| Mod | Davranış | Ne sınar |
|---|---|---|
| `success` | normal cevap | mutlu yol |
| `error` | 500 | merdivenin cache/TL basamakları |
| `timeout` | **gerçekten bekler** (`delayMillis`, varsayılan 10 sn) | CRM'in HTTP zaman aşımı |
| `garbage` | sözdizimi bozuk XML | tolerant reader / ayrıştırıcı sınırı |
| `holiday` | 404 | **TCMB'nin hafta sonu/tatil davranışı** — yedeğin var oluş sebebi |

**Modlar kaynak başınadır** ve bu servisin asıl değeri budur: sınanacak davranış "kur kaynağı
çöktü" değil **"TCMB çöktü ama ECB ayakta"**dır. Tek bir küresel mod bunu ifade edemezdi; iki
kaynak birlikte düşer ve zincirin devraldığı hiç görülmezdi.

> `timeout` modu **gerçekten bekler** — CRM içindeki `StubExchangeRateClient`'ın aksine. Karşıtlık
> bilinçlidir: stub'da ölçülen şey "bizim kodumuz bloke olmuyor mu", burada ölçülen şey "HTTP
> zaman aşımımız gerçekten çalışıyor mu". Beklemeyen bir sahte servis ikincisini sınayamaz.

## Çalıştırma

```bash
# Tek başına
mvn spring-boot:run                 # http://localhost:8095

# CRM yığınıyla (profiles: [dev] — varsayılan `up -d` ile AÇILMAZ)
cd ../backend
podman compose -f podman-compose.yml --profile dev up -d --build currency-api
curl -s localhost:8095/kurlar/today.xml | head
curl -s -X POST "localhost:8095/__mode?source=tcmb&mode=holiday"
```

CRM'i bu servise bağlamak ve duman testini koşmak için: [../backend/README.md](../backend/README.md)
"Çoklu Para Birimi Dayanıklılık Kanıtı" bölümü.

> ⚠️ Kaos ucunu **konteynerin içinden** sürün (duman testi öyle yapar). Host'ta 8095'i tutan
> başıboş bir süreç publish edilen portu gölgeler; kaos o kopyaya yazılır, CRM ise konteynere
> gider ve testler **sessizce yanlış şeyi ölçer** (ölçülerek öğrenildi).
