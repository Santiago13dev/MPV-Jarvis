# 🚀 Guía Completa de Inicio — WhatsApp MVP

Esta guía te lleva paso a paso desde cero hasta tener el sistema corriendo en tu computador (modo desarrollo) y, opcionalmente, en un VPS de producción.

---

## 📋 Tabla de contenido

1. [Requisitos previos](#1-requisitos-previos)
2. [Verificar la estructura del proyecto](#2-verificar-la-estructura-del-proyecto)
3. [Configurar variables de entorno](#3-configurar-variables-de-entorno)
4. [Levantar el proyecto en local con Docker](#4-levantar-el-proyecto-en-local-con-docker)
5. [Verificar que todo funciona](#5-verificar-que-todo-funciona)
6. [Conectar WhatsApp (escanear QR)](#6-conectar-whatsapp-escanear-qr)
7. [Probar el bot](#7-probar-el-bot)
8. [Configurar el negocio desde el dashboard](#8-configurar-el-negocio-desde-el-dashboard)
9. [Solución de problemas comunes](#9-solución-de-problemas-comunes)
10. [Desplegar en producción (VPS)](#10-desplegar-en-producción-vps)
11. [Comandos útiles del día a día](#11-comandos-útiles-del-día-a-día)

---

## 1. Requisitos previos

Antes de empezar, instala en tu computador:

| Herramienta | Versión mínima | Verificar instalación |
|---|---|---|
| Docker Desktop | 24+ | `docker --version` |
| Docker Compose | v2 (viene con Docker Desktop) | `docker compose version` |
| Git | cualquiera | `git --version` |
| Un editor de código | VS Code recomendado | — |

**Windows**: instala [Docker Desktop](https://www.docker.com/products/docker-desktop/) y asegúrate de que WSL2 esté habilitado (Docker Desktop te lo pide automáticamente en la instalación).

> ⚠️ No necesitas instalar Java, Node.js, Angular CLI ni PostgreSQL en tu máquina — todo corre dentro de contenedores Docker. Solo necesitas Docker.

Verifica que Docker esté corriendo abriendo Docker Desktop y comprobando que el ícono esté en verde, o ejecutando:

```bash
docker info
```

Si ves información del sistema (no un error de conexión), estás listo.

---

## 2. Verificar la estructura del proyecto

Abre una terminal (PowerShell, CMD o Git Bash) y navega a la carpeta del proyecto:

```bash
cd "C:\Users\kevin.rodriguez\Desktop\santiago\devjob\whatsapp-MVP"
```

Verifica que existan estas carpetas y archivos clave:

```bash
dir
```

Deberías ver:

```
docs/
backend/
frontend/
whatsapp-service/
infrastructure/
docker-compose.yml
docker-compose.prod.yml
.env.example
.gitignore
README.md
```

Si falta alguno de estos, revisa la conversación anterior donde se generaron — puede que algún archivo no se haya escrito por una desconexión del MCP filesystem.

---

## 3. Configurar variables de entorno

Este es el paso **más importante**. El archivo `.env` contiene contraseñas, secretos y la API key de OpenAI.

### 3.1. Copiar el archivo de ejemplo

```bash
copy .env.example .env
```

(En Mac/Linux sería `cp .env.example .env`)

### 3.2. Editar el archivo `.env`

Abre `.env` con tu editor y reemplaza estos valores:

```env
# --- PostgreSQL ---
POSTGRES_DB=whatsapp_mvp
POSTGRES_USER=wamvp_user
POSTGRES_PASSWORD=CAMBIA_ESTO_por_una_contraseña_segura

# --- Spring Boot / JWT ---
JWT_SECRET=CAMBIA_ESTO_por_una_cadena_aleatoria_de_al_menos_64_caracteres

# --- OpenAI ---
OPENAI_API_KEY=sk-tu-api-key-real-de-openai
OPENAI_MODEL=gpt-4o-mini

# --- WhatsApp Service ---
WA_WEBHOOK_SECRET=CAMBIA_ESTO_por_otro_secreto_aleatorio

# --- Admin inicial del dashboard ---
ADMIN_EMAIL=admin@tunegocio.com
ADMIN_PASSWORD=CAMBIA_ESTO_por_una_contraseña_segura
```

**¿Cómo generar una cadena aleatoria segura para `JWT_SECRET` y `WA_WEBHOOK_SECRET`?**

En PowerShell:
```powershell
-join ((48..57) + (65..90) + (97..122) | Get-Random -Count 64 | % {[char]$_})
```

O simplemente usa cualquier frase larga sin espacios, por ejemplo:
```
JWT_SECRET=miNegocioWhatsappMVP2026SecretoSuperLargoYAleatorioParaJWT123456789
```

**¿Dónde consigo la API key de OpenAI?**

1. Ve a https://platform.openai.com/api-keys
2. Inicia sesión o crea una cuenta
3. Crea una nueva API key
4. Cópiala y pégala en `OPENAI_API_KEY` (empieza con `sk-`)

> 💡 Si todavía no tienes la API key de OpenAI, puedes dejar `OPENAI_API_KEY=` vacío por ahora. El sistema funcionará igual con FAQs y keywords; solo el fallback de IA mostrará un mensaje genérico hasta que la configures.

### 3.3. Guardar el archivo

Guarda los cambios. **Nunca subas este archivo `.env` a Git** — ya está protegido en `.gitignore`.

---

## 4. Levantar el proyecto en local con Docker

Desde la raíz del proyecto (`whatsapp-MVP/`), ejecuta:

```bash
docker compose up -d --build
```

Esto va a:
1. Construir la imagen del backend (Spring Boot) — tarda 2-4 minutos la primera vez
2. Construir la imagen del frontend (Angular) — tarda 1-3 minutos
3. Construir la imagen del whatsapp-service (Node.js) — tarda 1-2 minutos
4. Descargar la imagen de PostgreSQL y Nginx
5. Levantar todos los contenedores conectados en red

**La primera vez puede tardar entre 5 y 10 minutos.** Las siguientes veces será mucho más rápido porque Docker cachea las capas.

### 4.1. Ver el progreso

Mientras se construye, verás logs en la terminal. Si quieres verlos en una terminal separada (recomendado), abre otra ventana de terminal y ejecuta:

```bash
docker compose logs -f
```

Presiona `Ctrl+C` para dejar de ver los logs (esto no detiene los contenedores).

---

## 5. Verificar que todo funciona

### 5.1. Revisar el estado de los contenedores

```bash
docker compose ps
```

Deberías ver 5 contenedores con estado `Up` o `running`:

```
NAME                  STATUS
wamvp_postgres        Up (healthy)
wamvp_whatsapp        Up
wamvp_backend         Up (healthy)
wamvp_frontend        Up
wamvp_nginx           Up
```

Si alguno dice `Restarting` o `Exited`, hay un problema — ve a la sección [9. Solución de problemas](#9-solución-de-problemas-comunes).

### 5.2. Verificar el backend

```bash
curl http://localhost:8080/actuator/health
```

Debe responder algo como:
```json
{"status":"UP"}
```

### 5.3. Abrir el dashboard

Abre tu navegador en:

```
http://localhost:4200
```

Deberías ver la pantalla de **Login** del dashboard con el ícono 💬.

### 5.4. Iniciar sesión

Usa las credenciales que configuraste en el `.env`:
- **Email**: el valor de `ADMIN_EMAIL`
- **Contraseña**: el valor de `ADMIN_PASSWORD`

Si el login es exitoso, entrarás al Dashboard principal.

---

## 6. Conectar WhatsApp (escanear QR)

1. En el dashboard, ve al menú lateral → **📱 WhatsApp**
2. Espera unos segundos a que aparezca el código QR (el estado pasará de "Conectando..." a "Escanear QR")
3. En tu teléfono, abre **WhatsApp** (recomendado usar un número de WhatsApp Business dedicado, no tu número personal)
4. Ve a **Configuración** (⋮ en Android, Ajustes en iOS) → **Dispositivos vinculados**
5. Toca **Vincular un dispositivo**
6. Escanea el código QR que aparece en el dashboard
7. Espera unos segundos — el estado debería cambiar a **"Conectado ✓"** automáticamente

> ⚠️ **Importante**: el teléfono debe mantenerse con batería y conexión a internet. La sesión persiste aunque reinicies el servidor, pero si cierras sesión desde el teléfono tendrás que escanear el QR de nuevo.

---

## 7. Probar el bot

Con WhatsApp conectado, desde **otro número de teléfono** (o pídele a alguien) envía un mensaje al número que conectaste. Por ejemplo:

```
Hola, ¿cuál es el horario de atención?
```

El sistema ya viene con 3 FAQs de ejemplo precargadas (horario, pedidos, métodos de pago), así que deberías recibir una respuesta automática en segundos.

Ve al dashboard → **💬 Conversaciones** y deberías ver la conversación apareciendo en tiempo real, con la etiqueta de qué motor respondió (FAQ, Keyword, IA, etc).

---

## 8. Configurar el negocio desde el dashboard

Ahora personaliza el bot para tu negocio real:

### 8.1. Información general
Ve a **⚙️ Configuración** y completa:
- Nombre del negocio
- Tipo de negocio
- Mensaje de bienvenida
- Mensaje fuera de horario
- Activar/desactivar IA

### 8.2. Horarios laborales
En la misma página, en la sección de horarios, activa/desactiva días y ajusta las horas de apertura y cierre de tu negocio.

### 8.3. Preguntas frecuentes (FAQs)
Ve a **❓ FAQs** y crea las preguntas que tus clientes hacen más seguido, con sus respuestas y palabras clave (keywords) que las disparan. Mientras más FAQs tengas bien configuradas, menos dependerás de la IA (lo que reduce costos).

---

## 9. Solución de problemas comunes

### El contenedor `backend` se reinicia constantemente

```bash
docker compose logs backend --tail=50
```

Causas comunes:
- `JWT_SECRET` muy corto (debe tener al menos 32-64 caracteres)
- PostgreSQL no está listo aún — espera unos segundos y reinicia: `docker compose restart backend`
- Error de conexión a la base de datos — revisa que `POSTGRES_PASSWORD` en `.env` coincida en todos los servicios

### El QR no aparece nunca

```bash
docker compose logs whatsapp-service --tail=50
```

- Verifica que el contenedor `wamvp_whatsapp` esté corriendo: `docker compose ps`
- Reinicia el servicio: `docker compose restart whatsapp-service`
- Si sigue sin aparecer, borra la sesión y vuelve a intentar:
  ```bash
  docker compose down
  docker volume rm wamvp_wa_sessions
  docker compose up -d
  ```

### El frontend no carga (pantalla en blanco)

```bash
docker compose logs frontend --tail=50
```

- Revisa que el build de Angular haya terminado sin errores
- Reconstruye solo el frontend: `docker compose up -d --build frontend`

### "Invalid credentials" al hacer login

- Verifica que `ADMIN_EMAIL` y `ADMIN_PASSWORD` en tu `.env` sean correctos
- El usuario admin solo se crea **la primera vez** que arranca el backend. Si cambiaste el `.env` después del primer arranque, el usuario viejo sigue en la base de datos. Para resetear todo:
  ```bash
  docker compose down -v   # ⚠️ Esto borra TODOS los datos
  docker compose up -d --build
  ```

### Puerto ya en uso (`port is already allocated`)

Significa que algo más en tu PC está usando el puerto 4200, 8080, 5432 o 3001. Cierra esa aplicación o cambia el puerto en `docker-compose.yml` (ej: `"4201:80"` en vez de `"4200:80"`).

### Ver logs de un servicio específico en vivo

```bash
docker compose logs -f backend
docker compose logs -f whatsapp-service
docker compose logs -f frontend
```

---

## 10. Desplegar en producción (VPS)

Cuando estés listo para producción con un dominio real y HTTPS:

### 10.1. Contratar un VPS
Recomendado: 2GB RAM, 2 vCPU. Proveedores: DigitalOcean, Hetzner, Vultr. Sistema operativo: Ubuntu 22.04 o 24.04.

### 10.2. Conectarte por SSH
```bash
ssh root@IP_DE_TU_VPS
```

### 10.3. Ejecutar el setup inicial
Sube el proyecto al VPS (vía `git clone` o `scp`), luego:

```bash
cd /opt/whatsapp-mvp
bash infrastructure/scripts/setup-vps.sh
```

Esto instala Docker, configura el firewall, crea swap y prepara backups automáticos.

### 10.4. Configurar el dominio
Edita `infrastructure/nginx/conf.d/whatsapp-mvp.conf` y reemplaza `YOUR_DOMAIN.COM` por tu dominio real (debe apuntar al IP del VPS vía DNS antes de este paso).

### 10.5. Configurar `.env` de producción
```bash
cp .env.example .env
nano .env   # Completa todos los valores reales
```

### 10.6. Obtener certificado SSL
```bash
certbot --nginx -d tu-dominio.com -d www.tu-dominio.com
```

### 10.7. Levantar en producción
```bash
docker compose -f docker-compose.prod.yml up -d --build
```

### 10.8. Verificar
Abre `https://tu-dominio.com` en el navegador. Deberías ver el login del dashboard con candado verde (HTTPS).

### 10.9. Deploys futuros
Cada vez que actualices el código:
```bash
bash infrastructure/scripts/deploy.sh
```

---

## 11. Comandos útiles del día a día

```bash
# Ver estado de todos los contenedores
docker compose ps

# Ver logs en tiempo real de todo
docker compose logs -f

# Ver logs de un servicio puntual
docker compose logs -f backend

# Reiniciar un servicio
docker compose restart whatsapp-service

# Detener todo (sin borrar datos)
docker compose down

# Detener todo y BORRAR datos (¡cuidado!)
docker compose down -v

# Reconstruir después de cambios en el código
docker compose up -d --build

# Backup manual de la base de datos
bash infrastructure/scripts/backup-db.sh

# Entrar a la base de datos directamente
docker exec -it wamvp_postgres psql -U wamvp_user -d whatsapp_mvp
```

---

## ✅ Checklist final

- [ ] Docker Desktop instalado y corriendo
- [ ] `.env` configurado con contraseñas y API key reales
- [ ] `docker compose up -d --build` ejecutado sin errores
- [ ] Los 5 contenedores en estado `Up`
- [ ] Dashboard accesible en `http://localhost:4200`
- [ ] Login exitoso con credenciales del `.env`
- [ ] QR escaneado y WhatsApp en estado "Conectado"
- [ ] Mensaje de prueba respondido automáticamente
- [ ] FAQs y configuración del negocio personalizadas

¡Listo! Tu sistema de automatización de WhatsApp está funcionando. 🎉
