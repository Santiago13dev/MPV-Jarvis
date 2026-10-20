#!/usr/bin/env bash
# Levanta el entorno LOCAL de pruebas rápidas de reservas:
#  - PostgreSQL + Backend + Dashboard Angular (Docker)
#  - Simulador de WhatsApp con interfaz web (sin QR)
#
# Uso: bash scripts/dev-up.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "▶ Levantando PostgreSQL + Backend + Dashboard..."
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d postgres backend frontend

echo "▶ Reiniciando simulador en :3001..."
lsof -ti:3001 | xargs kill -9 2>/dev/null || true
sleep 1
nohup node scripts/fake-wa-service.js > tmp_simulator.log 2>&1 &
sleep 1

echo
echo "✅ Listo. Abre el simulador:  http://localhost:3001"
echo "   Dashboard (reservas):     http://localhost:4200  (admin@example.com / admin123)"
echo "   Backend directo:          http://localhost:8080"
echo "   Logs backend:             docker logs -f wamvp_backend"
echo "   Logs simulador:           tail -f tmp_simulator.log"
echo
echo "   Para detener: bash scripts/dev-stop.sh"
