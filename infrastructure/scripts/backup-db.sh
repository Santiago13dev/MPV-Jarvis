#!/bin/bash
# ============================================================
# backup-db.sh — Backup automático de PostgreSQL
# Agregar a crontab: 0 2 * * * /path/to/backup-db.sh
# ============================================================
set -e

source "$(dirname "$0")/../../.env" 2>/dev/null || true

BACKUP_DIR="${BACKUP_DIR:-/opt/backups/whatsapp-mvp}"
DB="${POSTGRES_DB:-whatsapp_mvp}"
USER="${POSTGRES_USER:-wamvp_user}"
DATE=$(date +%Y%m%d_%H%M%S)
FILE="$BACKUP_DIR/db_backup_$DATE.sql.gz"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"

mkdir -p "$BACKUP_DIR"

echo "📦 Iniciando backup de $DB..."
docker exec wamvp_postgres pg_dump -U "$USER" "$DB" | gzip > "$FILE"

echo "✅ Backup guardado en: $FILE ($(du -sh "$FILE" | cut -f1))"

# Eliminar backups antiguos
echo "🧹 Eliminando backups con más de $RETENTION_DAYS días..."
find "$BACKUP_DIR" -name "db_backup_*.sql.gz" -mtime +$RETENTION_DAYS -delete

echo "📋 Backups disponibles:"
ls -lh "$BACKUP_DIR"
