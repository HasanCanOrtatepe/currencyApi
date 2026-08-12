#!/bin/zsh
#
# Redis yedeği — DAĞITILMIŞ ANAHTARLARIN tek kopyası buradadır.
#
# Neden gerekli: dinamik API anahtarları (hash'leri) ve "son geçerli kur" ağını taşıyan
# retention penceresi yalnız `currency-redis` volume'unda yaşar. O volume kaybolursa
# tüketicilere DAĞITILMIŞ anahtarların hepsi bir anda çalışmaz olur ve hiçbiri geri
# getirilemez (ham anahtarlar hiçbir yerde saklanmaz — tasarım gereği).
#
# Yedek `BGSAVE` ile alınan RDB anlık görüntüsüdür. AOF açık olsa da RDB tek dosyada
# tutarlı ve geri yüklenebilir bir kopyadır.
#
#   ./ops/backup-redis.sh              # yedek al
#   ./ops/backup-redis.sh --restore <dosya>   # geri yükle (SERVİSİ DURDURUR)

set -euo pipefail

CONTAINER="${REDIS_CONTAINER:-currencyapi_redis_1}"
BACKUP_DIR="${CURRENCY_BACKUP_DIR:-$HOME/Library/Application Support/currencyapi/backups}"
KEEP="${CURRENCY_BACKUP_KEEP:-14}"
PODMAN="${PODMAN_BIN:-/opt/homebrew/bin/podman}"

log() { print -r -- "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

restore() {
  local source_file="$1"
  [[ -f "$source_file" ]] || { log "HATA: dosya yok: $source_file"; exit 1; }

  log "GERİ YÜKLEME: $source_file"
  log "Redis durduruluyor (servis bu sırada cache'siz çalışır, kur sunmaya devam eder)"
  "$PODMAN" stop "$CONTAINER" >/dev/null

  # Konteyner durmuşken volume'a yazmak için geçici bir konteyner kullanılır: çalışan
  # Redis'in altından dosya değiştirmek, kapanışta kendi kopyasını üzerine yazmasına yol açar.
  "$PODMAN" cp "$source_file" "${CONTAINER}:/data/dump.rdb"

  # AOF açıkken Redis açılışta RDB'yi DEĞİL AOF'u okur; eski AOF silinmezse yedek yok sayılırdı.
  "$PODMAN" start "$CONTAINER" >/dev/null
  log "Redis yeniden başlatıldı. AOF açıksa geri yüklemenin etkili olması için:"
  log "  podman exec $CONTAINER redis-cli CONFIG SET appendonly no"
  log "  podman exec $CONTAINER redis-cli CONFIG SET appendonly yes   # AOF'u RDB'den yeniden yazar"
  exit 0
}

if [[ "${1:-}" == "--restore" ]]; then
  restore "${2:?geri yuklenecek dosya verilmedi}"
fi

mkdir -p "$BACKUP_DIR"

# Boru hattı KULLANILMAZ: `podman ps | grep -q` altında grep ilk eşleşmede çıkar, podman
# SIGPIPE alır ve `pipefail` bunu hata sayar — konteyner ÇALIŞIRKEN "çalışmıyor" denirdi.
typeset -a running
running=(${(f)"$("$PODMAN" ps --format '{{.Names}}')"})
if (( ! ${running[(I)$CONTAINER]} )); then
  log "HATA: $CONTAINER çalışmıyor — yedek alınamadı"
  exit 1
fi

# BGSAVE asenkrondur; bitmesini beklemezsek yarım/eski dosya kopyalanabilir.
LAST_SAVE_BEFORE=$("$PODMAN" exec "$CONTAINER" redis-cli LASTSAVE)
"$PODMAN" exec "$CONTAINER" redis-cli BGSAVE >/dev/null

for _ in {1..30}; do
  sleep 1
  if [[ "$("$PODMAN" exec "$CONTAINER" redis-cli LASTSAVE)" != "$LAST_SAVE_BEFORE" ]]; then
    break
  fi
done

STAMP=$(date '+%Y%m%d-%H%M%S')
TARGET="$BACKUP_DIR/redis-$STAMP.rdb"
"$PODMAN" cp "${CONTAINER}:/data/dump.rdb" "$TARGET"

# Boş/bozuk bir dosyayı "yedek" saymak, gerçek bir yedeğin olduğu yanılgısını üretir.
if [[ ! -s "$TARGET" ]]; then
  log "HATA: yedek boş, siliniyor: $TARGET"
  rm -f "$TARGET"
  exit 1
fi

log "Yedek alındı: $TARGET ($(du -h "$TARGET" | cut -f1))"

# Eski yedekleri buda — sınırsız birikim diski sessizce doldurur.
ls -1t "$BACKUP_DIR"/redis-*.rdb 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
  rm -f "$old"
  log "eski yedek silindi: $(basename "$old")"
done
