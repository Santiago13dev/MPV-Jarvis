package com.whatsappmvp.adapter.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

/** PATCH /api/reservations/configuration: campos null = sin cambio. */
@Data
public class ReservationSettingsRequest {
    private BigDecimal normalPrice;
    private BigDecimal vipPrice;
    private Integer vipCapacity;
    private Set<DayOfWeek> operatingDays;
    private LocalTime reservationStartTime;
    private LocalTime reservationEndTime;
    private Integer slotIntervalMinutes;
    private Integer defaultSlotCapacity;
    private Integer maxPartySize;
    private Integer holdMinutes;
    private Boolean vipConsumesNormalCapacity;
    private String paymentInstructions;
}
