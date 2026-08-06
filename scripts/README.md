# Scripts de Despliegue — WhatsApp MVP

## 📋 Archivos

| Archivo | Descripción |
|---------|-------------|
| `backup-db.sh` | Crea backup de PostgreSQL |
| `deploy.sh` | Despliegue seguro (backup + rebuild) |
| `restore-db.sh` | Restaura base de datos desde backup |

---

## 🚀 Despliegue en VPS (Producción)

### Primera vez

```bash
# 1. Conectar al VPS
ssh root@tu-vps

# 2. Clonar repositorio
git clone https://github.com/tu-usuario/whatsapp-mvp.git
cd whatsapp-mvp

# 3. Configurar variables de entorno
cp .env.example .env
nano .env  # Editar con valores reales

# 4. Dar permisos a scripts
chmod +x scripts/*.sh

# 5. Desplegar
bash scripts/deploy.sh
```

### Despliegos posteriores

```bash
# Opción 1: Script automático (recomendado)
bash scripts/deploy.sh

# Opción 2: Manual
bash scripts/backup-db.sh
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d
```

---

## 💾 Backup y Restauración

### Crear backup manual

```bash
bash scripts/backup-db.sh
```

Los backups se guardan en `/root/backups/whatsapp-mvp/` y se comprimen automáticamente.

### Restaurar desde backup

```bash
bash scripts/restore-db.sh
```

El script muestra los backups disponibles y pide confirmación antes de restaurar.

---

## 🔧 Comandos Útiles

```bash
# Ver estado de contenedores
docker compose -f docker-compose.prod.yml ps

# Ver logs del backend
docker logs wamvp_backend --tail 50

# Ver logs de WhatsApp
docker logs wamvp_whatsapp --tail 30

# Ver logs de PostgreSQL
docker logs wamvp_postgres --tail 30

# Reiniciar solo el backend
docker restart wamvp_backend

# Reiniciar todo
docker compose -f docker-compose.prod.yml restart
```

---

## ⚠️ Importante

1. **NUNCA ejecutar `docker compose down -v`** en producción (borra volúmenes)
2. **SIEMPRE hacer backup** antes de desplegar
3. Los volúmenes de Docker persisten los datos entre reinicios
4. Las migraciones de Flyway se ejecutan automáticamente al iniciar el backend
5. Si algo sale mal, restaurar desde backup con `restore-db.sh`
