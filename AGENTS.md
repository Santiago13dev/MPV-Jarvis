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

Key: the reservation engine and FAQs/keywords run **24/7**. The old business-hours gate (step 8) is commented out — AI works 24/7 too. Message sending happens OUTSIDE the lock with a configurable human delay (`app.outbound.delay-min-ms` / `delay-max-ms`, default 5-10s; local `.env` uses 0-200ms) and max 3 concurrent sends.

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
- Consulta VIP suelta (`¿tienen VIP?`, `¿cuánto cuesta el VIP?`) → `GENERAL_QUESTION` con `type=VIP`:
  responde capacidad/precio (`ReservationMessages.vipInfo()`) y abre un borrador `CREATE` VIP para seguir el flujo
- VIP ocupado: si ya hay una reserva VIP activa ese día, no se ofrecen otras franjas; se responde que ya
  está reservado y se ofrece un asesor (`HUMAN_TRANSFER_OFFERED`).

**Encuesta de celebración (V13/V15)**
- Antes de confirmar el bot pregunta el **motivo** (`awaiting = CELEBRATION`, luego `BIRTHDAY_GENDER` y `HONOREES`).
  El motivo y los homenajeados se guardan en el borrador y en la reserva.
- Salida de emergencia: si el cliente responde dos veces algo que no es un motivo (un "hola", una corrección
  de hora, un "que pena"), se asume **"solo guardar el espacio"** y el flujo continúa. Contador en
  `reservation_drafts.survey_attempts` (V15); la primera vez se repite la pregunta con un texto más explícito
  (`ReservationMessages.askCelebrationRetry()`). Sin esto la conversación quedaba atrapada para siempre.
- Durante la encuesta **no se llama al LLM**: un 429 de Groq inyectaba "estoy teniendo dificultades" justo
  cuando el cliente estaba respondiendo el motivo.
- Corrección de datos fuera de la pregunta: si el cliente cambia fecha/hora/personas mientras el bot pregunta
  otra cosa, la respuesta antepone un aviso ("Listo, actualicé la hora a 1:00 PM.") — antes el cambio se
  aplicaba en silencio y el resumen salía con otro dato sin explicación.

**Fechas y horas (`DateExpressionResolver` / `TimeExpressionResolver`)**
- Horas compactas en cualquier contexto: `1230`, `100`, `1-00`, `12.30` (con o sin `a las`), además de `12:30`, `1:00 pm`.
- `sábado 30` cuando el 30 no cae sábado → **pregunta** ("El 30 de octubre cae viernes, no sábado…") en vez de
  elegir un día distinto en silencio.
- Una fecha sin año solo se pasa al año siguiente si quedó **más de 45 días atrás** ("5 de enero" dicho en
  octubre). Si pasó hace pocos días ("24 oct" dicho el 26) se responde que ya pasó; antes se agendaba sin
  avisar para dentro de un año.
- Nombres: se descartan etiquetas de formulario (`nombre de quien reserva`, `de quien`) y palabras del flujo
  (`hola`, `cumpleaños`, `reserva`). En la confirmación también se acepta un nombre suelto ("Juan Melo").

**Motor de negocio (`ReservationService` — única vía de escritura)**
- Cupo normal por slot con `pg_advisory_xact_lock`; el VIP además toma un lock por día (concurrencia segura), doble chequeo de idempotencia
- Precios (`ReservationPricing`, única fuente de verdad): NORMAL = base 0 por defecto (**solo guardar el espacio**) + paquete de celebración; VIP = valor fijo por el espacio completo ($100.000 por defecto; **un solo espacio VIP por día**, no consume cupo NORMAL). Paquete de celebración: $40.000 para 1 homenajeado + $10.000 por adicional (`celebration_price` / `extra_honoree_price` en `restaurant_settings`)
- Crear / modificar (libera slot viejo, recalcula precio) / cancelar (soft, idempotente) / confirmar /
  recibo de pago (aceptar/rechazar) / expirar hold
- Auditoría (`reservation_audit_log`) + eventos Spring (`ReservationNotificationService`)
- Configuración admin: precios, días/horas operativos, intervalo de slots, capacidad, reglas por día/hora,
  bloqueos de fecha/slot (`RestaurantSettingsService` + `/api/reservations/configuration`)
- Calendario: sábados/domingos configurados + **lunes festivos** de Colombia (`ColombianHolidays`).
  `open_on_holiday_mondays` (default TRUE) se edita desde el dashboard; los lunes normales siguen cerrados.

