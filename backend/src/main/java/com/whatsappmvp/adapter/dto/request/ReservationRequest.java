package com.whatsappmvp.adapter.dto.request;

import com.whatsappmvp.domain.enums.ReservationStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ReservationRequest {
    private String customerName;
    private String phoneNumber;
    private LocalDateTime reservationDate;
    private BigDecimal amount;
    private ReservationStatus status;
    private String notes;
    private Integer peopleCount;
}
