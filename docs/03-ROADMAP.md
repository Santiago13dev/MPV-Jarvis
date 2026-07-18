# 🗺️ Roadmap de Desarrollo — Fases

## Estimación total: ~10 semanas para MVP funcional en producción

---

## FASE 0 — Setup e Infraestructura Base (Semana 1)
**Objetivo**: Todo el entorno corriendo localmente con Docker.

### Tareas:
- [ ] Crear `docker-compose.yml` con todos los servicios
- [ ] Configurar PostgreSQL + ejecutar schema inicial
- [ ] Configurar Nginx con configuración base
- [ ] Spring Boot project con dependencias base (Spring Web, Security, WebSocket, JPA, Flyway)
- [ ] Angular project creado con routing, módulos base
- [ ] Node.js + OpenWA project base con conexión QR
- [ ] Variables de entorno documentadas (`.env.example`)
- [ ] README de arranque local

**Entregable**: `docker-compose up` levanta todo el stack localmente.

---

## FASE 1 — Autenticación y Dashboard Shell (Semana 2)
**Objetivo**: Login funcional y dashboard vacío con navegación.

### Backend:
- [ ] Entidades JPA: User, Role
- [ ] JWT Authentication (login + refresh token)
- [ ] UserController + AuthController
- [ ] Global Exception Handler
- [ ] CORS configuration

### Frontend:
- [ ] Login page con JWT
- [ ] Auth guard
- [ ] Dashboard layout (sidebar, topbar)
- [ ] Routing principal
- [ ] HTTP interceptor (JWT en headers)

**Entregable**: Login funcional, dashboard con sidebar navegable.

---

## FASE 2 — Integración WhatsApp + Estado de Sesión (Semana 3)
**Objetivo**: Ver el QR en el dashboard y conectar WhatsApp.

### WhatsApp Service (Node.js):
- [ ] Inicialización de OpenWA
- [ ] Endpoint GET /session/qr → retorna QR en base64
- [ ] Endpoint GET /session/status → estado de conexión
- [ ] Endpoint POST /session/disconnect
- [ ] Webhook outbound: cuando llega mensaje → POST a Spring Boot
- [ ] Endpoint POST /messages/send → Spring Boot llama esto para enviar

### Backend:
- [ ] WhatsappSessionController (proxy hacia Node service)
- [ ] Webhook endpoint POST /api/webhook/message
- [ ] WhatsappSession entity + repository
- [ ] Actualizar estado en DB al recibir eventos del Node service
- [ ] WebSocket: emitir estado de conexión al dashboard

### Frontend:
- [ ] Página "Conexión WhatsApp"
- [ ] Mostrar QR code (imagen desde base64)
- [ ] Indicador de estado (Desconectado / Conectando / Conectado)
- [ ] Botón reconectar / desconectar
- [ ] WebSocket listener para updates en tiempo real

**Entregable**: Escanear QR y ver WhatsApp conectado en el dashboard.

---

## FASE 3 — Motor de Mensajes + FAQs + Keywords (Semana 4-5)
**Objetivo**: El bot responde automáticamente con reglas simples.

### Backend:
- [ ] MessageProcessingService (pipeline principal)
- [ ] RateLimitService (anti-spam)
- [ ] BusinessHoursService (verificar horario)
- [ ] ConversationService (crear/actualizar conversaciones)
- [ ] KeywordMatchingService
- [ ] FaqMatchingService (similitud por keywords)
- [ ] ResponseBuilderService
- [ ] Entidades: Contact, Conversation, Message, FaqItem, KeywordRule
- [ ] CRUD APIs: /api/faq, /api/keyword-rules, /api/auto-reply-rules

### Frontend:
- [ ] Gestión de FAQs (CRUD visual con tabla + modal)
- [ ] Gestión de Keyword Rules
- [ ] Vista de Conversaciones (inbox)
- [ ] Vista detalle de conversación con historial de mensajes

**Entregable**: Bot responde preguntas frecuentes sin IA.

---

## FASE 4 — Integración IA (Semana 6)
**Objetivo**: Mensajes complejos procesados por OpenAI.

### Backend:
- [ ] OpenAIService (cliente HTTP hacia OpenAI)
- [ ] Construcción dinámica del system prompt desde DB
- [ ] Incluir contexto de últimos N mensajes de la conversación
- [ ] Conteo de tokens y registro en DB
- [ ] Failover: si IA falla, mensaje genérico al usuario

