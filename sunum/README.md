# Pair 3 Sunum Sunucusu — `sunum.etiyapi.com`

Slayt destesini **şifre arkasında** sunar ve aktif slaytı **sunucuda** tutar: biri ilerlettiğinde
**herkesin ekranı birlikte geçer.** Sen kürsüdeyken bir arkadaşının telefonundan slayt çevirmesi
böyle çalışır.

**Kur servisiyle ve oyunla İLGİSİZDİR.** Ayrı yığın, ayrı imaj, ayrı port (8099), ayrı hostname.
Aynı depoda olmasının tek sebebi aynı domaini ve aynı Cloudflare tünelini paylaşmasıdır —
`oyun/` ile birebir aynı gerekçe. **Maven reaktörüne dahil değildir.**

```bash
cp .env.example .env          # SUNUM_SIFRE'yi doldur
cd sunum && podman compose up -d --build --force-recreate
```

`--force-recreate` **zorunludur**: yalnız `--build` ile imaj yeniden derlenir ama konteyner
yeniden oluşturulmayabilir ve **eski desteyi sunmaya devam eder.** Arıza sessizdir. Doğrulama:
`podman ps` çıktısındaki **Up süresi** dağıtımdan yeni olmalıdır.

## Uçlar

| Yol | Şifre | Ne yapar |
|---|---|---|
| `GET /` | ister | Deste (şifre yoksa giriş sayfası) |
| `POST /giris` | — | Şifreyi doğrular, imzalı çerez verir |
| `GET /cikis` | — | Çerezi siler |
| `GET /olaylar` | ister | **SSE** — aktif slayt değişince canlı iletir |
| `POST /slayt` | ister | Aktif slaytı ayarlar ve herkese yayınlar |
| `GET /saglik` | **istemez** | Orkestratör için — aşağıya bkz. |

## Kararlar

**Deste DÜZENLENMEZ.** `public/deste.html` konuşmacının dosyasının birebir kopyasıdır; senkron
kancası **sunum anında enjekte edilir** (`server.js` → `kancala()`). Yeni sürüm geldiğinde tek
yapılacak şey dosyayı değiştirmektir — kimse bir betiği yeniden yapıştırmak zorunda kalmaz.

**Çapa bulunamazsa sunucu HİÇ AÇILMAZ.** Kanca destenin iç durumuna (`i`, `render`) bağlanır;
deste değişip çapa kaybolursa sunucu açılışta patlar. Sessizce senkronsuz açılmak buradaki en
kötü sonuçtur — **sahnede** fark edilir. Kur servisindeki *"sessizce reddeden bir servis, hiç
açılmayandan tehlikelidir"* kuralının aynısı.

**Şifresiz AÇILMAZ.** `SUNUM_SIFRE` tanımsızsa ya da 8 karakterden kısaysa süreç düşer.
Şifresiz açılmak, "şifreli olacak" diye kurulmuş bir yüzeyi sessizce herkese açmaktır.

**`/saglik` şifresizdir ve TAM ADLA eşleşir.** Şifresiz, çünkü onu çağıran taraf orkestratördür
(compose healthcheck) ve **elinde şifre yoktur**; istenseydi konteyner sürekli sağlıksız görünür
ve kendini yeniden başlatırdı. **Tam ad**, çünkü kur servisinde muafiyet `/actuator` *önekinin*
tamamına yazılmıştı ve bu, tünelin taşıdığı portta **ölçülerek doğrulanmış bir sızıntıydı** —
önek yazılsaydı `/saglik-deste` gibi bir yol uydurup şifreyi atlamak mümkün olurdu.

**Deneme sayacının kimliği `CF-Connecting-IP`'dir, socket adresi DEĞİL.** Tünel arkasında
`socket.remoteAddress` tüm internet trafiği için aynıdır (kur servisinde ölçüldü:
`remote=10.89.0.3`) — o hâliyle "deneme sayısı IP başına" cümlesi *"tek kova, tüm dünya"*
demektir ve tek bir kaba kuvvet denemesi **sunumdan beş dakika önce ekibi kilitler.** Uydurulmuş
bir başlığa karşı ikinci katman **küresel deneme tavanıdır** (120/5 dk).

> Kur servisinde bu ayarın varsayılanı **boştur** ("başlığa güvenme"); burada **doludur**, çünkü
> bu servis yalnız tünel arkasında var olmak üzere kurulmuştur. Portu doğrudan güvenilmeyen bir
> ağa açacaksan `SUNUM_IP_BASLIGI`'nı boşalt.

**Şifre loglanmaz.** Yanlış şifre bile bir sırdır (çoğu zaman başka bir yerin geçerli
şifresidir). Log'a yalnız kimlik ve "hatalı şifre" girer.

**SSE seçildi, WebSocket değil.** Akış tek yönlüdür (sunucu → ekran) ve SSE Node'un kendi `http`
modülüyle çalışır. WebSocket bir bağımlılık ağacı ya da elle protokol uygulaması gerektirirdi —
**sunumdan bir gün önce kırılabilecek bir şey daha.** Aynı gerekçeyle **sıfır npm bağımlılığı**
vardır ve `npm install` adımı yoktur.

**Ortadaki dokunuş slayt İLERLETMEZ.** Destenin kendi kuralı "ekranın %25'inden sağa tıkla →
ilerle"dir; tek bir yerel sunucu için sorunsuzdu. **Paylaşılan** bir destede beş telefonun
ortasına düşen her kazara dokunuş projeksiyondaki slaytı da atlatır. Kenarlar (%12) bırakıldı,
orta alan yakalama fazında engellendi. Klavye okları ve boşluk tuşu **aynen çalışır.**

**Durum bellektedir ve bu bilinçlidir.** Aktif slayt tek bir tamsayıdır; veritabanı yok, dosyaya
yazma yok. Konteyner yeniden başlarsa deste 1. slayta döner — sunumdan önce `SUNUM_CEREZ_ANAHTARI`
doldurulursa **kimsenin tekrar şifre girmesi gerekmez.**

## Sahnede

| | |
|---|---|
| Adres | <https://sunum.etiyapi.com> |
| Şifre | `sunum/.env` → `SUNUM_SIFRE` (ekiple paylaşılır) |
| Tam ekran | <kbd>F</kbd> |
| Gezinme | <kbd>←</kbd> <kbd>→</kbd> <kbd>boşluk</kbd> · kenarlara tıklama |
| Prova barı | <kbd>H</kbd> ile açılır, <kbd>T</kbd> kronometre, <kbd>R</kbd> sıfırlar |
| Bağlantı koptu | Sağ üstte **kırmızı rozet** çıkar. Bağlıyken rozet görünmez. |

**Hazırlık notu (`SUNUM_HAZIRLIK_V3.html`) burada YAYINLANMAZ** — içinde "tuzak" satırları, kesme
planı ve "şu kelimeyi ağzından çıkarma" uyarıları var; dosyanın kendisi *"yalnız konuşmacı
içindir"* diyor. Yansıtılan destede işi yoktur.

## Desteyi güncellemek

```bash
cp ~/Downloads/SUNUM_V3.html sunum/public/deste.html
cd sunum && podman compose up -d --build --force-recreate
podman logs pair3-sunum | tail -3     # çapa bulunamadıysa BURADA görürsün, sahnede değil
```
