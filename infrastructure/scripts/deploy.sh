#!/bin/bash
# ============================================================
# deploy.sh — Script de despliegue en VPS
# Uso: bash deploy.sh
# ============================================================
set -e

echo "🚀 Iniciando deploy de WhatsApp MVP..."

# Verificar que existe el .env
if [ ! -f .env ]; then
  echo "❌ Error: No existe .env. Copia .env.example y configura los valores."
  exit 1
fi

# Pull del código
echo "📥 Actualizando código..."
git pull origin main

# Build y restart de contenedores
echo "🐳 Reconstruyendo contenedores..."
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --build --remove-orphans

# Limpiar imágenes viejas
echo "🧹 Limpiando imágenes antiguas..."
docker image prune -f

# Health check
echo "⏳ Esperando que el backend levante..."
sleep 15
STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health || echo "000")
if [ "$STATUS" = "200" ]; then
  echo "✅ Deploy exitoso. Backend respondiendo."
else
  echo "⚠️  Backend no responde aún (status: $STATUS). Revisa los logs:"
  echo "   docker compose logs backend --tail=50"
fi

echo "🎉 Deploy completado."
