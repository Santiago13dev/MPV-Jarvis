package com.whatsappmvp.adapter.dto.response;

import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
public class ReservationResponse {
    private UUID id;
    private Long reservationNumber;
    private String customerName;
    private String phoneNumber;
    private String receiptPhone;
    private ReservationType reservationType;
    private LocalDate date;
    private LocalTime time;
    private Integer partySize;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private ReservationStatus status;
    private String notes;
    private LocalDateTime holdExpiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;
    private String cancelReason;
    private boolean hasReceipt;
    private LocalDateTime paymentReceiptUploadedAt;
    private LocalDateTime createdAt;

    // ── Compatibilidad con el dashboard anterior ──
    private LocalDateTime reservationDate;
    private BigDecimal amount;
    private Integer peopleCount;

    public static ReservationResponse from(ReservationEntity e) {
        return ReservationResponse.builder()
                .id(e.getId())
                .reservationNumber(e.getReservationNumber())
                .customerName(e.getCustomerName())
                .phoneNumber(e.getPhoneNumber())
                .receiptPhone(e.getReceiptPhone())
                .reservationType(e.getReservationType())
                .date(e.getSlotDate())
                .time(e.getSlotTime())
                .partySize(e.getPartySize())
                .unitPrice(e.getUnitPrice())
                .totalPrice(e.getTotalPrice())
                .status(e.getStatus())
                .notes(e.getNotes())
                .holdExpiresAt(e.getHoldExpiresAt())
                .confirmedAt(e.getConfirmedAt())
                .cancelledAt(e.getCancelledAt())
                .cancelReason(e.getCancelReason())
                .hasReceipt(e.getPaymentReceiptUploadedAt() != null)
                .paymentReceiptUploadedAt(e.getPaymentReceiptUploadedAt())
                .createdAt(e.getCreatedAt())
                .reservationDate(e.getReservationDate())
                .amount(e.getAmount())
                .peopleCount(e.getPartySize())
                .build();
    }
}
