#!/bin/zsh
#
# Log döndürme — `cloudflared` kendi logunu döndürmez ve sürekli çalışan bir tünelin log
# dosyası sınırsız büyür. Bu, sessiz bir disk doluşudur: hiçbir hata vermez, yalnız bir gün
# disk biter ve o gün her şey aynı anda bozulur.
#
# Sistem düzeyinde bir `newsyslog` girdisi de olurdu ama sudo gerektirir; bu betik kullanıcı
# alanında kalır ve zaten kurulu olan LaunchAgent'lardan çağrılır.
#
#   ./ops/rotate-logs.sh          # boyutu aşan logları döndür
#
# Döndürme: dosya `.1` olarak saklanır, eskisi silinir, canlı dosya SIFIRLANIR (silinmez).
# Silinseydi cloudflared eski dosya tanıtıcısına yazmaya devam eder ve yeni log görünmezdi —
# klasik "log dosyası var ama boş" arızası.

set -uo pipefail

LOG_DIR="${CURRENCY_LOG_DIR:-$HOME/Library/Logs/currencyapi}"
MAX_BYTES="${CURRENCY_LOG_MAX_BYTES:-10485760}"   # 10 MB
KEEP="${CURRENCY_LOG_KEEP:-3}"

[[ -d "$LOG_DIR" ]] || exit 0

rotate() {
  local file="$1"
  [[ -f "$file" ]] || return 0

  local size
  size=$(stat -f%z "$file" 2>/dev/null || echo 0)
  (( size > MAX_BYTES )) || return 0

  # En eskiden başlayarak kaydır: .2 → .3, .1 → .2 ...
  local i
  for (( i = KEEP - 1; i >= 1; i-- )); do
    [[ -f "$file.$i" ]] && mv -f "$file.$i" "$file.$((i + 1))"
  done
  cp "$file" "$file.1"

  # Kesme (truncate) — silme DEĞİL: yazan süreç açık dosya tanıtıcısını kullanmaya devam
  # ettiği için silinen dosyaya yazar ve loglar görünmez olurdu.
  : > "$file"

  print -r -- "[$(date '+%Y-%m-%d %H:%M:%S')] dondu: $(basename "$file") ($size bayt)"

  # Sınırın ötesindeki kopyaları at.
  local extra
  for extra in "$file".*; do
    local suffix="${extra##*.}"
    [[ "$suffix" =~ '^[0-9]+$' ]] && (( suffix > KEEP )) && rm -f "$extra"
  done
}

for log in "$LOG_DIR"/*.log; do
  rotate "$log"
done