**Extracción de intención/datos (`ReservationIntentExtractor`)**
- Reglas primero (gratis, cubre fechas/horas/nombres en español vía `DateExpressionResolver`/`TimeExpressionResolver`),
  luego LLM (Groq) como fallback con `enrich()`
- Intents: `CREATE_RESERVATION`, `CHECK_AVAILABILITY`, `MODIFY_RESERVATION`, `CANCEL_RESERVATION`,
  `GET_RESERVATION`, `PAYMENT_RECEIPT`, `GENERAL_QUESTION`, `NONE`

### Angular Budget Fix
- `angular.json` → `anyComponentStyle`: `maximumWarning` 6kb, `maximumError` 8kb (el dashboard de reservas y el sidebar superan los 4kb)

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
  reservation_number/audit), índices únicos parciales (VIP por slot en V9; V12 lo reemplaza por uno por día, idempotency key), `reservation_audit_log`,
  `reservation_receipts`, `reservation_drafts`. No destructiva.
- `V10__fix_generic_faq_seed.sql` — corrige **condicionalmente** las FAQs genéricas de V2 (horario
  “lunes a viernes 8am–6pm”, medios de pago y pedidos) y agrega 5 FAQs oficiales que faltaban
  (factura, política de cancelación, capacidad, música, espera). Si producción ya tiene una respuesta
  propia, no la toca; es segura para desplegar.
- `V11__add_holiday_mondays_setting.sql` — agrega `restaurant_settings.open_on_holiday_mondays`
  (BOOLEAN NOT NULL DEFAULT TRUE). No destructiva; permite abrir/cerrar los lunes festivos desde el dashboard.
- `V12__one_vip_per_day.sql` — reemplaza el índice único VIP por franja por uno por día (el VIP es un solo
  espacio físico). Si ya hubiera VIP activos duplicados el mismo día, omite el índice único para no romper
  el despliegue; la aplicación los sigue bloqueando con un lock por día.

### Key Tables
- `conversations` → `pending_action` (VARCHAR 50) y `pending_action_data` (TEXT/JSON). Hoy solo se usa para `HUMAN_TRANSFER_OFFERED`
- `reservations` → `customer_name`, `phone_number`, `reservation_date`, `amount`, `status` (ciclo de vida del motor), `notes`, `conversation_id`, `type` (NORMAL/VIP), `slot_time`, precios, `hold_expires_at`, `idempotency_key`, `reservation_number`
- `reservation_drafts` → estado conversacional (UNIQUE `conversation_id`, `mode` CREATE/MODIFY/CANCEL, `awaiting`)
- `reservation_receipts` → comprobantes de pago subidos por WhatsApp o dashboard
- `reservation_audit_log` → auditoría de toda escritura
- `restaurant_settings` → singleton de precios/horarios/capacidad; `open_on_holiday_mondays` controla los lunes festivos
- `slot_capacity_rules`, `reservation_blocks` → reglas de cupo por día/hora y bloqueos
- `faq_items` → 73 items configured via admin API en producción. En local se cargan 73 FAQs oficiales de
  respaldo con `python3 scripts/seed_faqs_full.py --update` (idempotente; no borra filas). Quedan 6 filas
  legacy de la semilla con pregunta distinta (mismo tema); son de prioridad baja y no ganan el matching.
- `keyword_rules` → empty by default, created via admin API

### FAQs: backup local vs. 73 de producción
- `backend/scripts/faq_backup.sql` **NO es el backup de las 73**: tiene 19 filas viejas (2026-07-29) e
  incluye datos incorrectos (“viernes a domingo 11PM”, “cuando juega colombia”, “50% de descuento”).
  No restaurarlo en la demo.
- `backend/scripts/seed-faqs.js` y `seed-restaurant.sql` son semillas parciales (16/19), no las 73.
- Las 73 exactas de producción no están versionadas. Para recuperarlas, con el VPS accesible (a hoy
  `147.93.58.156` responde en 22/80/443, pero la app está caída y nginx muestra la página por defecto):
  ```bash
  cd /opt/MPV-Jarvis
  # SOLO Postgres; NUNCA `down -v`
  docker compose -f docker-compose.prod.yml up -d postgres
  docker exec -i wamvp_postgres pg_dump -U wamvp_user -d whatsapp_mvp \
    -t faq_items --data-only --column-inserts > /tmp/faq_items.sql
  wc -l /tmp/faq_items.sql
  ```
  Luego traer el archivo y, en local, respaldar la tabla actual y reemplazarla:
  ```bash
  docker compose exec -T postgres pg_dump -U wamvp_user -d whatsapp_mvp -t faq_items \
    --data-only --column-inserts > /tmp/faq_local_actual.sql
  docker compose exec -T postgres psql -U wamvp_user -d whatsapp_mvp -c "TRUNCATE faq_items;"
  docker compose exec -T postgres psql -U wamvp_user -d whatsapp_mvp < /tmp/faq_items.sql
  ```
  `TRUNCATE` solo en local; en producción está prohibido. Si no hay SSH, el mismo dump se puede ejecutar
  desde la terminal web de Hostinger y pegar el SQL.

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
- `backend/src/main/java/com/whatsappmvp/application/reservation/ReservationCalendarService.java` — Calendario: días operativos, lunes festivos, franjas y bloqueos
- `backend/src/main/java/com/whatsappmvp/application/reservation/ColombianHolidays.java` — Festivos colombianos calculados (fijos, Ley Emiliani y Semana Santa)
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
- `backend/src/main/resources/db/migration/V12__one_vip_per_day.sql` — Latest migration
- `scripts/seed_faqs_full.py` — carga/actualiza las 73 FAQs oficiales de respaldo en local (idempotente)

