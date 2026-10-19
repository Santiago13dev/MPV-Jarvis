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

Pipeline order (two phases: DB work under per-phone lock, external calls outside it):
1. Dedup by `waMessageId`, get/create contact, get/create conversation
2. Rate limit check
3. Persist inbound message
4. `HUMAN_TAKEOVER` check → skip automation
5. **5.45**: `HUMAN_TRANSFER_OFFERED` → client "sí"/"no" executes/declines the transfer
6. **5.5**: Image/PDF → payment receipt (`ReservationConversationService.handleReceipt`)
7. **5.55**: Human-transfer keywords → `discardDraft()` + transfer (BEFORE the reservation engine)
8. **5.6**: **Reservation engine** (`ReservationConversationService.handleText`) → create/query/modify/cancel. Returns `REPLY` (answered, stop), `QUESTION_WITH_REMINDER` (answered mid-flow, draft kept) or `NOT_HANDLED` (continue)
9. **5.62/5.65**: Location / menu-PDF requests
10. Text-only filter (other media → human)
11. **7A**: Keyword rules (`KeywordMatchingService`)
12. **7B**: FAQ matching (`FaqMatchingService`)
13. **6**: Welcome message — **only if it's still the first message AND it is not a question**
    (`looksLikeQuestion()`); a question goes to AI instead of returning the greeting. Location is sent with it.
14. **8.5**: Timeout → transfer to human (skipped on the first message)
15. **9**: AI fallback (OpenAI/Groq)

Key: the reservation engine and FAQs/keywords run **24/7**. The old business-hours gate (step 8) is commented out — AI works 24/7 too. Message sending happens OUTSIDE the lock with a random 5-10s human delay and max 3 concurrent sends.

### @lid JID Fix
- `sendAndPersistResponse()` now uses `remoteJid` (full JID) instead of `phone + @s.whatsapp.net`
- `@lid` JIDs are used for send-back (not converted to `@s.whatsapp.net`)
- WhatsApp Service Client `post()` uses `.block()` instead of `.subscribe()` (errors propagate)

### Reservation Engine (`application/reservation/` package)

Reemplaza al antiguo `ReservationFlowService` (respaldo: `docs/legacy/ReservationFlowService.java.bak`).
Dos capas de estado:

**A. Ciclo de vida de la reserva (`ReservationStatus`)**
```
PENDING → PAYMENT_PENDING → PAYMENT_RECEIPT_SENT → CONFIRMED → CANCELLED
             │                      │  ▲ (recibo rechazado vuelve a PAYMENT_PENDING)
             └──────────────────────┘
CONFIRMED → CANCELLED. CANCELLED/EXPIRD = terminales.
ACTIVE (consume cupo) = PENDING, PAYMENT_PENDING, PAYMENT_RECEIPT_SENT, CONFIRMED
HOLDS (expiran por `holdExpiresAt`) = PENDING, PAYMENT_PENDING
```
Transiciones validadas por `canTransitionTo()`; invalidas → excepción.

**B. Estado conversacional (`ReservationConversationService`)**
- El estado vive en la tabla **`reservation_drafts`** (UNIQUE por `conversation_id`), NO en `conversations.pending_action`
  (`pending_action` solo se usa para `HUMAN_TRANSFER_OFFERED`)
- `Mode`: `CREATE` | `MODIFY` | `CANCEL`; campo `awaiting` = qué le preguntó el bot
  (`PARTY_SIZE`, `DATE`, `TIME`, `TIME_ANY`, `NAME`, `TYPE`, `CONFIRM`, `RESERVATION`, `CHANGES`)
- TTL del borrador: 30 min de inactividad
- Devuelve `Kind`: `REPLY` (respondió y corta), `QUESTION_WITH_REMINDER` (pregunta en medio → responde y
  recuerda "sigo con tu reserva?", borrador intacto), `NOT_HANDLED` (sigue el pipeline)
- La reserva **solo se crea cuando el cliente confirma** y `ReservationService.create()` tiene éxito
  (clave de idempotencia `wa:<waMessageId>`)

