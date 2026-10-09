package com.whatsappmvp.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida de una reserva.
 *
 * PENDING              → creada, aún sin instrucciones de pago entregadas (p. ej. creación manual)
 * PAYMENT_PENDING      → cupo retenido (hold), esperando pago del cliente
 * PAYMENT_RECEIPT_SENT → el cliente envió comprobante, esperando validación del asesor
 * CONFIRMED            → pago validado por un asesor
 * CANCELLED            → cancelada (cliente o administrador)
 * EXPIRED              → el hold venció sin completar el proceso
 */
public enum ReservationStatus {
    PENDING,
    PAYMENT_PENDING,
    PAYMENT_RECEIPT_SENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    /** Estados que consumen cupo. */
    public static final Set<ReservationStatus> ACTIVE =
            EnumSet.of(PENDING, PAYMENT_PENDING, PAYMENT_RECEIPT_SENT, CONFIRMED);

    /** Estados con vencimiento (hold). */
    public static final Set<ReservationStatus> HOLDS = EnumSet.of(PENDING, PAYMENT_PENDING);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED;
    }

    /** Transiciones permitidas (ver ReservationService). */
    public boolean canTransitionTo(ReservationStatus target) {
        return switch (this) {
            case PENDING -> target == PAYMENT_PENDING || target == PAYMENT_RECEIPT_SENT
                    || target == CONFIRMED || target == CANCELLED || target == EXPIRED;
            case PAYMENT_PENDING -> target == PAYMENT_RECEIPT_SENT || target == CONFIRMED
                    || target == CANCELLED || target == EXPIRED;
            case PAYMENT_RECEIPT_SENT -> target == CONFIRMED || target == PAYMENT_PENDING
                    || target == CANCELLED;
            case CONFIRMED -> target == CANCELLED;
            case CANCELLED, EXPIRED -> false;
        };
    }
}
