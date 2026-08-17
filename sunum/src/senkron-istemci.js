'use strict';

/**
 * Senkron istemcisi — desteye sunum anında enjekte edilir (bkz. server.js → kancala()).
 *
 * İki yönü vardır ve ikisi ayrı tutulur:
 *   GELEN  — /olaylar (SSE) → window.__deste.git(n)   (yayınlamaz, yankı olmaz)
 *   GİDEN  — deste ilerledi → window.__senkron(n) → POST /slayt
 */
(function () {
  const deste = window.__deste;
  if (!deste) return; // kanca uygulanmadıysa sessizce normal deste gibi davran

  // ── GİDEN ────────────────────────────────────────────────────────────────
  // Son yazma kazanır: hızlı ok basışlarında araya giren istekleri beklemeye değmez,
  // önemli olan SON slayttır.
  let sonGonderilen = -1;
  let bekleyen = null;

  window.__senkron = function (n) {
    if (n === sonGonderilen) return;
    sonGonderilen = n;
    clearTimeout(bekleyen);
    // 60 ms toparlama: "Home" ya da hızlı basış tek istek olarak gider.
    bekleyen = setTimeout(() => {
      fetch('/slayt', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ slayt: n })
      }).catch(() => durum('kopuk'));
    }, 60);
  };

  // ── GELEN ────────────────────────────────────────────────────────────────
  let sonSurum = -1;

  function baglan() {
    const akis = new EventSource('/olaylar');

    akis.onopen = () => durum('canli');

    akis.onmessage = (olay) => {
      let paket;
      try {
        paket = JSON.parse(olay.data);
      } catch {
        return;
      }
      // Sürüm geriye gitmez: geciken bir paket, yeni slaytı eskisine geri çekmemeli.
      if (paket.surum < sonSurum) return;
      sonSurum = paket.surum;
      sonGonderilen = paket.slayt; // kendi yayınımızı geri göndermeyi engeller
      deste.git(paket.slayt);
      durum('canli');
    };

    // EventSource kendi kendine yeniden bağlanır; burada yalnız GÖSTERGE güncellenir.
    // Elle reconnect yazmak iki yeniden bağlanma döngüsü yaratırdı.
    akis.onerror = () => durum('kopuk');
  }

  // ── Durum göstergesi ─────────────────────────────────────────────────────
  // Sahnede bilinmesi gereken tek şey: "ekranlar birbirine bağlı mı?" Bağlıyken
  // göstergenin görünmesi gerekmez; KOPUKKEN görünmesi şarttır.
  const rozet = document.createElement('div');
  rozet.style.cssText = 'position:fixed;right:12px;top:12px;z-index:60;padding:5px 11px;'
    + 'border-radius:999px;font:700 .72rem "Segoe UI",system-ui,sans-serif;'
    + 'letter-spacing:.06em;text-transform:uppercase;pointer-events:none;'
    + 'transition:opacity .3s ease;opacity:0';
  document.body.appendChild(rozet);

  function durum(hal) {
    if (hal === 'canli') {
      rozet.textContent = '● senkron';
      rozet.style.background = 'rgba(111,207,127,.16)';
      rozet.style.color = '#8FE0A0';
      rozet.style.opacity = '0';
    } else {
      rozet.textContent = '● bağlantı koptu';
      rozet.style.background = 'rgba(255,107,107,.18)';
      rozet.style.color = '#FF9C9C';
      rozet.style.opacity = '1';
    }
  }

  // ── Kaza koruması: ortadaki dokunuş slayt İLERLETMEZ ─────────────────────
  //
  // Destenin kendi tıklama kuralı "ekranın %25'inden sağa tıkla → ilerle"dir. Tek bir
  // yerel sunucu için sorunsuzdu; PAYLAŞILAN bir destede beş telefonun ortasına düşen
  // her kazara dokunuş, projeksiyondaki slaytı da atlatır. Kenarlar bırakılır, orta
  // %76'lık alan yakalanır — yakalama YAKALAMA FAZINDA yapılır, çünkü destenin
  // dinleyicisi de document üzerindedir ve balonlaşma fazında çalışır.
  document.addEventListener('click', (e) => {
    const oran = e.clientX / window.innerWidth;
    if (oran > 0.12 && oran < 0.88) e.stopPropagation();
  }, true);

  baglan();
})();
