#!/bin/bash
# ============================================================
# Despliegue seguro — NO borra volúmenes ni datos
# USO: bash scripts/deploy.sh
# ============================================================

set -e

COMPOSE_FILE="docker-compose.prod.yml"

echo "🚀 Iniciando despliegue seguro..."

# 1. Backup antes de desplegar
echo "📦 Paso 1: Creando backup..."
bash "$(dirname "$0")/backup-db.sh"

# 2. Pull de cambios (si hay repositorio)
echo "📥 Paso 2: Obteniendo cambios..."
if [ -d ".git" ]; then
    git pull origin main || git pull origin master
fi

# 3. Reconstruir contenedores
echo "🔨 Paso 3: Reconstruyendo contenedores..."
docker compose -f "$COMPOSE_FILE" build

# 4. Detener contenedores viejos (NO borra volúmenes)
echo "⏹️  Paso 4: Deteniendo contenedores anteriores..."
docker compose -f "$COMPOSE_FILE" stop

# 5. Levantar contenedores nuevos
echo "▶️  Paso 5: Iniciando nuevos contenedores..."
docker compose -f "$COMPOSE_FILE" up -d

# 6. Esperar a que PostgreSQL esté listo
echo "⏳ Paso 6: Esperando a que PostgreSQL esté listo..."
sleep 10

# 7. Verificar que los contenedores estén corriendo
echo "🔍 Paso 7: Verificando estado..."
docker compose -f "$COMPOSE_FILE" ps

echo ""
echo "✅ Despliegue completado exitosamente!"
echo ""
echo "📊 Para ver logs:"
echo "   docker logs wamvp_backend --tail 50"
echo "   docker logs wamvp_whatsapp --tail 30"
