'use strict';

/**
 * Pair 3 — Sunum sunucusu (sunum.etiyapi.com)
 *
 * İki iş yapar ve üçüncüsünü BİLİNÇLİ olarak yapmaz:
 *   1. Desteyi ŞİFRE arkasında sunar.
 *   2. Aktif slaytı sunucuda tutar ve değişince herkese CANLI iletir (senkron sunum).
 *   3. Desteyi DÜZENLEMEZ — `public/deste.html` olduğu gibi durur, senkron kancası
 *      sunum anında enjekte edilir (bkz. kancala()).
 *
 * Kur servisiyle ve oyunla İLGİSİZDİR: ayrı yığın, ayrı imaj, ayrı port (8099), ayrı
 * hostname. Aynı depoda olmasının tek sebebi aynı domaini ve aynı Cloudflare tünelini
 * paylaşmasıdır — `oyun/` ile birebir aynı gerekçe.
 *
 * SIFIR npm BAĞIMLILIĞI: yalnız Node'un kendi modülleri. Bir sunum sunucusunun bir
 * bağımlılık ağacı taşıması, sunumdan bir gün önce `npm install` kırılması riskini
 * sunumun kendisine bağlamak olurdu.
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = Number(process.env.SUNUM_PORT || 8099);
const SIFRE = process.env.SUNUM_SIFRE || '';

/**
 * Çerez imzası için gizli anahtar. Verilmezse her AÇILIŞTA rastgele üretilir — yani
 * sunucu yeniden başladığında herkes tekrar şifre girer. Bu bilinçli bir varsayılandır:
 * sabit bir anahtar uydurmak, onu koda gömmek demek olurdu.
 */
const CEREZ_ANAHTARI = process.env.SUNUM_CEREZ_ANAHTARI || crypto.randomBytes(32).toString('hex');

/** Oturum ömrü — bir sunum günü. */
const OTURUM_SANIYE = Number(process.env.SUNUM_OTURUM_SANIYE || 12 * 60 * 60);

/**
 * Anonim çağıranın adresini taşıyan başlık.
 *
 * KUR SERVİSİNDE ÖLÇÜLEREK ÖĞRENİLDİ: tünel arkasında `socket.remoteAddress` TÜM internet
 * trafiği için aynı değeri verir. "Deneme sayısı IP başına" cümlesi o hâliyle "tek kova,
 * tüm dünya" demektir ve tek bir kaba kuvvet denemesi, sunumdan beş dakika önce EKİBİ
 * kilitler. Bu yüzden kimlik başlıktan okunur (cloudflared onu kendisi yazar).
 *
 * Kur servisinde bu ayarın varsayılanı BOŞTUR ("başlığa güvenme"); burada varsayılan
 * doludur çünkü bu servis yalnız tünel arkasında var olmak üzere kurulmuştur. Uydurulmuş
 * bir başlığa karşı ikinci bir katman vardır: aşağıdaki KÜRESEL tavan.
 */
const IP_BASLIGI = (process.env.SUNUM_IP_BASLIGI || 'cf-connecting-ip').toLowerCase();

const KOK = path.join(__dirname, '..', 'public');

// ─────────────────────────────────────────────────────────────────────────────
// Şifre denemesi sınırı — iki katmanlı
// ─────────────────────────────────────────────────────────────────────────────
const PENCERE_MS = 5 * 60 * 1000;
const KIMLIK_BASINA = 10;
/**
 * Küresel tavan, kimlik başına sınırın YEDEĞİDİR: başlık uydurulabilen bir kurulumda
 * saldırgan her istekte yeni bir kimlik yazıp kimlik sınırını atlayabilir. Cömerttir —
 * amacı ekibi kilitlemek değil, sınırsız denemeyi kapatmaktır.
 */
const KURESEL = 120;

const denemeler = new Map(); // kimlik -> [zaman damgaları]
let kureselDenemeler = [];

function temizle(liste, simdi) {
  return liste.filter((t) => simdi - t < PENCERE_MS);
}

function denemeHakkiVar(kimlik) {
  const simdi = Date.now();
  kureselDenemeler = temizle(kureselDenemeler, simdi);
  const kendi = temizle(denemeler.get(kimlik) || [], simdi);
  denemeler.set(kimlik, kendi);
  return kendi.length < KIMLIK_BASINA && kureselDenemeler.length < KURESEL;
}

