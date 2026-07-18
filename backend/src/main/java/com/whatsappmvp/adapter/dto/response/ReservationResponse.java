package com.whatsappmvp.adapter.dto.response;

import com.whatsappmvp.domain.enums.ReservationStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ReservationResponse {
    private UUID id;
    private String customerName;
    private String phoneNumber;
    private LocalDateTime reservationDate;
    private BigDecimal amount;
    private ReservationStatus status;
    private String notes;
    private LocalDateTime createdAt;
}
