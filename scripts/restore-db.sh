#!/bin/bash
# ============================================================
# Restaurar base de datos desde backup
# ============================================================

set -e

BACKUP_DIR="/root/backups/whatsapp-mvp"
CONTAINER_NAME="wamvp_postgres"

# Listar backups disponibles
echo "📋 Backups disponibles:"
echo ""
ls -1t "$BACKUP_DIR"/backup_*.sql.gz 2>/dev/null | head -10
echo ""

# Pedir al usuario que seleccione un backup
read -p "📝 Nombre del archivo de backup (sin ruta): " BACKUP_NAME
BACKUP_FILE="${BACKUP_DIR}/${BACKUP_NAME}"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "❌ Error: No se encontró el archivo $BACKUP_FILE"
    exit 1
fi

echo "⚠️  ADVERTENCIA: Esto REEMPLAZARÁ la base de datos actual."
read -p "¿Continuar? (s/n): " CONFIRM

if [ "$CONFIRM" != "s" ]; then
    echo "❌ Operación cancelada"
    exit 0
fi

echo "🔄 Restaurando base de datos..."

# Descomprimir y restaurar
gunzip -c "$BACKUP_FILE" | docker exec -i "$CONTAINER_NAME" psql -U wamvp_user -d whatsapp_mvp

echo "✅ Base de datos restaurada exitosamente!"
echo "🔄 Reiniciando backend para aplicar cambios..."
docker restart wamvp_backend

echo "✅ Listo!"
