# AGENTS.md — WhatsApp MVP Project Context

## Architecture Overview

```
Angular (4200) → Nginx (80) → Spring Boot (8080) → PostgreSQL (5432)
                                    ↕
                            Node.js WhatsApp Service (3001) ←→ Baileys ←→ WhatsApp API
```

- **Docker Compose project name**: `whatsapp-MVP`
- **Containers**: `wamvp_whatsapp`, `wamvp_backend`, `wamvp_frontend`, `wamvp_postgres`, `wamvp_nginx`
- **Docker volume**: `wa_sessions:/app/sessions` (persists WhatsApp credentials)
- **Bot phone number**: `573123197433`
- **Client phone**: `48301856550957` (Santiago, sends from WhatsApp Web → produces `remoteJid: 48301856550957@lid`)
- **VPS**: Hostinger, path `/opt/MPV-Jarvis`
- **Repository**: `https://github.com/Santiago13dev/MPV-Jarvis`

---

## Current State (What Works)

### QR Reset Flow
- `POST /api/whatsapp/reset` (Spring Boot) → `POST /session/reset` (Node.js)
- Deletes `sessions/` folder, closes socket, reconnects with fresh QR
- Angular has "Reiniciar sesión" button in `whatsapp-status.component.ts`

### Message Pipeline (MessageProcessingService.java)

Pipeline order:
1. Rate limit check
2. Get/create contact
3. Get/create conversation
4. Persist inbound message
5. HUMAN_TAKEOVER check → skip automation
6. **5.5**: "reservar"/"reserva" → starts reservation flow (BEFORE FAQs)
7. **5.6**: Pending action check → handles reservation step (BEFORE FAQs)
8. Text-only filter (images → human)
9. Welcome message (first message)
10. **7A**: Keyword rules (KeywordMatchingService)
11. **7B**: FAQ matching (FaqMatchingService)
12. **8**: Outside business hours → off-hours message
13. **9**: AI fallback (OpenAI)

Key: FAQs and keywords work **24/7** (before business hours check). Only AI and old reservation creation were blocked outside hours.

### @lid JID Fix
- `sendAndPersistResponse()` now uses `remoteJid` (full JID) instead of `phone + @s.whatsapp.net`
- `@lid` JIDs are used for send-back (not converted to `@s.whatsapp.net`)
- WhatsApp Service Client `post()` uses `.block()` instead of `.subscribe()` (errors propagate)

### Reservation Flow (ReservationFlowService.java)
Flow:
1. Client: "reservar" → Bot: "¿Deseas hacer una reserva? Responde SI o NO"
2. Client: "SI" → Bot: asks for data (nombre, fecha, personas, motivo, homenajeado, hora)
3. Client: sends data → Bot: parses and creates reservation as CONFIRMADA
4. Client: "NO" → Bot: "¡Perfecto! Si necesitas algo más, estoy aquí."

State tracked via `conversations.pending_action` and `conversations.pending_action_data` (JSON).

### Angular Budget Fix
- `angular.json` → `anyComponentStyle: maxError` increased from `4kb` to `8kb` (pre-existing build error)

---

## Rebuild Commands

```powershell
# Backend rebuild
docker compose build --no-cache backend; if ($?) { docker compose up -d backend }

# WhatsApp service rebuild
docker compose build --no-cache whatsapp; if ($?) { docker compose up -d whatsapp }

# Full rebuild
docker compose build --no-cache; if ($?) { docker compose up -d }
```

Check logs:
```powershell
docker logs wamvp_backend --tail 30
docker logs wamvp_whatsapp --tail 20
```

---

## Database

### Flyway Migrations
- `V1__init_schema.sql` — contacts, conversations, messages, users, roles, faq_items, keyword_rules, business_config, business_hours
- `V2__seed_data.sql` — roles, business_config, business_hours, 3 generic FAQ items
- `V3__add_reservations.sql` — reservations table
- `V4__add_pending_action_to_conversations.sql` — `pending_action`, `pending_action_data` columns on conversations
- `V5__add_remote_jid_to_conversations.sql` — `remote_jid` column for @lid JID support
- `V6__add_is_deleted_to_conversations.sql` — soft delete support
- `V7__add_admin_phone_to_business_config.sql` — `admin_phone` column

### Key Tables
- `conversations` → now has `pending_action` (VARCHAR 50) and `pending_action_data` (TEXT/JSON)
- `reservations` → `customer_name`, `phone_number`, `reservation_date`, `amount`, `status` (PENDIENTE/CONFIRMADA/CANCELADA), `notes`, `conversation_id`
- `faq_items` → 73 items configured via admin API (reservation FAQs with keywords: reservar, reserva, mesa, cita, apartar)
- `keyword_rules` → empty by default, created via admin API

---

## Key Files

### Node.js WhatsApp Service
- `whatsapp-service/src/whatsapp/client.js` — Baileys session manager, `sendTextMessage()` (line 244), `resetSession()` (line 210)
- `whatsapp-service/src/whatsapp/webhook.service.js` — Forwards messages to backend, includes `remoteJid` (line 64-73)
- `whatsapp-service/src/routes/session.routes.js` — `POST /session/reset` endpoint (line 62)

