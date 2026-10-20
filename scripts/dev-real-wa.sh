#!/usr/bin/env bash
# Modo REAL con QR: levanta el whatsapp-service de Baileys en local.
#
# ⚠️ IMPORTANTE:
#  - Escanea el QR con el teléfono del número BOT (WhatsApp Business → Dispositivos vinculados).
#  - Si ese número ya está vinculado en la VPS/producción, este login lo DESCONECTA (error 440).
#    Para pruebas seguras usa un número de WhatsApp distinto (spare) como "bot" local.
#
# Uso: bash scripts/dev-real-wa.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "▶ Deteniendo simulador (libero el puerto 3001)..."
lsof -ti:3001 | xargs kill -9 2>/dev/null || true

echo "▶ Levantando PostgreSQL + Backend + WhatsApp real..."
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d postgres backend whatsapp-service

echo
echo "⏳ Esperando a que aparezca el QR (logs)... escanéalo con el teléfono del BOT."
echo "   También puedes verlo como imagen con: bash scripts/show-qr.sh"
echo "   (Ctrl+C para salir de los logs; los servicios siguen corriendo)"
echo
sleep 3
docker logs -f wamvp_whatsapp
