#!/usr/bin/env bash
# Detiene el entorno local de pruebas (NO borra volúmenes ni datos).
set -uo pipefail
cd "$(dirname "$0")/.."

echo "▶ Deteniendo simulador..."
lsof -ti:3001 | xargs kill -9 2>/dev/null || true

echo "▶ Deteniendo contenedores (sin borrar datos)..."
docker compose -f docker-compose.yml -f docker-compose.local.yml stop postgres backend frontend whatsapp-service 2>/dev/null || \
  docker compose -f docker-compose.yml -f docker-compose.local.yml stop postgres backend frontend

echo "✅ Detenido."
