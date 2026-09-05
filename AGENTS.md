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
6. **5.5**: "reservar"/"reserva" + context patterns → starts reservation flow (BEFORE FAQs)
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
1. Client: "reservar" or natural intent → Bot uses `smartParse()` (regex first, LLM fallback)
2. If data complete → Shows confirmation summary (📅🕐👥👤) → Client: "SI" → Creates reservation
3. If data incomplete → Shows detected data + what's missing + "Envíame los datos faltantes o pregunta lo que necesites"
4. Client: sends more data → Bot parses and merges with existing (new overwrites, existing preserved)
5. Client: asks a question → Bot answers (FAQ/AI) + "¿Sigo con tu reserva?" + shows current status (NO se pierde el contexto)
6. Client: "NO" → Bot: "¡Perfecto! Si necesitas algo más, estoy aquí."
7. State tracked via `conversations.pending_action` = `RESERVATION_COLLECTING` and `conversations.pending_action_data` (JSON).
8. Auto-expiry: 30 minutes of inactivity clears pending action.

Key improvements:
- **Smart parsing**: `smartParse()` tries regex (fast, free) first, then LLM (Groq) as fallback when regex misses fields
- **Non-destructive interruptions**: Questions during reservation DON'T clear the flow. Bot answers + preserves context.
- **Confirmation summary**: Shows full details with emojis before creating reservation
- **Data merging**: Each message merges new data with existing (no data loss)
- **Better UX**: Emojis (📅🕐👥👤🎂🎉), clear formatting, "Envíame los datos faltantes o pregunta lo que necesites"

### Angular Budget Fix
- `angular.json` → `anyComponentStyle: maxError` increased from `4kb` to `8kb` (pre-existing build error)

---

## Rebuild Commands (VPS)

```bash
# Backend rebuild
docker compose -f docker-compose.prod.yml build --no-cache backend
docker compose -f docker-compose.prod.yml up -d backend

# WhatsApp service rebuild
docker compose -f docker-compose.prod.yml build --no-cache whatsapp-service
docker compose -f docker-compose.prod.yml up -d whatsapp-service

# Full rebuild (safe — uses stop, not down -v)
bash scripts/deploy.sh
```

Check logs:
```bash
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
- `backend/src/main/java/com/whatsappmvp/application/service/ReservationFlowService.java` — Multi-step reservation conversation state machine (unified ACTION_COLLECTING state)
- `backend/src/main/java/com/whatsappmvp/application/service/ReservationService.java` — CRUD for reservations
- `backend/src/main/java/com/whatsappmvp/application/service/BusinessHoursService.java` — `isWithinBusinessHours()` checks DB `business_hours` by day-of-week
- `backend/src/main/java/com/whatsappmvp/application/service/FaqMatchingService.java` — Keyword/phrase matching
- `backend/src/main/java/com/whatsappmvp/application/service/KeywordMatchingService.java` — Matches against `keyword_rules` table
- `backend/src/main/java/com/whatsappmvp/application/service/AIService.java` — Reads system-prompt.txt from classpath + `extractReservationData()` for LLM-based extraction
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
| Bot stuck asking SI/NO for reservations | 3 separate states (ASK_CONFIRMATION, CONFIRM_PARSED, COLLECT_MISSING) only accepted SI/NO | Unified ACTION_COLLECTING state that parses data from any message |
| WhatsApp 440 conflict loop | Reconnected every 30s on conflict, triggered WhatsApp anti-spam | Max 5 retries with backoff (30s→120s), then 10min cooldown |
| WhatsApp 515 server drop | Not handled, retried immediately | 5min cooldown on515 |
| WhatsApp 401 auto-retry loop | Retried every 60s with invalid credentials, kept number blocked | No auto-retry on 401 — requires manual reset |
| AI model not found | `llama-3.3-70b-versatile` no longer available on Groq | Changed to `openai/gpt-oss-20b` via env var |
| Rate limit config ignored | `RateLimitService` hardcoded defaults, never read `application.yml` | Inject `AppProperties` instead of `BusinessConfigJpaRepository` |

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
4. Los volúmenes PostgreSQL (`wamvp_postgres_data`) persisten FAQs, reservas y configuración
5. Flyway ejecuta migraciones automáticamente al iniciar backend (NO borra datos existentes)
6. Para restaurar: `bash scripts/restore-db.sh`
7. `docker compose down` es SEGURO (mantiene volúmenes) — solo `-v` borra datos
8. Siempre usar `docker-compose.prod.yml` en VPS (no el `docker-compose.yml` de dev)
9. **Para reiniciar containers en VPS**: usar `docker compose -f docker-compose.prod.yml restart` (NUNCA `down -v`)
10. Si se necesita un rebuild completo en VPS, usar `deploy.sh` que hace backup automático antes

### ⛔ Incidente conocido: `docker compose down -v` borró la BD

Ejecutar `docker compose down -v` (con la flag `-v`) **borra el volumen `wamvp_postgres_data`** incluyendo todas las FAQs (73 items), reservas, configuración del negocio, y datos de contactos/conversaciones. Esto ya ha ocurrido en un rebuild anterior.

**Causa raíz**: La flag `-v` en `docker compose down` elimina volúmenes nombrados. Los cambios de CSS o frontend no tienen relación — el problema fue el uso de `-v` en un rebuild del backend.

**Prevención**: Siempre usar `restart` en vez de `down -v`:
```bash
# ✅ SEGURO — reinicia sin borrar datos
docker compose -f docker-compose.prod.yml restart

