#!/usr/bin/env bash
# Muestra el QR del whatsapp-service como imagen (macOS).
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${TMPDIR:-/tmp}/wamvp_qr.png"

QR=$(curl -s http://localhost:3001/session/qr | python3 -c "import sys,json; print(json.load(sys.stdin).get('qr') or '')")
if [ -z "$QR" ]; then
  echo "⏳ El QR aún no está listo. Espera unos segundos y reintenta."
  exit 1
fi

echo "$QR" | sed 's#^data:image/png;base64,##' | base64 -d > "$OUT"
echo "✅ QR guardado en $OUT"
open "$OUT" 2>/dev/null || echo "Abre manualmente: $OUT"