**Motor de negocio (`ReservationService` — única vía de escritura)**
- Cupo por slot con `pg_advisory_xact_lock` (concurrencia segura), doble chequeo de idempotencia
- Precios: NORMAL = por persona, VIP = precio fijo por espacio (exclusivo por slot, no consume cupo NORMAL)
- Crear / modificar (libera slot viejo, recalcula precio) / cancelar (soft, idempotente) / confirmar /
  recibo de pago (aceptar/rechazar) / expirar hold
- Auditoría (`reservation_audit_log`) + eventos Spring (`ReservationNotificationService`)
- Configuración admin: precios, días/horas operativos, intervalo de slots, capacidad, reglas por día/hora,
  bloqueos de fecha/slot (`RestaurantSettingsService` + `/api/reservations/configuration`)

**Extracción de intención/datos (`ReservationIntentExtractor`)**
- Reglas primero (gratis, cubre fechas/horas/nombres en español vía `DateExpressionResolver`/`TimeExpressionResolver`),
  luego LLM (Groq) como fallback con `enrich()`
- Intents: `CREATE_RESERVATION`, `CHECK_AVAILABILITY`, `MODIFY_RESERVATION`, `CANCEL_RESERVATION`,
  `GET_RESERVATION`, `PAYMENT_RECEIPT`, `GENERAL_QUESTION`, `NONE`

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
- `V8__add_people_count_to_reservations.sql` — `people_count` on reservations
- `V9__reservation_engine.sql` — engine del módulo: `restaurant_settings` (singleton), `slot_capacity_rules`,
  `reservation_blocks`, columnas nuevas de `reservations` (type/slot/prices/hold/idempotency_key/
  reservation_number/audit), índices únicos parciales (VIP por slot, idempotency key), `reservation_audit_log`,
  `reservation_receipts`, `reservation_drafts`. No destructiva.