### Frontend:
- [ ] Editor visual del prompt del negocio (textarea con secciones guiadas)
- [ ] Vista previa del prompt generado
- [ ] Indicador de costo estimado de tokens
- [ ] Toggle para habilitar/deshabilitar IA

**Entregable**: Mensajes complejos respondidos por IA con contexto del negocio.

---

## FASE 5 — Dashboard Completo + Métricas (Semana 7)
**Objetivo**: Dashboard administrativo completo.

### Backend:
- [ ] MetricsService (consultas agregadas)
- [ ] GET /api/metrics/daily?from=&to=
- [ ] GET /api/metrics/summary (hoy, ayer, semana)
- [ ] WebSocket: push de nuevos mensajes al dashboard en tiempo real

### Frontend:
- [ ] Dashboard home con métricas (cards + gráficas)
- [ ] Mensajes enviados vs recibidos (recharts o similar)
- [ ] Conversaciones activas
- [ ] Últimos mensajes en tiempo real (WebSocket)
- [ ] Gestión de Contactos
- [ ] Búsqueda de conversaciones

**Entregable**: Dashboard completo con métricas reales.

---

## FASE 6 — Configuración Avanzada + Multimedia (Semana 8)
**Objetivo**: Dueño puede configurar todo visualmente.

### Backend:
- [ ] BusinessConfigController (CRUD)
- [ ] BusinessHoursController
- [ ] MediaCatalogController (upload + listar)
- [ ] File upload service (almacenamiento en VPS / S3-compatible)

### Frontend:
- [ ] Página de configuración del negocio (form guiado)
- [ ] Configuración de horarios (tabla día por día con toggle)
- [ ] Catálogo de multimedia (grid con upload drag&drop)
- [ ] Gestión de mensajes automáticos (bienvenida, fuera de horario)

**Entregable**: Negocio 100% configurable sin tocar código.

---

## FASE 7 — Seguridad, Logs y Human Takeover (Semana 9)
**Objetivo**: Sistema robusto y listo para producción.

### Backend:
- [ ] Audit logs en todas las operaciones críticas
- [ ] Human takeover: endpoint para que operador tome control
- [ ] Notificación (email/push) cuando conversación requiere humano
- [ ] Role-based access control (ADMIN vs OPERATOR)
- [ ] Input validation global con @Valid
- [ ] Rate limiting en API endpoints

### Frontend:
- [ ] Botón "Tomar control" en conversación
- [ ] Indicador visual de conversaciones con humano
- [ ] Página de logs de auditoría (solo ADMIN)
- [ ] Gestión de usuarios (solo ADMIN)

**Entregable**: Sistema seguro con escalamiento a humano.

---

## FASE 8 — Despliegue en VPS + SSL (Semana 10)
**Objetivo**: Producción estable con HTTPS.

### Infraestructura:
- [ ] docker-compose.prod.yml
- [ ] Nginx configurado para dominio real
- [ ] Let's Encrypt SSL (certbot)
- [ ] Variables de entorno en `.env` del VPS (nunca en git)
- [ ] Backups automáticos de PostgreSQL
- [ ] Script de deploy (git pull + docker-compose up -d)
- [ ] Logrotate para logs de containers
- [ ] Monitoring básico (uptime check)

**Entregable**: Sistema en producción con HTTPS, estable 24/7.

---

## 📊 Resumen de Fases

| Fase | Nombre                     | Semana | Prioridad |
|------|----------------------------|--------|-----------|
| 0    | Setup + Infraestructura    | 1      | 🔴 Crítica |
| 1    | Auth + Dashboard Shell     | 2      | 🔴 Crítica |
| 2    | WhatsApp Integration       | 3      | 🔴 Crítica |
| 3    | Motor Híbrido + FAQs       | 4-5    | 🔴 Crítica |
| 4    | Integración IA             | 6      | 🟠 Alta    |
| 5    | Dashboard + Métricas       | 7      | 🟠 Alta    |
| 6    | Configuración + Multimedia | 8      | 🟡 Media   |
| 7    | Seguridad + Human Takeover | 9      | 🟠 Alta    |
| 8    | Deploy Producción          | 10     | 🔴 Crítica |
