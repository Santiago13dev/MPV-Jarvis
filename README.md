# WhatsApp MVP — Sistema de Automatización Inteligente

Sistema completo de automatización de WhatsApp para pequeños negocios con dashboard administrativo, motor híbrido de respuestas y fallback a IA.

---

## 🚀 Arranque rápido (local)

```bash
# 1. Clonar y configurar variables
cp .env.example .env
# Editar .env con tus valores

# 2. Levantar todo
docker compose up -d --build

# 3. Abrir dashboard
http://localhost:4200
# Login: valor de ADMIN_EMAIL / ADMIN_PASSWORD del .env
```

---

## 🏗️ Stack

| Capa | Tecnología |
|------|-----------|
| Frontend | Angular 17 |
| Backend | Spring Boot 3.2 (Java 17) |
| WhatsApp | Node.js + Baileys |
| Base de datos | PostgreSQL 15 |
| Proxy | Nginx |
| Contenedores | Docker + Compose |

---

## 📁 Estructura

```
whatsapp-MVP/
├── docs/                    # Arquitectura, BD, roadmap, riesgos
├── backend/                 # Spring Boot API
├── frontend/                # Angular Dashboard
├── whatsapp-service/        # Node.js + Baileys
├── infrastructure/          # Nginx, PostgreSQL, scripts
├── docker-compose.yml       # Dev local
├── docker-compose.prod.yml  # Producción
└── .env.example
```

---

## 🔄 Flujo de mensajes

```
WhatsApp → Node.js (Baileys)
         → POST /api/webhook/message (Spring Boot)
         → Rate Limit → Horario → Motor Híbrido
              ├── Keywords  → Respuesta directa
              ├── FAQs      → Respuesta FAQ
              └── IA        → OpenAI fallback
         → Enviar respuesta → WhatsApp
         → WebSocket → Dashboard
```

---

## 🖥️ Deploy en VPS

```bash
# 1. Setup inicial del servidor (una sola vez)
bash infrastructure/scripts/setup-vps.sh

# 2. Clonar repositorio
git clone <repo> /opt/whatsapp-mvp
cd /opt/whatsapp-mvp

# 3. Configurar variables
cp .env.example .env && nano .env

# 4. SSL con Let's Encrypt
certbot --nginx -d tu-dominio.com

# 5. Producción
docker compose -f docker-compose.prod.yml up -d --build

# 6. Deploy futuro
bash infrastructure/scripts/deploy.sh
```

---

## ⚙️ Variables de entorno clave

| Variable | Descripción |
|----------|-------------|
| `POSTGRES_PASSWORD` | Contraseña de la BD |
| `JWT_SECRET` | Secret para tokens JWT (mín. 64 chars) |
| `OPENAI_API_KEY` | API key de OpenAI |
| `WA_WEBHOOK_SECRET` | Secret compartido entre Node y Spring |
| `ADMIN_EMAIL` | Email del admin inicial |
| `ADMIN_PASSWORD` | Contraseña del admin inicial |

---

## 📊 URLs del dashboard

| Sección | Ruta |
|---------|------|
| Dashboard | `/dashboard` |
| WhatsApp / QR | `/whatsapp` |
| Conversaciones | `/conversations` |
| FAQs | `/faqs` |
| Configuración | `/config` |

---

## 🛠️ Comandos útiles

```bash
# Ver logs en tiempo real
docker compose logs -f backend
docker compose logs -f whatsapp-service

# Backup manual de BD
bash infrastructure/scripts/backup-db.sh

# Reiniciar solo el servicio WhatsApp
docker compose restart whatsapp-service

# Ver estado de contenedores
docker compose ps
```

---

## 📖 Documentación

- `docs/01-ARCHITECTURE.md` — Arquitectura general y decisiones técnicas
- `docs/02-DATABASE-DESIGN.md` — Modelo relacional completo
- `docs/03-ROADMAP.md` — Fases de desarrollo
- `docs/04-RISKS.md` — Riesgos técnicos y mitigaciones
- `docs/05-FOLDER-STRUCTURE.md` — Estructura de carpetas