### Spring Boot Backend
- `backend/src/main/java/com/whatsappmvp/application/service/MessageProcessingService.java` — Core pipeline
- `backend/src/main/java/com/whatsappmvp/application/service/ReservationFlowService.java` — Multi-step reservation conversation state machine
- `backend/src/main/java/com/whatsappmvp/application/service/ReservationService.java` — CRUD for reservations
- `backend/src/main/java/com/whatsappmvp/application/service/BusinessHoursService.java` — `isWithinBusinessHours()` checks DB `business_hours` by day-of-week
- `backend/src/main/java/com/whatsappmvp/application/service/FaqMatchingService.java` — Keyword/phrase matching
- `backend/src/main/java/com/whatsappmvp/application/service/KeywordMatchingService.java` — Matches against `keyword_rules` table
- `backend/src/main/java/com/whatsappmvp/application/service/AIService.java` — Reads system-prompt.txt from classpath
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/WebhookController.java` — Receives messages from Node.js, passes `remoteJid`
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/WhatsappSessionController.java` — `POST /api/whatsapp/reset`
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/ReservationController.java` — CRUD REST API `/api/reservations`
- `backend/src/main/java/com/whatsappmvp/adapter/dto/request/WebhookMessageRequest.java` — DTO with `remoteJid`
- `backend/src/main/java/com/whatsappmvp/adapter/dto/request/ReservationRequest.java` — DTO for creating reservations
- `backend/src/main/java/com/whatsappmvp/infrastructure/client/WhatsAppServiceClient.java` — `.block()` instead of `.subscribe()`
- `backend/src/main/java/com/whatsappmvp/infrastructure/persistence/entity/ConversationEntity.java` — Has `pendingAction`, `pendingActionData`
- `backend/src/main/java/com/whatsappmvp/infrastructure/persistence/entity/ReservationEntity.java` — Reservation model
- `backend/src/main/resources/system-prompt.txt` — System prompt for "La Montaña del Bendito Chicharrón"
- `backend/src/main/resources/db/migration/V4__add_pending_action_to_conversations.sql` — Latest migration

### Angular Frontend
- `frontend/src/app/features/whatsapp/whatsapp-status.component.ts` — QR display + "Reiniciar sesión" button
- `frontend/src/app/core/services/api.service.ts` — `resetWhatsapp()` method
- `frontend/angular.json` — Budget: `anyComponentStyle: 8kb`

---

## Issues Resolved (This Session)

| Issue | Root Cause | Fix |
|---|---|---|
| QR not working | Stale session | Added resetSession endpoint |
| FAQs blocked by business hours | Business hours check was before FAQ/keyword matching | Restructured pipeline: FAQs first |
| Bot responses not reaching device | `sendText()` used `.subscribe()` (fire-and-forget) | Changed to `.block()` for error propagation |
| @lid JID not handled | Sending to `@s.whatsapp.net` instead of `@lid` | Use `remoteJid` directly for send-back |
| Angular build error | `anyComponentStyle` budget too low | Increased to `8kb` |
| Reservation created without user input | Hardcoded at "reservar" keyword, dead code (FAQ caught it first) | New `ReservationFlowService` with SI/NO flow |

---

## Production Deployment (VPS Hostinger)

### Scripts disponibles (`scripts/`)

| Script | Descripción |
|--------|-------------|
| `backup-db.sh` | Crea backup de PostgreSQL antes de cada despliegue |
| `deploy.sh` | Despliegue seguro: backup → git pull → rebuild → restart |
| `restore-db.sh` | Restaura base de datos desde backup |

### Despliegue en VPS

```bash
# Primera vez: clonar, configurar .env, dar permisos
cp .env.example .env && nano .env
chmod +x scripts/*.sh

# Desplegar (automático con backup)
bash scripts/deploy.sh
```

### Archivos de producción

- `docker-compose.prod.yml` — Configuración separada para VPS
- `.env.example` — Template de variables de entorno

### ⚠️ Reglas críticas

1. **NUNCA** ejecutar `docker compose down -v` (borra volúmenes y datos)
2. **NUNCA** ejecutar `docker compose -f docker-compose.prod.yml down -v`
3. **SIEMPRE** ejecutar `backup-db.sh` antes de desplegar
4. Los volúmenes PostgreSQL (`wamvp_postgres_data`) persisten FAQs y configuración
5. Flyway ejecuta migraciones automáticamente al iniciar backend (NO borra datos existentes)
6. Para restaurar: `bash scripts/restore-db.sh`
7. `docker compose down` es SEGURO (mantiene volúmenes) — solo `-v` borra datos
8. Siempre usar `docker-compose.prod.yml` en VPS (no el `docker-compose.yml` de dev)

---

## Pending / Future Work

- [ ] Verify reservation flow end-to-end (test "reservar" → "SI" → data)
- [ ] Business hours configuration in DB — check `business_hours` table rows
- [ ] WhatsApp connection stability (440/408 errors on reconnect)
- [ ] Rate limit tuning
- [ ] Multi-language support
- [ ] Image/document handling beyond auto-response

---

## Restaurant Info: "La Montaña del Bendito Chicharrón"

- **Type**: Restaurante colombiano, chicharrón y comida tradicional
- **Horario**: Sábados y domingos 11:30 AM hasta agotar existencias + lunes festivos
- **Reservas**: Horarios sugeridos 11:10 AM, 12:00 PM, 12:30 PM, 1:00 PM
- **Datos para reserva**: Nombre, fecha, personas, motivo, homenajeado, hora
- **Decoración**: $40.000 COP
- **Tiempo máximo de espera**: 15 min (después sujeto a disponibilidad)
- **Política cancelación**: No hay devolución, dinero queda para otra fecha
- **Pagos**: Nequi, efectivo, transferencia, Llave
- **Domicilios**: NO
- **Pet Friendly**: Sí (traer bolsa para residuos)
- **WiFi**: NO
- **Factura electrónica**: Sí
- **Capacidad**: 112 mesas
- **Música**: Ocasionalmente en vivo los domingos
- **System prompt**: `backend/src/main/resources/system-prompt.txt` (AIService reads from classpath)
- **IA**: Groq API (llama3-8b-8192) — solo como fallback cuando FAQ/keywords no matchean
