#!/bin/zsh
#
# Servis sağlık kontrolü — "gece 3'te düştü, kimse bilmiyor" sorununa karşı.
#
# Konteynerlerin kendi healthcheck'i + `restart: unless-stopped` süreç ölürse toparlar; ama
# ZİNCİRİN TAMAMINI kimse kontrol etmiyordu: tünel düşerse, DNS bozulursa ya da servis ayakta
# olduğu hâlde kur veremez hâle gelirse konteyner "sağlıklı" görünmeye devam eder ve
# dışarıdaki tüketici için servis YOKTUR. Bu yüzden kontrol, tüketicinin gördüğü yerden —
# yani public URL üzerinden — yapılır.
#
# Bildirim: macOS bildirimi (makinedeyken) + isteğe bağlı webhook (uzaktayken).
#   CURRENCY_ALERT_WEBHOOK=https://...   # Slack/Discord/ntfy vb. — verilmezse atlanır
#
#   ./ops/healthcheck.sh          # tek sefer kontrol
#   ./ops/healthcheck.sh --quiet  # yalnız sorun varsa çıktı üret (zamanlanmış çalıştırma)

set -uo pipefail

PUBLIC_URL="${CURRENCY_PUBLIC_URL:-https://kur.etiyapi.com}"
ADMIN_URL="${CURRENCY_ADMIN_URL:-http://127.0.0.1:8097}"
LOG_FILE="${CURRENCY_HEALTH_LOG:-$HOME/Library/Logs/currencyapi/healthcheck.log}"
STATE_FILE="${CURRENCY_HEALTH_STATE:-$HOME/Library/Application Support/currencyapi/health.state}"
TIMEOUT="${CURRENCY_HEALTH_TIMEOUT:-15}"

QUIET=0
[[ "${1:-}" == "--quiet" ]] && QUIET=1

mkdir -p "${LOG_FILE:h}" "${STATE_FILE:h}"

log() {
  print -r -- "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG_FILE"
  (( QUIET )) || print -r -- "$*"
}

failures=()

# 1) Tanıtım sayfası — tünel + servis ayakta mı
code=$(curl -s -o /dev/null -w '%{http_code}' -m "$TIMEOUT" "$PUBLIC_URL/" || echo 000)
[[ "$code" == "200" ]] || failures+=("public sayfa HTTP $code")

# 2) Kur ucu GERÇEKTEN kur veriyor mu — "ayakta ama kursuz" durumunu yakalar.
#    Anahtarsız önizleme ucu kullanılır: bu script'in içinde anahtar tutmak gerekmesin.
preview=$(curl -s -m "$TIMEOUT" "$PUBLIC_URL/api/v1/rates/preview" || echo '')
if [[ "$preview" != *'"unitPrice"'* ]]; then
  failures+=("kur ucu kur DONDURMEDI")
fi

# 3) Admin instance (yalnız loopback) — anahtar yönetimi çalışıyor mu
code=$(curl -s -o /dev/null -w '%{http_code}' -m "$TIMEOUT" "$ADMIN_URL/actuator/health" || echo 000)
[[ "$code" == "200" ]] || failures+=("admin instance HTTP $code")

previous="ok"
[[ -f "$STATE_FILE" ]] && previous=$(<"$STATE_FILE")

if (( ${#failures} == 0 )); then
  print -r -- "ok" > "$STATE_FILE"
  # Düzelme de bildirilir: yalnız arıza bildirilseydi "hâlâ bozuk mu?" sorusu cevapsız kalırdı.
  if [[ "$previous" != "ok" ]]; then
    log "TOPARLANDI: servis yeniden sağlıklı"
    osascript -e 'display notification "Servis yeniden sağlıklı." with title "currency-api"' 2>/dev/null
    [[ -n "${CURRENCY_ALERT_WEBHOOK:-}" ]] && \
      curl -s -m 10 -X POST -H 'Content-Type: application/json' \
        -d '{"text":"currency-api: TOPARLANDI — servis yeniden sağlıklı"}' \
        "$CURRENCY_ALERT_WEBHOOK" >/dev/null
  fi
  (( QUIET )) || log "saglikli"
  exit 0
fi

message="currency-api ARIZA: ${(j:, :)failures}"
log "$message"
print -r -- "down" > "$STATE_FILE"

# Zaten arızalıyken her turda bildirim göndermek, bildirimleri gürültüye çevirip
# okunmaz hâle getirir — yalnız DURUM DEĞİŞTİĞİNDE haber verilir.
if [[ "$previous" == "ok" ]]; then
  osascript -e "display notification \"${message//\"/}\" with title \"currency-api\"" 2>/dev/null
  [[ -n "${CURRENCY_ALERT_WEBHOOK:-}" ]] && \
    curl -s -m 10 -X POST -H 'Content-Type: application/json' \
      -d "{\"text\":\"${message//\"/}\"}" "$CURRENCY_ALERT_WEBHOOK" >/dev/null
fi

exit 1
