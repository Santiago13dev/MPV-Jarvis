-- V15 — Encuesta de celebración con salida de emergencia.
-- Si el cliente responde dos veces algo que no es un motivo, se asume
-- "solo guardar el espacio" para que la conversación no quede atrapada.
ALTER TABLE reservation_drafts
    ADD COLUMN IF NOT EXISTS survey_attempts INTEGER NOT NULL DEFAULT 0;
