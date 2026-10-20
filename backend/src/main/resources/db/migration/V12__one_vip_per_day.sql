-- El VIP es un único espacio físico: solo puede haber una reserva VIP activa por día,
-- sin importar la franja horaria. Antes se permitía una por fecha + hora.
--
-- La regla se aplica en la aplicación con un lock por día (ReservationService) y, como
-- red de seguridad, con un índice único parcial. Si en producción ya existieran dos VIP
-- activos el mismo día (datos creados con la regla anterior), el índice no se crea para
-- no romper el despliegue; la aplicación seguirá impidiendo reservas VIP nuevas duplicadas.

DROP INDEX IF EXISTS uq_reservations_vip_slot;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM reservations
        WHERE reservation_type = 'VIP'
          AND status IN ('PENDING', 'PAYMENT_PENDING', 'PAYMENT_RECEIPT_SENT', 'CONFIRMED')
        GROUP BY slot_date
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'Se omitió uq_reservations_vip_date: existen reservas VIP activas duplicadas por día.';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_reservations_vip_date
            ON reservations(slot_date)
            WHERE reservation_type = 'VIP'
              AND status IN ('PENDING', 'PAYMENT_PENDING', 'PAYMENT_RECEIPT_SENT', 'CONFIRMED');
    END IF;
END $$;

-- Índice de apoyo para consultar la ocupación VIP del día.
CREATE INDEX IF NOT EXISTS idx_reservations_vip_date
    ON reservations(slot_date)
    WHERE reservation_type = 'VIP'
      AND status IN ('PENDING', 'PAYMENT_PENDING', 'PAYMENT_RECEIPT_SENT', 'CONFIRMED');
