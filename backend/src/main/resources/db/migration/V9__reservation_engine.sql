-- ============================================================================
-- V9: Reservation Engine
--   * Configuración del restaurante (singleton) + reglas de capacidad + bloqueos
--   * reservations: tipo, franja, precios, hold, idempotencia, número, trazabilidad
--   * Auditoría, comprobantes de pago y borradores de conversación
--   * Migración NO destructiva de los datos existentes
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS reservation_number_seq START WITH 1001 INCREMENT BY 1;

-- ── Configuración (una sola fila, id = 1) ──────────────────────────────────
CREATE TABLE IF NOT EXISTS restaurant_settings (
    id                            SMALLINT PRIMARY KEY DEFAULT 1,
    normal_price                  NUMERIC(12, 2) NOT NULL DEFAULT 40000,
    vip_price                     NUMERIC(12, 2) NOT NULL DEFAULT 100000,
    vip_capacity                  INT            NOT NULL DEFAULT 20,
    operating_days                VARCHAR(100)   NOT NULL DEFAULT 'SATURDAY,SUNDAY',
    reservation_start_time        TIME           NOT NULL DEFAULT '11:30',
    -- Valor provisional: última franja que ya manejaba el negocio (13:00). Editable.
    reservation_end_time          TIME           NOT NULL DEFAULT '13:00',
    slot_interval_minutes         INT            NOT NULL DEFAULT 30,
    -- Valor provisional: ajustar en el dashboard.
    default_slot_capacity         INT            NOT NULL DEFAULT 50,
    max_party_size                INT            NOT NULL DEFAULT 40,
    hold_minutes                  INT            NOT NULL DEFAULT 60,
    vip_consumes_normal_capacity  BOOLEAN        NOT NULL DEFAULT FALSE,
    payment_instructions          TEXT,
    updated_at                    TIMESTAMP DEFAULT NOW(),
    CONSTRAINT chk_settings_singleton CHECK (id = 1),
    CONSTRAINT chk_settings_prices    CHECK (normal_price >= 0 AND vip_price >= 0),
    CONSTRAINT chk_settings_capacity  CHECK (vip_capacity > 0 AND default_slot_capacity >= 0 AND max_party_size > 0),
    CONSTRAINT chk_settings_interval  CHECK (slot_interval_minutes >= 5),
    CONSTRAINT chk_settings_hold      CHECK (hold_minutes > 0),
    CONSTRAINT chk_settings_hours     CHECK (reservation_end_time >= reservation_start_time)
);

INSERT INTO restaurant_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- Capacidad por día y/o franja. NULL = comodín. Gana la regla más específica.
CREATE TABLE IF NOT EXISTS slot_capacity_rules (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    day_of_week  VARCHAR(10),
    slot_time    TIME,
    capacity     INT NOT NULL CHECK (capacity >= 0),
    created_at   TIMESTAMP DEFAULT NOW()
);

-- Bloqueo de fechas completas (slot_time NULL) o de una franja puntual.
CREATE TABLE IF NOT EXISTS reservation_blocks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    block_date  DATE NOT NULL,
    slot_time   TIME,
    reason      VARCHAR(255),
    created_at  TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_reservation_blocks_date ON reservation_blocks(block_date);

-- ── reservations ───────────────────────────────────────────────────────────
ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS reservation_type            VARCHAR(10) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN IF NOT EXISTS slot_date                   DATE,
    ADD COLUMN IF NOT EXISTS slot_time                   TIME,
    ADD COLUMN IF NOT EXISTS unit_price                  NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS total_price                 NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS receipt_phone               VARCHAR(50),
    ADD COLUMN IF NOT EXISTS hold_expires_at             TIMESTAMP,
    ADD COLUMN IF NOT EXISTS idempotency_key             VARCHAR(120),
    ADD COLUMN IF NOT EXISTS reservation_number          BIGINT,
    ADD COLUMN IF NOT EXISTS confirmed_at                TIMESTAMP,
    ADD COLUMN IF NOT EXISTS confirmed_by                VARCHAR(100),
    ADD COLUMN IF NOT EXISTS cancelled_at                TIMESTAMP,
    ADD COLUMN IF NOT EXISTS cancelled_by                VARCHAR(100),
    ADD COLUMN IF NOT EXISTS cancel_reason               TEXT,
    ADD COLUMN IF NOT EXISTS payment_receipt_uploaded_at TIMESTAMP;

ALTER TABLE reservations ALTER COLUMN amount TYPE NUMERIC(12, 2);

-- Datos legacy: una cantidad de personas <= 0 no es válida (chk_reservations_party). Se deja en NULL
-- (cantidad desconocida) en vez de reventar la migración al crear el CHECK.
UPDATE reservations SET people_count = NULL WHERE people_count IS NOT NULL AND people_count <= 0;

-- Backfill: franja y precios a partir de los datos existentes
UPDATE reservations
   SET slot_date   = reservation_date::date,
       slot_time   = reservation_date::time,
       unit_price  = COALESCE(amount, 0),
       total_price = COALESCE(amount, 0)
 WHERE slot_date IS NULL;

UPDATE reservations SET reservation_number = nextval('reservation_number_seq')
 WHERE reservation_number IS NULL;

