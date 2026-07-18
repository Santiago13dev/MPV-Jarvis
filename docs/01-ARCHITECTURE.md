# 🏗️ Arquitectura General — WhatsApp MVP Automation System

## 📌 Visión del Sistema

Sistema de automatización inteligente de WhatsApp para pequeños negocios.
Diseñado para reducir la carga operativa del dueño respondiendo chats repetitivos
con un motor híbrido de reglas + IA, expuesto mediante un dashboard web administrativo.

---

## 🧱 Componentes del Sistema

```
┌─────────────────────────────────────────────────────────────────────┐
│                         VPS Ubuntu (Docker)                         │
│                                                                     │
│  ┌──────────────┐    ┌──────────────────┐    ┌───────────────────┐ │
│  │   Angular    │    │   Spring Boot    │    │  WhatsApp Service │ │
│  │  Dashboard   │◄──►│   REST API       │◄──►│   (Node.js +      │ │
│  │  (Nginx)     │    │   + WebSockets   │    │    OpenWA)        │ │
│  └──────────────┘    └────────┬─────────┘    └───────────────────┘ │
│                               │                                     │
│                    ┌──────────▼──────────┐                          │
│                    │     PostgreSQL       │                          │
│                    │   (Datos + Config)   │                          │
│                    └─────────────────────┘                          │
│                                                                     │
│  ┌──────────────┐    ┌──────────────────┐                           │
│  │    Nginx     │    │   OpenAI API     │                           │
│  │ Reverse Proxy│    │   (IA Fallback)  │                           │
│  │  + SSL/TLS   │    │   (Externo)      │                           │
│  └──────────────┘    └──────────────────┘                           │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🔄 Flujo de Mensajes (Message Processing Pipeline)

```
WhatsApp User
     │
     ▼
[OpenWA Service] ──── Recibe mensaje entrante
     │
     ▼
[HTTP POST → Spring Boot /api/webhook/message]
     │
     ▼
[MessageProcessingService]
     │
     ├─── 1. RATE LIMIT CHECK ──► ¿Spam? → Ignorar / Bloquear
     │
     ├─── 2. HORARIO LABORAL ──► ¿Fuera de horario? → Mensaje off-hours
     │
     ├─── 3. ESTADO CONVERSACIÓN
     │         ├── HUMAN_TAKEOVER → No automatizar, notificar dueño
     │         └── AUTO → Continuar pipeline
     │
     ├─── 4. MOTOR HÍBRIDO (en orden de prioridad)
     │         │
     │         ├─── A. KEYWORD RULES ──► ¿Coincide keyword? → Respuesta directa
     │         │
     │         ├─── B. FAQ ENGINE ──► ¿Coincide pregunta frecuente? → Respuesta FAQ
     │         │
     │         └─── C. AI FALLBACK ──► OpenAI con contexto del negocio
     │
     ▼
[ResponseBuilderService] → Construye respuesta (texto/media/botones)
     │
     ▼
[OpenWA Service] → Envía respuesta a WhatsApp
     │
     ▼
[ConversationService] → Persiste en PostgreSQL + WebSocket event al dashboard
```

---

## 🧩 Microservicios / Módulos

### 1. `whatsapp-service` (Node.js + OpenWA)
- Responsabilidad ÚNICA: gestionar la sesión de WhatsApp
- Expone endpoints REST internos para enviar mensajes
- Recibe webhook del Spring Boot para enviar
- Emite eventos al Spring Boot cuando llegan mensajes
- Gestiona el QR code y la reconexión automática
- Puerto interno: 3001

### 2. `backend` (Spring Boot — Java 17+)
- Core business logic
- REST API para el dashboard
- WebSockets para tiempo real
- Motor híbrido de procesamiento de mensajes
- Integración con OpenAI
- Puerto interno: 8080

### 3. `frontend` (Angular 17+)
- Dashboard SPA
- Servido por Nginx como archivos estáticos
- Puerto público: 443 (HTTPS vía Nginx)

### 4. `postgresql`
- Base de datos principal
- Puerto interno: 5432

### 5. `nginx`
- Reverse proxy
- SSL termination
- Sirve el frontend
- Puerto público: 80/443

---

## 🔐 Seguridad

- JWT tokens con refresh token
- HTTPS obligatorio (Let's Encrypt)
- Variables de entorno para secrets (nunca en código)
- Rate limiting en API
- CORS configurado
- Logs de auditoría
- Red Docker interna (los servicios no exponen puertos directamente)

---

## 📡 Comunicación entre Servicios

| Origen          | Destino          | Protocolo       | Propósito                    |
|-----------------|------------------|-----------------|------------------------------|
| OpenWA Service  | Spring Boot      | HTTP POST       | Webhook mensaje entrante     |
| Spring Boot     | OpenWA Service   | HTTP POST       | Enviar mensaje de salida     |
| Spring Boot     | OpenAI API       | HTTPS           | IA fallback                  |
| Angular         | Spring Boot      | REST + WS       | Dashboard admin              |
| Nginx           | Angular          | Static files    | Servir SPA                   |
| Nginx           | Spring Boot      | Reverse proxy   | /api/*                       |
| Nginx           | OpenWA Service   | Reverse proxy   | /whatsapp/* (QR admin)       |

---

## ⚡ Decisiones Técnicas Clave

### ¿Por qué OpenWA en Node.js separado del Spring Boot?
Spring Boot es Java y OpenWA es una librería de Node.js. Mantenerlos separados
sigue el principio de Single Responsibility y permite reemplazar el cliente WhatsApp
(por @whiskeysockets/baileys, por ejemplo) sin tocar el backend.

### ¿Por qué motor híbrido y no solo IA?
IA cuesta dinero por token. Con 20 chats/día y ~10 mensajes por chat = 200 mensajes/día.
Si el 70% son FAQs resueltas por reglas, solo el 30% pasa a IA → ahorro del 70% en costos.

### ¿Por qué PostgreSQL y no MongoDB?
Los datos son relacionales: conversaciones → mensajes → usuarios → configuraciones.
PostgreSQL tiene JSONB para datos flexibles (configuraciones del bot) sin sacrificar
integridad relacional.

### ¿Por qué WebSockets?
El dashboard debe mostrar mensajes en tiempo real sin polling. Spring Boot tiene
soporte nativo de WebSockets con STOMP.
