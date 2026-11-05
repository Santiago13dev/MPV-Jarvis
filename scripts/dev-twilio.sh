#!/usr/bin/env bash
# Levanta el bot en modo Twilio (SIN QR): servicio Node + túnel público para el webhook.
#
# Requisitos (una sola vez):
#   1. Cuenta gratis en https://www.twilio.com
#   2. Console → Messaging → Try it out → Send a WhatsApp message (Sandbox)
#      - Copia Account SID y Auth Token
#      - Desde TU WhatsApp envía al +1 415 523 8886 el texto: join <tu-codigo>
#   3. Pon en .env:
#      WA_PROVIDER=twilio
#      TWILIO_ACCOUNT_SID=AC...
#      TWILIO_AUTH_TOKEN=...
#      TWILIO_WHATSAPP_FROM=whatsapp:+14155238886
#   4. En el Sandbox, campo "When a message comes in":
#      https://<URL que imprime este script>/webhook/twilio   (método POST)
#
# Uso: bash scripts/dev-twilio.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# Cargar .env en el entorno
set -a
# shellcheck disable=SC1091
source .env
set +a

if [ -z "${TWILIO_ACCOUNT_SID:-}" ] || [ -z "${TWILIO_AUTH_TOKEN:-}" ]; then
  echo "⚠️  Faltan TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN en .env"
  echo "   El servicio arranca igual, pero no podrá enviar respuestas."
fi

echo "▶ Liberando puerto 3001 (simulador/Baileys)..."
lsof -ti:3001 | xargs kill -9 2>/dev/null || true

echo "▶ Arrancando whatsapp-service en modo Twilio..."
(cd whatsapp-service && WA_PROVIDER=twilio PORT=3001 \
  BACKEND_URL="${BACKEND_URL_LOCAL:-http://localhost:8080}" \
  nohup node src/app.js > ../tmp_twilio_service.log 2>&1 &)

sleep 2
curl -s localhost:3001/health || { echo "❌ El servicio no respondió"; exit 1; }
echo

echo "▶ Abriendo túnel público (cloudflared)..."
nohup cloudflared tunnel --url http://localhost:3001 --no-autoupdate > tmp_tunnel.log 2>&1 &

echo "⏳ Esperando la URL pública..."
URL=""
for _ in $(seq 1 30); do
  URL=$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' tmp_tunnel.log | head -1 || true)
  [ -n "$URL" ] && break
  sleep 1
done

if [ -z "$URL" ]; then
  echo "❌ No se pudo obtener la URL del túnel. Revisa tmp_tunnel.log"
  exit 1
fi

echo
echo "✅ Webhook para Twilio Sandbox:"
echo "   $URL/webhook/twilio   (método POST)"
echo
echo "   Dashboard:            http://localhost:4200"
echo "   Logs servicio Twilio: tail -f tmp_twilio_service.log"
echo "   Logs túnel:           tail -f tmp_tunnel.log"
echo
echo "   Prueba rápida: envía 'hola' desde tu WhatsApp al +1 415 523 8886"
echo "   Para detener:  lsof -ti:3001 | xargs kill -9 ; pkill -f 'cloudflared tunnel --url'"