function denemeKaydet(kimlik) {
  const simdi = Date.now();
  denemeler.set(kimlik, [...temizle(denemeler.get(kimlik) || [], simdi), simdi]);
  kureselDenemeler = [...temizle(kureselDenemeler, simdi), simdi];
}

function istemciKimligi(req) {
  const basliktan = req.headers[IP_BASLIGI];
  if (typeof basliktan === 'string' && basliktan.trim()) {
    return basliktan.split(',')[0].trim();
  }
  return req.socket.remoteAddress || 'bilinmeyen';
}

// ─────────────────────────────────────────────────────────────────────────────
// Oturum — imzalı çerez (node:crypto, kütüphane yok)
// ─────────────────────────────────────────────────────────────────────────────
const CEREZ_ADI = 'sunum_oturum';

function imzala(veri) {
  return crypto.createHmac('sha256', CEREZ_ANAHTARI).update(veri).digest('base64url');
}

function biletUret() {
  const govde = String(Date.now() + OTURUM_SANIYE * 1000);
  return `${govde}.${imzala(govde)}`;
}

function biletGecerli(bilet) {
  if (typeof bilet !== 'string' || !bilet.includes('.')) return false;
  const [govde, imza] = bilet.split('.', 2);
  const beklenen = imzala(govde);
  // timingSafeEqual eşit uzunluk ister; farklı uzunluk zaten geçersizdir.
  if (imza.length !== beklenen.length) return false;
  if (!crypto.timingSafeEqual(Buffer.from(imza), Buffer.from(beklenen))) return false;
  return Number(govde) > Date.now();
}

function cerezOku(req, ad) {
  const ham = req.headers.cookie;
  if (!ham) return null;
  for (const parca of ham.split(';')) {
    const [k, ...v] = parca.trim().split('=');
    if (k === ad) return decodeURIComponent(v.join('='));
  }
  return null;
}

/** Tünel arkasında https'tir; localhost'ta denerken Secure çerez GÖNDERİLMEZ, o yüzden koşullu. */
function guvenliMi(req) {
  return String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() === 'https';
}

function oturumVar(req) {
  return biletGecerli(cerezOku(req, CEREZ_ADI));
}

// ─────────────────────────────────────────────────────────────────────────────
// Senkron durum — aktif slayt sunucuda
// ─────────────────────────────────────────────────────────────────────────────
let aktifSlayt = 0;
let surum = 0;
const dinleyiciler = new Set();

function slaytAyarla(n) {
  const hedef = Number.isInteger(n) && n >= 0 && n < 200 ? n : null;
  if (hedef === null || hedef === aktifSlayt) return false;
  aktifSlayt = hedef;
  surum += 1;
  yayinla();
  return true;
}