### Angular Frontend
- `frontend/src/app/features/whatsapp/whatsapp-status.component.ts` — QR display + "Reiniciar sesión" button
- `frontend/src/app/features/reservations/reservations.component.ts` — Dashboard de reservas (buscar, crear, confirmar, cancelar, recibos, disponibilidad del día, configuración)
- `frontend/src/app/core/services/api.service.ts` — `resetWhatsapp()` + todo el API de reservas (`search/create/update/confirm/cancel/rejectReceipt/uploadReceipt/stats/day-availability/configuration`)
- `frontend/src/app/core/models/models.ts` — `Reservation`, `ReservationStatus`, `ReservationType`, receipts, settings, capacity rules, blocks, `SlotAvailability`
- `frontend/nginx.conf` — proxy `/api`, `/ws`, `/uploads` → `backend:8080` (el build de producción usa `apiUrl: '/api'`)
- `frontend/angular.json` — Budget: `anyComponentStyle` warning 6kb / error 8kb

### Tests (backend only)
- `backend/src/test/java/com/whatsappmvp/application/reservation/` — unit tests puros (JUnit 5, sin Spring):
  `DateExpressionResolverTest` (9), `TimeExpressionResolverTest` (6), `ReservationIntentExtractorTest` (20),
  `ReservationPricingAndStatusTest` (6), `ColombianHolidaysTest` (5, festivos fijos/Ley Emiliani/Semana Santa)
  y `ReservationCalendarServiceTest` (3, lunes festivos vs. lunes normales) = 49 tests
- `backend/src/test/java/com/whatsappmvp/application/service/FaqMatchingServiceTest` (7, Mockito puro) —
  scoring de FAQs: plural/singular con límite de palabra, atajo de palabras ≤3, umbral del side-FAQ y
  keywords multi-palabra (no deben matchear por el prefijo de la primera palabra)
- `backend/src/test/java/com/whatsappmvp/integration/` — `PostgresTestBase` (Testcontainers `postgres:15-alpine`,
  Flyway V1..V12, se omite si no hay Docker), `ReservationServiceIntegrationTest` (23, incluye lunes festivo)
  y `ReservationConversationIntegrationTest` (5, `@SpringBootTest`) = 28 tests
- Ejecutar: `mvn test` en `backend/` (las de integración requieren Docker).
  En macOS con Docker Desktop, Testcontainers puede quedar skipped por el socket
  (`~/.docker/run/docker.sock`); en ese caso correr las baterías E2E que usan la BD real.
  Total actual: **56 unit en verde**; 28 de integración compilan y quedan skipped en este entorno.
- Baterías locales (backend + BD Docker):
  - `python3 scripts/test_reservation_full.py` → **181/181** (incluye la encuesta de celebración, la reserva
    gratis sin celebración y el rescate de la encuesta). El script responde solo la encuesta
    (`auto_survey()`) porque los guiones son anteriores a V13; la sección VIP la desactiva (`AUTO_CELEBRATION = "no"`)
    para validar el precio base del espacio.
  - `python3 scripts/test_dashboard_config.py` → **46/46**
  - `python3 scripts/test_ai_cases.py` → **17/17** (requiere `GROQ_API_KEY` en `.env`)
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
disponibilidad por día/personas, VIP (un solo espacio por día; si está ocupado → asesor), día cerrado/fecha pasada, grupos >40 → asesor,
personas 0/negativas, pregunta de perro en medio del flujo (`QUESTION_WITH_REMINDER`), consultas de reserva
(`GET_RESERVATION`), comprobante "ya pagué" con y sin reserva pendiente, FAQ (wifi, domicilios, factura,
medios de pago, música, parqueadero, mascota, política de cancelación, horarios).

