-- El restaurante abre sábados y domingos; además, los lunes que sean festivos en Colombia.
-- La regla es configurable desde el dashboard. Por defecto queda activa para no perder la
-- información oficial del negocio ("también lunes festivos").
ALTER TABLE restaurant_settings
    ADD COLUMN IF NOT EXISTS open_on_holiday_mondays BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN restaurant_settings.open_on_holiday_mondays IS
    'Si TRUE, el motor abre los lunes festivos (calendario Colombia) aunque operating_days no incluya MONDAY.';