function yayinla() {
  const paket = `data: ${JSON.stringify({ slayt: aktifSlayt, surum })}\n\n`;
  for (const res of dinleyiciler) {
    try {
      res.write(paket);
    } catch {
      dinleyiciler.delete(res); // kopmuş bağlantı; yazma hatası akışı durdurmamalı
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Desteye senkron kancası — DOSYA DEĞİŞTİRİLMEZ, sunum anında enjekte edilir
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `public/deste.html` dokunulmamış kalır: yeni sürüm geldiğinde tek yapılacak dosyayı
 * değiştirmektir, kimse bir betiği yeniden yapıştırmak zorunda kalmaz.
 *
 * Kanca destenin İÇ durumuna bağlanır (`i` ve `render`), çünkü sınıfları dışarıdan
 * ellemek iki denetleyici yaratır ve destenin kendi sayacı ekrandan sapardı.
 *
 * ÇAPA BULUNAMAZSA SUNUCU HİÇ AÇILMAZ. Sessizce senkronsuz açılmak buradaki en kötü
 * sonuçtur: sahnede fark edilir — kur servisindeki "sessizce reddeden bir servis, hiç
 * açılmayandan tehlikelidir" kuralının aynısı.
 */
const CAPA = '  render(); paint();\n})();';

const KANCA = `  render(); paint();

  // ── SENKRON KANCASI (sunum sunucusu tarafindan enjekte edildi) ──
  let _sessiz = false;
  const _render = render;
  render = function () {
    _render();
    if (!_sessiz && window.__senkron) window.__senkron(i);
  };
  window.__deste = {
    sayi: () => slides.length,
    indeks: () => i,
    // Gelen senkron: uygular ama YAYINLAMAZ — yoksa iki istemci arasinda sonsuz yankı olur.
    git(n) {
      const h = Math.max(0, Math.min(slides.length - 1, n));
      if (h === i) return;
      i = h;
      _sessiz = true;
      try { render(); } finally { _sessiz = false; }
    }
  };
})();`;

function kancala(html) {
  if (!html.includes(CAPA)) {
    throw new Error(
      'Destede senkron capasi bulunamadi (public/deste.html). Beklenen: "render(); paint();" '
      + 'ile kapanan IIFE. Deste degistiyse sunum/src/server.js icindeki CAPA guncellenmeli.'
    );
  }
  return html
    .replace(CAPA, KANCA)
    .replace('</body>', '  <script src="/senkron.js"></script>\n</body>');
}

let DESTE = null;
function desteyiYukle() {
  DESTE = kancala(fs.readFileSync(path.join(KOK, 'deste.html'), 'utf8'));
}

// ─────────────────────────────────────────────────────────────────────────────
// Giriş sayfası — destenin kendi teması
// ─────────────────────────────────────────────────────────────────────────────
function girisSayfasi(hata) {
  return `<!DOCTYPE html>
<html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pair 3 · Sunum</title>
<style>
  :root{--brand:#F58220;--navy:#242441;--shell:#15152A;--ink:#F4F4F8;--muted:#A9A9C4;--bad:#FF6B6B}
  *{box-sizing:border-box;margin:0;padding:0}
  body{min-height:100vh;display:grid;place-items:center;background:
    radial-gradient(120% 90% at 15% 10%,#2E2E56 0%,var(--navy) 55%,#191932 100%);
    color:var(--ink);font-family:"Segoe UI",Inter,system-ui,-apple-system,sans-serif;padding:24px}
  .kutu{width:100%;max-width:380px}
  .bar{width:64px;height:5px;background:var(--brand);border-radius:3px;margin-bottom:22px}
  .ust{font-size:.76rem;letter-spacing:.16em;text-transform:uppercase;color:var(--brand);
    font-weight:800;margin-bottom:10px}
  h1{font-size:1.6rem;line-height:1.15;letter-spacing:-.02em;font-weight:800;margin-bottom:8px}
  p{color:var(--muted);font-size:.92rem;line-height:1.55;margin-bottom:22px}
  label{display:block;font-size:.78rem;letter-spacing:.08em;text-transform:uppercase;
    color:var(--muted);font-weight:700;margin-bottom:7px}
  input{width:100%;padding:13px 15px;font-size:1rem;color:var(--ink);
    background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.16);border-radius:10px}
  input:focus{outline:none;border-color:var(--brand);background:rgba(255,255,255,.09)}
  button{width:100%;margin-top:14px;padding:13px;font-size:1rem;font-weight:800;
    color:#1A1A31;background:var(--brand);border:0;border-radius:10px;cursor:pointer}
  button:hover{filter:brightness(1.08)}
  .hata{margin-top:14px;padding:11px 13px;border-radius:9px;font-size:.88rem;line-height:1.45;
    background:rgba(255,107,107,.12);border-left:3px solid var(--bad);color:#FFC4C4}
</style></head>
<body>
  <form class="kutu" method="post" action="/giris">
    <div class="bar"></div>
    <div class="ust">CRM Lite · 4. Bölüm</div>
    <h1>Dayanıklılık, Kur Entegrasyonu ve Denetim İzi</h1>
    <p>Deste şifreyle korunuyor. Girdikten sonra <b>herkes aynı slaytı görür</b> —
      biri ilerlettiğinde tüm ekranlar birlikte geçer.</p>
    <label for="sifre">Şifre</label>
    <input id="sifre" name="sifre" type="password" autocomplete="current-password"
           autofocus required>
    <button type="submit">Desteyi aç</button>
    ${hata ? `<div class="hata">${hata}</div>` : ''}
  </form>
</body></html>`;
}

// ─────────────────────────────────────────────────────────────────────────────
// Yanıt yardımcıları
// ─────────────────────────────────────────────────────────────────────────────
function yaz(res, kod, tur, govde, ekBaslik = {}) {
  res.writeHead(kod, {
    'Content-Type': tur,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    ...ekBaslik
  });
  res.end(govde);
}

function govdeOku(req, limit = 4096) {
  return new Promise((coz, red) => {
    let veri = '';
    req.on('data', (parca) => {
      veri += parca;
      // Gövde SINIRSIZ okunmaz — kur servisindeki BoundedHttpBody ile aynı gerekçe.
      if (veri.length > limit) {
        req.destroy();
        red(new Error('govde cok buyuk'));
      }
    });
    req.on('end', () => coz(veri));
    req.on('error', red);
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// Sunucu
// ─────────────────────────────────────────────────────────────────────────────
const sunucu = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://yerel');
  const yol = url.pathname;

  // SAĞLIK UCU ŞİFRESİZDİR ve TAM ADLA eşleşir.
  //
  // Şifresiz: onu çağıran taraf orkestratördür (compose healthcheck) ve elinde şifre
  // yoktur; istenseydi konteyner sürekli sağlıksız görünür, kendini yeniden başlatırdı.
  //
  // TAM AD: kur servisinde muafiyet "/actuator" ÖNEKİNİN tamamına yazılmıştı ve bu,
  // tünelin taşıdığı portta ölçülerek doğrulanmış bir sızıntıydı. Burada önek yazılsaydı
  // "/saglik-deste" gibi bir yol uydurup şifreyi atlamak mümkün olurdu.
  if (yol === '/saglik') {
    return yaz(res, 200, 'application/json; charset=utf-8',
      JSON.stringify({ durum: 'AYAKTA', slayt: aktifSlayt, dinleyici: dinleyiciler.size }));
  }

  if (yol === '/giris' && req.method === 'POST') {
    const kimlik = istemciKimligi(req);
    if (!denemeHakkiVar(kimlik)) {
      console.warn(`[sunum] sifre denemesi sinirlandi kimlik=${kimlik}`);
      return yaz(res, 429, 'text/html; charset=utf-8',
        girisSayfasi('Çok fazla deneme yapıldı. Birkaç dakika sonra tekrar deneyin.'));
    }
    let sifre = '';
    try {
      sifre = new URLSearchParams(await govdeOku(req)).get('sifre') || '';
    } catch {
      return yaz(res, 400, 'text/plain; charset=utf-8', 'gecersiz istek');
    }

    // Sabit zamanlı karşılaştırma: uzunluk farkı da bir ipucudur, o yüzden ikisi de hash'lenir.
    const gelen = crypto.createHash('sha256').update(sifre).digest();
    const dogru = crypto.createHash('sha256').update(SIFRE).digest();
    if (!SIFRE || !crypto.timingSafeEqual(gelen, dogru)) {
      denemeKaydet(kimlik);
      // ŞİFRE LOGLANMAZ — yanlış şifre bile bir sırdır (çoğu zaman başka bir yerin
      // geçerli şifresidir). Kur servisindeki "anahtarlar log'a girmez" kuralının aynısı.
      console.warn(`[sunum] hatali sifre kimlik=${kimlik}`);
      return yaz(res, 401, 'text/html; charset=utf-8', girisSayfasi('Şifre hatalı.'));
    }

    const cerez = [
      `${CEREZ_ADI}=${encodeURIComponent(biletUret())}`,
      'Path=/', 'HttpOnly', 'SameSite=Lax', `Max-Age=${OTURUM_SANIYE}`,
      guvenliMi(req) ? 'Secure' : null
    ].filter(Boolean).join('; ');

    console.log(`[sunum] giris basarili kimlik=${kimlik}`);
    return yaz(res, 303, 'text/plain; charset=utf-8', '', { Location: '/', 'Set-Cookie': cerez });
  }

  if (yol === '/cikis') {
    return yaz(res, 303, 'text/plain; charset=utf-8', '', {
      Location: '/',
      'Set-Cookie': `${CEREZ_ADI}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`
    });
  }

  // ── Buradan sonrası ŞİFRE İSTER ──
  if (!oturumVar(req)) {
    if (yol === '/' && req.method === 'GET') {
      return yaz(res, 200, 'text/html; charset=utf-8', girisSayfasi(null));
    }
    return yaz(res, 401, 'application/json; charset=utf-8', JSON.stringify({ hata: 'sifre gerekli' }));
  }

  if (yol === '/' && req.method === 'GET') {
    return yaz(res, 200, 'text/html; charset=utf-8', DESTE);
  }

  if (yol === '/senkron.js' && req.method === 'GET') {
    return yaz(res, 200, 'application/javascript; charset=utf-8',
      fs.readFileSync(path.join(__dirname, 'senkron-istemci.js'), 'utf8'));
  }

  // Canlı yayın: aktif slayt değişince tüm ekranlara iletilir.
  //
  // SSE seçildi, WebSocket DEĞİL: akış tek yönlüdür (sunucu → ekran) ve SSE, Node'un kendi
  // http modülüyle çalışır. WebSocket bir bağımlılık ağacı ya da elle protokol uygulaması
  // gerektirirdi — sunumdan bir gün önce kırılabilecek bir şey daha.
  if (yol === '/olaylar' && req.method === 'GET') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store',
      Connection: 'keep-alive',
      // Cloudflare/vekil arabelleklemesi SSE'yi sessizce biriktirir ve slayt geç gelir.
      'X-Accel-Buffering': 'no'
    });
    res.write(`data: ${JSON.stringify({ slayt: aktifSlayt, surum })}\n\n`);
    dinleyiciler.add(res);

    // Yorum satırı canlı tutma sinyalidir: tünel ve vekiller sessiz bağlantıyı kapatır,
    // kapanan bağlantı da slaytın gelmemesi demektir.
    const nabiz = setInterval(() => {
      try { res.write(': nabiz\n\n'); } catch { /* kopmuşsa aşağıdaki close temizler */ }
    }, 20000);

    req.on('close', () => {
      clearInterval(nabiz);
      dinleyiciler.delete(res);
    });
    return undefined;
  }

  if (yol === '/slayt' && req.method === 'POST') {
    let istek;
    try {
      istek = JSON.parse(await govdeOku(req, 256));
    } catch {
      return yaz(res, 400, 'application/json; charset=utf-8', JSON.stringify({ hata: 'gecersiz govde' }));
    }
    slaytAyarla(istek?.slayt);
    return yaz(res, 200, 'application/json; charset=utf-8',
      JSON.stringify({ slayt: aktifSlayt, surum }));
  }

  return yaz(res, 404, 'application/json; charset=utf-8', JSON.stringify({ hata: 'bulunamadi' }));
});

// ─────────────────────────────────────────────────────────────────────────────
// Açılış — yanlış yapılandırmayla AÇILMAZ
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Şifresiz açılmak, "şifreli olacak" diye kurulmuş bir yüzeyi sessizce herkese açmak
 * demektir. Kur servisindeki `auth.enabled` kuralının aynısı: sessizce yanlış davranan
 * bir servis, hiç açılmayandan tehlikelidir.
 */
if (!SIFRE) {
  console.error('[sunum] SUNUM_SIFRE tanimsiz — sunucu ACILMIYOR. sunum/.env dosyasina yazin.');
  process.exit(1);
}
if (SIFRE.length < 8) {
  console.error('[sunum] SUNUM_SIFRE 8 karakterden kisa — sunucu ACILMIYOR.');
  process.exit(1);
}

// Deste açılışta okunur ve kancalanır: çapa bulunamazsa burada patlar, sahnede değil.
try {
  desteyiYukle();
} catch (e) {
  console.error(`[sunum] ${e.message}`);
  process.exit(1);
}

sunucu.listen(PORT, () => {
  console.log(`[sunum] ayakta port=${PORT} slayt=${aktifSlayt} kimlik-basligi=${IP_BASLIGI}`);
  if (!process.env.SUNUM_CEREZ_ANAHTARI) {
    console.log('[sunum] SUNUM_CEREZ_ANAHTARI verilmedi — yeniden baslatmada herkes tekrar sifre girer');
  }
});
