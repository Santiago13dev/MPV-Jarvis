package com.whatsappmvp.application.reservation;

import java.util.UUID;

/**
 * Evento de dominio publicado por ReservationService. Se atiende DESPUÉS del commit
 * (ReservationNotificationService) para que jamás se notifique algo que luego hizo rollback.
 */
public record ReservationNotificationEvent(UUID reservationId, Type type, String note) {

    public enum Type {
        /** Un asesor validó el pago → avisar al cliente. */
        CONFIRMED,
        /** Un asesor rechazó el comprobante → pedir uno nuevo. */
        RECEIPT_REJECTED,
        /** El hold venció → avisar al cliente. */
        EXPIRED,
        /** Llegó un comprobante → avisar al administrador. */
        RECEIPT_RECEIVED,
        /** Un administrador canceló la reserva → avisar al cliente. */
        CANCELLED_BY_ADMIN
    }
}