### Criterio de "excelente" para la demo
- La última pasada E2E (2026-10-19) quedó en verde: "reserva para 4 mediodía", side-FAQ "perro"/"wifi",
  VIP, modificación y cancelación. Ver "Pending / Future Work".
- IA local **sin `GROQ_API_KEY`** → contesta "En este momento no puedo procesar tu consulta…" (processed_by=AI).
  En el VPS sí hay key. Si la demo es local, exportar la key antes de arrancar.
- La BD local tiene **73 FAQs oficiales de respaldo** (cargadas con `scripts/seed_faqs_full.py`), no
  necesariamente las mismas 73 de producción. Casos como "¿cuánto cuesta el espacio VIP?" no dependen de
  FAQ: el motor responde `vipInfo()` directamente (y la FAQ de VIP se excluye del side-FAQ).
- Bug corregido: una keyword multi-palabra como `parqueadero para motos` matcheaba por el prefijo de la
  primera palabra (`parqu...`) y le robaba la FAQ general de parqueadero. Ahora el atajo de prefijo solo
  aplica a keywords de una palabra. Esto también evitó que la FAQ de “cancelar reserva” le ganara a la de
  “política de cancelación” por compartir el prefijo `cance`.
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

### Cómo levantar el entorno local (macOS + Docker Desktop)

```bash
# Levanta PostgreSQL + backend + dashboard Angular + simulador de WhatsApp (sin QR)
bash scripts/dev-up.sh

# URLs
#   Dashboard:  http://localhost:4200   (admin@example.com / admin123)
#   Simulador:  http://localhost:3001
#   Backend:    http://localhost:8080

# Detener sin borrar datos
bash scripts/dev-stop.sh
```

- `docker-compose.local.yml` + `backend/Dockerfile.local` existen porque las imágenes `alpine` no tienen
  manifest ARM64; el override compila el backend con `eclipse-temurin:17-jdk`.
- El nginx del frontend (`frontend/nginx.conf`) hace proxy de `/api`, `/ws` y `/uploads` a `backend:8080`,
  así el build de producción (`apiUrl: '/api'`) funciona también al abrir el dashboard en `:4200`.
- `OUTBOUND_DELAY_MIN_MS=0` / `OUTBOUND_DELAY_MAX_MS=200` en `.env` aceleran las pruebas; en producción
  el default es 5000-10000 ms.
- Para WhatsApp real con QR: `bash scripts/dev-real-wa.sh` (⚠️ usa un número BOT distinto al de producción;
  si no, el login local desconecta el de la VPS con error 440).

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
- [x] 84 tests del motor de reservas (56 unit + 28 Testcontainers; los 28 de integración quedan skipped en macOS)
- [x] Hardening E2E del motor de reservas: ~90 mensajes de cliente, 10 bugs corregidos (ver
      "Reservation Engine — Hardening E2E")
- [x] **Lunes festivos (2026-10-19)**: calendario colombiano (`ColombianHolidays`), configuración
      `open_on_holiday_mondays` en dashboard, V11 y fix del resolver para “lunes 2 de noviembre”.
      E2E 171/171, dashboard 46/46, IA 17/17 y 56 unit en verde.
- [x] **Última pasada E2E (2026-10-19, local macOS)**: VIP info/creación, un solo VIP por día (si está
      ocupado se ofrece asesor), normal,
      modificación 5→6 (precio fijo $40.000), cancelación + liberación del día VIP, "reserva para 4 mediodía"
      y side-FAQ "perro"/"wifi" con recordatorio.
- [x] **73 FAQs oficiales de respaldo en local** (`scripts/seed_faqs_full.py --update`) y fix del matcher
      para keywords multi-palabra; baterías 171/171 (E2E), 46/46 (dashboard/config) y 17/17 (IA).
- [ ] **Recuperar las 73 exactas del VPS** cuando esté accesible (el disco puede seguir vivo; ver
      “FAQs: backup local vs. 73 de producción”). Mientras tanto, el set local cubre los temas oficiales.
- [ ] Limpiar 6 filas legacy de FAQs con pregunta distinta (mismo tema, prioridad baja) cuando se
      reemplace el set local por el dump de producción.
- [ ] **Commit pendiente** (fix VIP + delay configurable + proxy nginx + tooling local `dev-*.sh` +
      fix FAQs/matcher + V10 + lunes festivos/V11 + VIP único por día/V12; ver `git status`)
- [ ] `GROQ_API_KEY` en local si la demo se hace sin VPS (si no, la IA contesta el fallback genérico)
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
