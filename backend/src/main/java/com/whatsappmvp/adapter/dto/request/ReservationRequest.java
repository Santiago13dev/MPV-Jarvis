package com.whatsappmvp.adapter.dto.request;

import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** Creación manual de reserva (dashboard / API). Pasa por el mismo ReservationService que WhatsApp. */
@Data
public class ReservationRequest {

    @NotBlank(message = "El nombre del cliente es obligatorio")
    private String customerName;

    @NotBlank(message = "El teléfono es obligatorio")
    private String phoneNumber;

    private String receiptPhone;

    /** NORMAL por defecto. */
    private ReservationType reservationType;

    @NotNull(message = "La fecha es obligatoria")
    private LocalDate date;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime time;

    @NotNull(message = "El número de personas es obligatorio")
    @Min(value = 1, message = "El número de personas debe ser mayor a 0")
    @Max(value = 1000, message = "Número de personas no válido")
    private Integer partySize;

    private String notes;

    /** Estado inicial: PENDING, PAYMENT_PENDING (default) o CONFIRMED (ya pagó, p. ej. efectivo). */
    private ReservationStatus status;
}
