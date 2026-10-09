package com.whatsappmvp.infrastructure.persistence.entity;

import com.whatsappmvp.domain.enums.ReservationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Borrador de reserva: estado conversacional persistente (uno por conversación).
 * mode CREATE → datos de una reserva nueva.
 * mode MODIFY → los campos son los NUEVOS valores (null = sin cambio) de targetReservationId.
 * mode CANCEL → targetReservationId es la reserva a cancelar.
 */
@Entity
@Table(name = "reservation_drafts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationDraftEntity {

    public enum Mode { CREATE, MODIFY, CANCEL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, unique = true)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Mode mode = Mode.CREATE;

    /** Qué dato le pidió el bot al cliente en el último turno (DATE, TIME, PARTY_SIZE, NAME, CONFIRM, ...). */
    @Column(length = 30)
    private String awaiting;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_type", length = 10)
    private ReservationType reservationType;

    @Column(name = "party_size")
    private Integer partySize;

    @Column(name = "slot_date")
    private LocalDate slotDate;

    @Column(name = "slot_time")
    private LocalTime slotTime;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "receipt_phone", length = 50)
    private String receiptPhone;

    @Column(name = "target_reservation_id")
    private UUID targetReservationId;

    /** Horas ofrecidas en el último mensaje, CSV "12:00,13:00" (para resolver "la primera"). */
    @Column(name = "offered_times", length = 255)
    private String offeredTimes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
