#!/bin/bash
# ============================================================
# Backup de PostgreSQL — Ejecutar ANTES de cada despliegue
# ============================================================

set -e

BACKUP_DIR="/root/backups/whatsapp-mvp"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/backup_${TIMESTAMP}.sql"
CONTAINER_NAME="wamvp_postgres"
MAX_BACKUPS=10

echo "📦 Iniciando backup de base de datos..."

# Crear directorio de backups si no existe
mkdir -p "$BACKUP_DIR"

# Verificar que el contenedor esté corriendo
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "❌ Error: El contenedor ${CONTAINER_NAME} no está corriendo"
    exit 1
fi

# Ejecutar backup
docker exec "$CONTAINER_NAME" pg_dump -U wamvp_user whatsapp_mvp > "$BACKUP_FILE"

# Verificar que el backup no esté vacío
if [ ! -s "$BACKUP_FILE" ]; then
    echo "❌ Error: El backup está vacío"
    rm -f "$BACKUP_FILE"
    exit 1
fi

# Comprimir backup
gzip "$BACKUP_FILE"
echo "✅ Backup creado: ${BACKUP_FILE}.gz"

# Limpiar backups antiguos (mantener solo los últimos MAX_BACKUPS)
cd "$BACKUP_DIR"
ls -t backup_*.sql.gz 2>/dev/null | tail -n +$((MAX_BACKUPS + 1)) | xargs -r rm -f

echo "🧹 Limpieza completada. Backups mantenidos: $MAX_BACKUPS"
echo "📁 Ubicación: $BACKUP_DIR"
