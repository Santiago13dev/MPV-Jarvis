#!/bin/bash
# ============================================================
# setup-vps.sh — Setup inicial del VPS Ubuntu 22.04/24.04
# Ejecutar UNA SOLA VEZ como root en el VPS
# Uso: bash setup-vps.sh
# ============================================================
set -e

echo "🖥️  Configurando VPS para WhatsApp MVP..."

# Actualizar sistema
apt-get update -qq && apt-get upgrade -y -qq

# Instalar Docker
if ! command -v docker &>/dev/null; then
  echo "🐳 Instalando Docker..."
  curl -fsSL https://get.docker.com | bash
  systemctl enable docker
  systemctl start docker
fi

# Instalar Docker Compose plugin
if ! docker compose version &>/dev/null; then
  echo "📦 Instalando Docker Compose..."
  apt-get install -y docker-compose-plugin
fi

# Instalar herramientas
apt-get install -y git curl nginx certbot python3-certbot-nginx ufw

# Firewall
echo "🔒 Configurando firewall..."
ufw allow ssh
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# Crear usuario de la app (no-root)
if ! id "wamvp" &>/dev/null; then
  useradd -m -s /bin/bash wamvp
  usermod -aG docker wamvp
  echo "✅ Usuario wamvp creado"
fi

# Directorios
mkdir -p /opt/whatsapp-mvp
mkdir -p /opt/backups/whatsapp-mvp
chown -R wamvp:wamvp /opt/whatsapp-mvp /opt/backups

# Swap de 2GB (importante para VPS con poca RAM)
if [ ! -f /swapfile ]; then
  echo "💾 Creando swap de 2GB..."
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Crontab para backup automático diario a las 2am
CRON_JOB="0 2 * * * /opt/whatsapp-mvp/infrastructure/scripts/backup-db.sh >> /var/log/wa-backup.log 2>&1"
(crontab -l 2>/dev/null | grep -qF "backup-db.sh") || (crontab -l 2>/dev/null; echo "$CRON_JOB") | crontab -

echo ""
echo "✅ VPS configurado correctamente."
echo ""
echo "📋 Próximos pasos:"
echo "  1. Clona el repositorio en /opt/whatsapp-mvp"
echo "  2. Copia .env.example → .env y edita los valores"
echo "  3. Configura el dominio en infrastructure/nginx/conf.d/whatsapp-mvp.conf"
echo "  4. Obtén SSL: certbot --nginx -d tu-dominio.com"
echo "  5. Ejecuta: docker compose -f docker-compose.prod.yml up -d --build"
