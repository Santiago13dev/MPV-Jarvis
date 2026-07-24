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

### Key Tables
- `conversations` → now has `pending_action` (VARCHAR 50) and `pending_action_data` (TEXT/JSON)
- `reservations` → `customer_name`, `phone_number`, `reservation_date`, `amount`, `status` (PENDIENTE/CONFIRMADA/CANCELADA), `notes`, `conversation_id`
- `faq_items` → seeded via `backend/scripts/seed-faqs.js` (reservation FAQs with keywords: reservar, reserva, mesa, cita, apartar)
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
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/WebhookController.java` — Receives messages from Node.js, passes `remoteJid`
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/WhatsappSessionController.java` — `POST /api/whatsapp/reset`
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/ReservationController.java` — CRUD REST API `/api/reservations`
- `backend/src/main/java/com/whatsappmvp/adapter/dto/request/WebhookMessageRequest.java` — DTO with `remoteJid`
- `backend/src/main/java/com/whatsappmvp/adapter/dto/request/ReservationRequest.java` — DTO for creating reservations
- `backend/src/main/java/com/whatsappmvp/infrastructure/client/WhatsAppServiceClient.java` — `.block()` instead of `.subscribe()`
- `backend/src/main/java/com/whatsappmvp/infrastructure/persistence/entity/ConversationEntity.java` — Has `pendingAction`, `pendingActionData`
- `backend/src/main/java/com/whatsappmvp/infrastructure/persistence/entity/ReservationEntity.java` — Reservation model
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

## Pending / Future Work

- [ ] Verify reservation flow end-to-end (test "reservar" → "SI" → data)
- [ ] Business hours configuration in DB — check `business_hours` table rows
- [ ] WhatsApp connection stability (440/408 errors on reconnect)
- [ ] Rate limit tuning
- [ ] Multi-language support
- [ ] Image/document handling beyond auto-response
