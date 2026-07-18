-- V3: Tabla de reservaciones (feature nueva)

CREATE TABLE IF NOT EXISTS reservations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_name     VARCHAR(255) NOT NULL,
    phone_number      VARCHAR(50) NOT NULL,
    reservation_date  TIMESTAMP NOT NULL,
    amount            NUMERIC(10, 2),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    notes             TEXT,
    conversation_id   UUID REFERENCES conversations(id),
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reservations_status ON reservations(status);
CREATE INDEX IF NOT EXISTS idx_reservations_date   ON reservations(reservation_date DESC);
