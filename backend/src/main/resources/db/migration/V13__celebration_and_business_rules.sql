-- V13 — Reglas de negocio de la demo:
--   * reserva normal: base gratis (solo guardar espacio)
--   * paquete de celebración (postre + decoración): 40.000 para 1 homenajeado, +10.000 por adicional
--   * VIP: máximo 15 personas
--   * reservas normales: sin máximo por reserva (el cupo de la franja sigue siendo el límite real)
--   * pago por Llave al número de Roberto
--   * hold interno de 24 h; al cliente no se le muestra una cuenta regresiva
--   * motivo de celebración y número de homenajeados en borrador y reserva

ALTER TABLE restaurant_settings
    ADD COLUMN IF NOT EXISTS celebration_price NUMERIC(12, 2) NOT NULL DEFAULT 40000,
    ADD COLUMN IF NOT EXISTS extra_honoree_price NUMERIC(12, 2) NOT NULL DEFAULT 10000;

ALTER TABLE reservation_drafts
    ADD COLUMN IF NOT EXISTS celebration_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS honorees INTEGER;

ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS celebration_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS honorees INTEGER;

-- max_party_size = 0 significa "sin límite por reserva"; el CHECK original exigía > 0.
ALTER TABLE restaurant_settings DROP CONSTRAINT IF EXISTS chk_settings_capacity;
ALTER TABLE restaurant_settings
    ADD CONSTRAINT chk_settings_capacity
    CHECK (vip_capacity > 0 AND default_slot_capacity >= 0 AND max_party_size >= 0);

UPDATE restaurant_settings
SET normal_price = 0,
    vip_capacity = 15,
    max_party_size = 0,
    hold_minutes = 1440
WHERE id = 1;

UPDATE restaurant_settings
SET payment_instructions = E'💳 Llave: 314 261 7362 (Roberto)\nTambién puedes pagar en efectivo o por transferencia.'
WHERE id = 1
  AND (payment_instructions IS NULL OR btrim(payment_instructions) = '');
