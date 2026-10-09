package com.whatsappmvp.adapter.dto.request;

import com.whatsappmvp.domain.enums.ReservationType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** PATCH /api/reservations/{id}: campos null = sin cambio. */
@Data
public class ReservationUpdateRequest {
    private LocalDate date;
    private LocalTime time;

    @Min(value = 1, message = "El número de personas debe ser mayor a 0")
    @Max(value = 1000, message = "Número de personas no válido")
    private Integer partySize;

    private ReservationType reservationType;
}