### Key Tables
- `conversations` → `pending_action` (VARCHAR 50) y `pending_action_data` (TEXT/JSON). Hoy solo se usa para `HUMAN_TRANSFER_OFFERED`
- `reservations` → `customer_name`, `phone_number`, `reservation_date`, `amount`, `status` (ciclo de vida del motor), `notes`, `conversation_id`, `type` (NORMAL/VIP), `slot_time`, precios, `hold_expires_at`, `idempotency_key`, `reservation_number`
- `reservation_drafts` → estado conversacional (UNIQUE `conversation_id`, `mode` CREATE/MODIFY/CANCEL, `awaiting`)
- `reservation_receipts` → comprobantes de pago subidos por WhatsApp o dashboard
- `reservation_audit_log` → auditoría de toda escritura
- `restaurant_settings` → singleton de precios/horarios/capacidad
- `slot_capacity_rules`, `reservation_blocks` → reglas de cupo por día/hora y bloqueos
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
- `backend/src/main/java/com/whatsappmvp/application/reservation/ReservationConversationService.java` — Conversational state machine (drafts, `handleText`/`handleReceipt`) — replaces deleted `ReservationFlowService`
- `backend/src/main/java/com/whatsappmvp/application/reservation/ReservationIntentExtractor.java` — Rules + LLM intent/data extraction
- `backend/src/main/java/com/whatsappmvp/application/reservation/ReservationAvailabilityService.java` — Slot capacity / availability
- `backend/src/main/java/com/whatsappmvp/application/reservation/RestaurantSettingsService.java` — Settings, capacity rules, blocks
- `backend/src/main/java/com/whatsappmvp/application/reservation/ReservationMessages.java` — All customer-facing texts
- `backend/src/main/java/com/whatsappmvp/application/service/ReservationService.java` — Single write path: create/modify/cancel/confirm/receipts/expire, advisory locks, audit
- `backend/src/main/java/com/whatsappmvp/application/service/BusinessHoursService.java` — `isWithinBusinessHours()` checks DB `business_hours` by day-of-week
- `backend/src/main/java/com/whatsappmvp/application/service/FaqMatchingService.java` — Keyword/phrase matching
- `backend/src/main/java/com/whatsappmvp/application/service/KeywordMatchingService.java` — Matches against `keyword_rules` table
- `backend/src/main/java/com/whatsappmvp/application/service/AIService.java` — Reads system-prompt.txt from classpath + `extractReservationData()` for LLM-based extraction
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/WebhookController.java` — Receives messages from Node.js, passes `remoteJid`
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/WhatsappSessionController.java` — `POST /api/whatsapp/reset`
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/ReservationController.java` — CRUD REST API `/api/reservations`
- `backend/src/main/java/com/whatsappmvp/adapter/in/web/ReservationConfigController.java` — `/api/reservations/configuration` (settings, capacity rules, blocks)
- `backend/src/main/java/com/whatsappmvp/adapter/dto/request/WebhookMessageRequest.java` — DTO with `remoteJid`
- `backend/src/main/java/com/whatsappmvp/adapter/dto/request/ReservationRequest.java` — DTO for creating reservations
- `backend/src/main/java/com/whatsappmvp/infrastructure/client/WhatsAppServiceClient.java` — `.block()` instead of `.subscribe()`
- `backend/src/main/java/com/whatsappmvp/infrastructure/persistence/entity/ConversationEntity.java` — Has `pendingAction`, `pendingActionData`
- `backend/src/main/java/com/whatsappmvp/infrastructure/persistence/entity/ReservationEntity.java` — Reservation model
- `backend/src/main/resources/system-prompt.txt` — System prompt for "La Montaña del Bendito Chicharrón"
- `backend/src/main/resources/db/migration/V9__reservation_engine.sql` — Latest migration

### Angular Frontend
- `frontend/src/app/features/whatsapp/whatsapp-status.component.ts` — QR display + "Reiniciar sesión" button
- `frontend/src/app/features/reservations/reservations.component.ts` — Dashboard de reservas (buscar, crear, confirmar, cancelar, recibos, disponibilidad del día, configuración)
- `frontend/src/app/core/services/api.service.ts` — `resetWhatsapp()` + todo el API de reservas (`search/create/update/confirm/cancel/rejectReceipt/uploadReceipt/stats/day-availability/configuration`)
- `frontend/src/app/core/models/models.ts` — `Reservation`, `ReservationStatus`, `ReservationType`, receipts, settings, capacity rules, blocks, `SlotAvailability`
- `frontend/angular.json` — Budget: `anyComponentStyle: 8kb`

### Tests (backend only)
- `backend/src/test/java/com/whatsappmvp/application/reservation/` — unit tests puros (JUnit 5, sin Spring):
  `DateExpressionResolverTest` (8), `TimeExpressionResolverTest` (6), `ReservationIntentExtractorTest` (20),
  `ReservationPricingAndStatusTest` (6) = 40 tests
- `backend/src/test/java/com/whatsappmvp/application/service/FaqMatchingServiceTest` (5, Mockito puro) —
  scoring de FAQs: plural/singular con límite de palabra, atajo de palabras ≤3, umbral del side-FAQ
- `backend/src/test/java/com/whatsappmvp/integration/` — `PostgresTestBase` (Testcontainers `postgres:15-alpine`,
  Flyway V1..V9, se omite si no hay Docker), `ReservationServiceIntegrationTest` (22) y
  `ReservationConversationIntegrationTest` (5, `@SpringBootTest`) = 27 tests
- Ejecutar: `mvn test` en `backend/` (las de integración requieren Docker).
  Total actual: **45 unit + 27 integración = 72 tests, todos en verde**
- Sin CI. Frontend sin setup de tests (no hay target `ng test` ni specs). Sin tests de controllers REST ni del pipeline `MessageProcessingService`.

---

## Reservation Engine — Hardening E2E (última sesión)

Batería de ~90 mensajes de clientes reales enviados al webhook (`POST /api/webhook/message`, header
`X-Webhook-Secret: devsecret`) contra un backend local con la BD de Docker (`wamvp_postgres`), leyendo las
respuestas con `psql`. Reportes: `/tmp/opencode/matrix_report.txt`, `/tmp/opencode/matrix_extra*.log`
(los scripts viven en `/tmp/opencode/*.py` y **se borran al limpiar /tmp** — se recrean en ~10 min si hace falta).

### Bugs encontrados y corregidos (todos verificados E2E)

| Sintoma del cliente | Causa raiz | Fix |
|---|---|---|
| Primer mensaje "¿hacen domicilios?" → solo el saludo | PASO 6 (bienvenida) estaba ANTES de keywords/FAQ y hacía `return` | 7A/7B ahora van antes; si es pregunta sin match → pasa a IA (nunca saludo) — `looksLikeQuestion()` |
| "reserva para 10 y voy con mi perro" solo preguntaba el día | El motor de reservas se tragaba la pregunta lateral | side-FAQ en el `REPLY`: `FaqMatchingService.findMatch(msg, exclude, 3)` + `isReservationFaq()` |
| "no puedo asistir, quiero cancelar" → FAQ de política secuestraba el flujo | `intentFrom` exigía `cancelWord && mentionsReservation` | `cancelWord && (mentionsReservation \|\| (!hasDraft && !questionMark))` |
| "quiero cambiar a 6 personas" → FAQ de política | ídem con `modifyWord` | `modifyWord && (mentionsReservation \|\| !hasDraft)`; `Context` ahora lleva `hasDraft` |
| "¿me devuelven el dinero si cancelo?" entraba al flujo de cancelar | cancel demasiado agresivo sin `questionMark` | regla de arriba (`!questionMark`) |
| "ya pagué, ¿me confirmas?" sin reserva → FAQ decía "Tu reserva ha quedado confirmada" (mentira) | `PAYMENT_RECEIPT` devolvía `notHandled` y caía en FAQ | `start()` PAYMENT_RECEIPT → `askReceiptImage` / `noPendingPayment` / `noReservations` |
| "reserva para 0" / "para 50" esperaban hasta pedir la fecha | no había validacion temprana | `advanceCreate`: `partySize < 1` → `msg.partyInvalid()`; `> maxPartySize` → `handToHuman(msg.partyTooLarge)` |
| "tengo reserva a las 12" → creaba una reserva nueva | frase no estaba en `GET_RESERVATION` | agregadas "tengo reserva(s)", "ya reserve", "ya hice la reserva", "ya aparte" |
| "¿hacen domicilios?" no matcheaba keyword "domicilio" | singular/plural solo sumaba +1 | plural/singular con límite de palabra (+3) + raíz de 5 letras (+2) |
| "reserva para 4 mediodía" → recibía la FAQ de horarios pegada | el singular derivado "dia" (3 letras) entraba por `contains()` dentro de "mediodia" | formas derivadas SIEMPRE con `matchesWholeWord()` (sin atajo ≤3); side-FAQ exige score ≥3 |
| Tabla `reservation_drafts` crecía sin limite (76 filas en 1h de pruebas) | nadie borraba borradores de chats abandonados | `ReservationExpirationJob.purgeExpiredDrafts()` (cada 60s, TTL 30 min) |

### Flujos verificados end-to-end
Crear (1 mensaje y por pasos), confirmar → `#`, modificar + confirmar cambio (precio recalculado),
cancelar con frase sin "reserva" y con confirmación sí/no, "no" en la confirmación, cancelar a mitad de creación,
disponibilidad por día/personas, VIP (precio fijo por slot), día cerrado/fecha pasada, grupos >40 → asesor,
personas 0/negativas, pregunta de perro en medio del flujo (`QUESTION_WITH_REMINDER`), consultas de reserva
(`GET_RESERVATION`), comprobante "ya pagué" con y sin reserva pendiente, FAQ (wifi, domicilios, factura,
medios de pago, música, parqueadero, mascota, política de cancelación, horarios).

### Criterio de "excelente" para la demo
- Todo lo de arriba responde bien; lo que queda **pendiente de re-verificar** (1 pasada de 90s):
  caso "reserva para 4 mediodía" E2E (ya cubierto por unit test) y regresión de side-FAQ "perro".
- IA local **sin `GROQ_API_KEY`** → contesta "En este momento no puedo procesar tu consulta…" (processed_by=AI).
  En el VPS sí hay key. Si la demo es local, exportar la key antes de arrancar.
- La BD local tiene **19 FAQs** (cargadas con `backend/scripts/faq_backup.sql`, que hace `DELETE FROM faq_items`);
  producción tiene 73. Casos como "¿cuánto cuesta el espacio VIP?" no tienen FAQ local y caen a IA.
- Bug conocido (bajo): "¿me dan un descuento por mi cumpleaños?" empatan promo vs decoración y gana decoración
  (tie-break por prioridad). Arreglar si se ve en la demo.

### Cómo levantar el backend local (WSL, sin Docker CLI)
```bash
# 1) proxy TCP a Docker Desktop (una vez por sesión; sin esto Testcontainers/psql no conectan)
docker.exe run --rm -d --name tc-proxy -p 2375:2375 \
  -v /var/run/docker.sock:/var/run/docker.sock alpine/socat \
  TCP-LISTEN:2375,fork,reuseaddr,bind=0.0.0.0 UNIX-CONNECT:/var/run/docker.sock

# 2) backend local (¡ usar setsid: si no, la tool mata el proceso al hacer timeout !)
cd backend && setsid bash -c '
  JAVA_HOME=/mnt/c/Users/kevin/.jdks/temurin-17 \
  SPRING_DATASOURCE_URL=jdbc:postgresql://172.25.96.1:5432/whatsapp_mvp \
  UPLOADS_PATH=/tmp/opencode/uploads \
  exec /mnt/c/Herramientas/apache-maven-3.9.6/bin/mvn -B spring-boot:run > /tmp/opencode/backend.log 2>&1
' < /dev/null > /dev/null 2>&1 &

# 3) salud: curl localhost:8080/actuator/health  (arranca en ~40-60s)
# psql: docker.exe exec -i wamvp_postgres psql -U wamvp_user -d whatsapp_mvp -c "..."
```
**Trampas**: `pkill -f "spring-boot:run"` mata la shell propia (el patrón está en la línea de comando — usar
`ps -eo pid,args | grep -E "[s]pring..."`); integración de Docker WSL está desactivada → `docker` no existe
(solo `docker.exe`); login de prueba `admin@example.com / admin123`.

---

## Issues Resolved (This Session)

| Issue | Root Cause | Fix |
|---|---|---|
| QR not working | Stale session | Added resetSession endpoint |
| FAQs blocked by business hours | Business hours check was before FAQ/keyword matching | Restructured pipeline: FAQs first |
| Bot responses not reaching device | `sendText()` used `.subscribe()` (fire-and-forget) | Changed to `.block()` for error propagation |
| @lid JID not handled | Sending to `@s.whatsapp.net` instead of `@lid` | Use `remoteJid` directly for send-back |
| Angular build error | `anyComponentStyle` budget too low | Increased to `8kb` |
| Reservation created without user input | Hardcoded at "reservar" keyword, dead code (FAQ caught it first) | Replaced by the new reservation engine (`application/reservation/`) — superseded the `ReservationFlowService` SI/NO flow |
| Bot stuck asking SI/NO for reservations | 3 separate states (ASK_CONFIRMATION, CONFIRM_PARSED, COLLECT_MISSING) only accepted SI/NO | Superseded: engine parses data from any message via `ReservationIntentExtractor` |
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
- [x] 62 automated tests for the reservation engine (unit + Testcontainers integration)
- [x] Hardening E2E del motor de reservas: ~90 mensajes de cliente, 10 bugs corregidos (ver
      "Reservation Engine — Hardening E2E")
- [ ] **Última pasada E2E** (90s, backend local): caso "reserva para 4 mediodía" (sin FAQ de horarios pegada)
      + regresión del side-FAQ "perro" — la lógica ya está en unit test (`FaqMatchingServiceTest`)
- [ ] **Commit pendiente** (8+2 archivos sin commitear: `MessageProcessingService`, `FaqMatchingService`,
      `ReservationIntentExtractor`, `ReservationConversationService`, `ReservationMessages`,
      `ReservationExpirationJob`, `ReservationDraftJpaRepository` + tests)
- [ ] `GROQ_API_KEY` en local si la demo se hace sin VPS (si no, la IA contesta el fallback genérico)
- [ ] Cargar las 73 FAQs de producción en local (`faq_backup.sql` solo trae 19)
- [ ] Tie-break de FAQs con score empatado ("descuento" vs "cumpleaños")
- [ ] Tests for REST controllers (`ReservationController`, `ReservationConfigController`) and `MessageProcessingService`
- [ ] Frontend test setup (karma/jasmine) + specs for `reservations.component.ts`
- [ ] CI so `mvn test` runs on every push
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