-- Estados: español → nuevo modelo
ALTER TABLE reservations ALTER COLUMN status TYPE VARCHAR(30);

-- Estados legacy desconocidos romperían chk_reservations_status. Se pasan a CANCELLED conservando el valor
-- original en cancel_reason: no consumen cupos y no se pierde el dato. (Los conocidos se mapean más abajo.)
UPDATE reservations
   SET cancel_reason = COALESCE(cancel_reason, 'Estado anterior no reconocido: ' || status),
       status = 'CANCELLED'
 WHERE status NOT IN ('PENDIENTE', 'CONFIRMADA', 'CANCELADA', 'PENDING', 'PAYMENT_PENDING',
                      'PAYMENT_RECEIPT_SENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED');
UPDATE reservations SET status = CASE status
        WHEN 'PENDIENTE'  THEN 'PAYMENT_PENDING'
        WHEN 'CONFIRMADA' THEN 'CONFIRMED'
        WHEN 'CANCELADA'  THEN 'CANCELLED'
        ELSE status END;
UPDATE reservations SET confirmed_at = COALESCE(updated_at, created_at)
 WHERE status = 'CONFIRMED' AND confirmed_at IS NULL;
UPDATE reservations SET cancelled_at = COALESCE(updated_at, created_at), cancelled_by = 'LEGACY'
 WHERE status = 'CANCELLED' AND cancelled_at IS NULL;
ALTER TABLE reservations ALTER COLUMN status SET DEFAULT 'PENDING';

ALTER TABLE reservations ALTER COLUMN slot_date          SET NOT NULL;
ALTER TABLE reservations ALTER COLUMN slot_time          SET NOT NULL;
ALTER TABLE reservations ALTER COLUMN reservation_number SET NOT NULL;
ALTER TABLE reservations ALTER COLUMN reservation_number SET DEFAULT nextval('reservation_number_seq');

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_type   CHECK (reservation_type IN ('NORMAL', 'VIP')),
    ADD CONSTRAINT chk_reservations_status CHECK (status IN
        ('PENDING', 'PAYMENT_PENDING', 'PAYMENT_RECEIPT_SENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    ADD CONSTRAINT chk_reservations_party  CHECK (people_count IS NULL OR people_count > 0);

CREATE UNIQUE INDEX IF NOT EXISTS uq_reservations_number ON reservations(reservation_number);

-- Idempotencia: la misma operación jamás crea dos reservas
CREATE UNIQUE INDEX IF NOT EXISTS uq_reservations_idempotency_key
    ON reservations(idempotency_key) WHERE idempotency_key IS NOT NULL;

-- VIP = recurso exclusivo: una sola reserva activa por fecha + franja (red de seguridad en BD)
CREATE UNIQUE INDEX IF NOT EXISTS uq_reservations_vip_slot
    ON reservations(slot_date, slot_time)
 WHERE reservation_type = 'VIP'
   AND status IN ('PENDING', 'PAYMENT_PENDING', 'PAYMENT_RECEIPT_SENT', 'CONFIRMED');

CREATE INDEX IF NOT EXISTS idx_reservations_slot  ON reservations(slot_date, slot_time, status);
CREATE INDEX IF NOT EXISTS idx_reservations_phone ON reservations(phone_number, status);
CREATE INDEX IF NOT EXISTS idx_reservations_hold  ON reservations(hold_expires_at)
 WHERE status IN ('PENDING', 'PAYMENT_PENDING');

-- ── Auditoría ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS reservation_audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id  UUID NOT NULL,
    action          VARCHAR(60)  NOT NULL,
    actor           VARCHAR(150),
    actor_type      VARCHAR(20)  NOT NULL,
    previous_state  VARCHAR(30),
    new_state       VARCHAR(30),
    metadata        TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_res_audit_reservation ON reservation_audit_log(reservation_id, created_at);

-- ── Comprobantes de pago ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS reservation_receipts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id  UUID NOT NULL REFERENCES reservations(id),
    file_path       VARCHAR(500) NOT NULL,
    mime_type       VARCHAR(100),
    wa_message_id   VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    uploaded_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_by     VARCHAR(100),
    reviewed_at     TIMESTAMP,
    review_note     TEXT,
    CONSTRAINT chk_receipt_status CHECK (status IN ('RECEIVED', 'ACCEPTED', 'REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_res_receipts_reservation ON reservation_receipts(reservation_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_res_receipts_wa_message
    ON reservation_receipts(wa_message_id) WHERE wa_message_id IS NOT NULL;

-- ── Borradores de reserva (estado conversacional persistente) ──────────────
CREATE TABLE IF NOT EXISTS reservation_drafts (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id        UUID NOT NULL UNIQUE REFERENCES conversations(id) ON DELETE CASCADE,
    mode                   VARCHAR(10) NOT NULL DEFAULT 'CREATE',
    awaiting               VARCHAR(30),
    reservation_type       VARCHAR(10),
    party_size             INT,
    slot_date              DATE,
    slot_time              TIME,
    customer_name          VARCHAR(255),
    receipt_phone          VARCHAR(50),
    target_reservation_id  UUID,
    offered_times          VARCHAR(255),
    created_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_draft_mode CHECK (mode IN ('CREATE', 'MODIFY', 'CANCEL'))
);