# ❌ PELIGROSO — borra toda la BD
docker compose -f docker-compose.prod.yml down -v
```

---

## Pending / Future Work

- [x] Verify reservation flow end-to-end (test "reservar" → "SI" → data)
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
- **IA**: Groq API (openai/gpt-oss-20b) — solo como fallback cuando FAQ/keywords no matchean

---

## WhatsApp Connection Troubleshooting

### Error Codes Reference

| Code | Meaning | Action |
|------|---------|--------|
| **401** | Logged out — credentials invalid | Manual reset required. DO NOT auto-retry (keeps number blocked) |
| **403** | Forbidden — number may be banned | Stop. Check WhatsApp Business status |
| **440** | Conflict — another device connected | Close all WhatsApp Web sessions, unlink all devices, then reset |
| **408** | Timeout — connection unstable | Auto-retry with exponential backoff (handled by code) |
| **515** | Server dropped connection — rate limit | Wait 5 minutes, then retry. Usually temporary |
| **463** | Message undeliverable in ack | Transient — caused by unstable connection |

### Reset Session Flow (VPS)

```bash
# 1. Stop service
docker stop wamvp_whatsapp

# 2. Delete ALL session files
docker run --rm -v wamvp_wa_sessions:/sessions alpine sh -c "rm -rf /sessions/default/*"

# 3. Restart
docker start wamvp_whatsapp

# 4. Monitor QR
docker logs wamvp_whatsapp -f --tail 10

# 5. Scan QR from PHONE (not WhatsApp Web)
#    WhatsApp Business → Settings → Linked Devices → Link a Device
```

### Critical: 401 Auto-Retry Anti-Pattern

**NEVER auto-retry on 401.** Each retry with invalid credentials tells WhatsApp "same device trying again" and keeps the number blocked. The 401 handler in `client.js` sets status to DISCONNECTED and stops. User must manually trigger `POST /session/reset`.

### After Too Many Failed Attempts

If you see repeated 440/515/401 errors:
1. **Stop the service completely** (`docker stop wamvp_whatsapp`)
2. **Wait 1-2 hours** for WhatsApp rate limiting to clear
3. Delete session files
4. Restart and scan QR fresh

### Deploy After Fixes

```bash
cd /opt/MPV-Jarvis
git pull
docker compose -f docker-compose.prod.yml build --no-cache whatsapp-service backend
docker compose -f docker-compose.prod.yml up -d whatsapp-service backend
```
