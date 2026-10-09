package com.whatsappmvp.application.reservation;

import com.whatsappmvp.domain.enums.ReservationType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Resultados estructurados del motor de disponibilidad (lo que recibe la IA / el dashboard). */
public final class AvailabilityModels {

    private AvailabilityModels() {}

    public enum Reason {
        OK,
        CLOSED_DAY,
        PAST_DATE,
        BLOCKED,
        INVALID_SLOT,
        SLOT_PASSED,
        INVALID_PARTY_SIZE,
        PARTY_TOO_LARGE,
        INSUFFICIENT_CAPACITY,
        VIP_TAKEN,
        VIP_CAPACITY_EXCEEDED
    }

    public record Pool(int capacity, int reserved, int available) {}

    public record PriceQuote(ReservationType type, int partySize, BigDecimal unitPrice,
                             BigDecimal totalPrice, boolean perPerson) {}

    /** Una franja de un día. */
    public record SlotAvailability(LocalTime time, boolean blocked, boolean passed, Pool normal, Pool vip) {}

    /** Respuesta de GET /availability (date + time). */
    public record AvailabilityResult(LocalDate date, LocalTime time, boolean open, Reason closedReason,
                                     Pool normal, Pool vip) {}

    /** Resultado de validar una solicitud concreta (tipo, personas, fecha, hora). */
    public record RequestCheck(boolean available, Reason reason, LocalDate date, LocalTime time,
                               ReservationType type, int partySize, Pool pool, PriceQuote price,
                               List<LocalTime> alternatives, List<LocalDate> nextOperatingDays) {}
}
