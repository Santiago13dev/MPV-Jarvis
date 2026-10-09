package com.whatsappmvp.application.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationAuditEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationAuditJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Auditoría de reservas. Se escribe en la MISMA transacción que el cambio de estado:
 * si el cambio hace rollback, la auditoría también (nunca queda un registro de algo que no ocurrió).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationAuditService {

    public static final String CREATED = "RESERVATION_CREATED";
    public static final String UPDATED = "RESERVATION_UPDATED";
    public static final String CANCELLED = "RESERVATION_CANCELLED";
    public static final String RECEIPT_UPLOADED = "PAYMENT_RECEIPT_UPLOADED";
    public static final String RECEIPT_REJECTED = "PAYMENT_RECEIPT_REJECTED";
    public static final String CONFIRMED = "RESERVATION_CONFIRMED";
    public static final String EXPIRED = "RESERVATION_EXPIRED";

    private final ReservationAuditJpaRepository auditRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID reservationId, String action, Actor actor,
                       ReservationStatus previous, ReservationStatus next, Map<String, Object> metadata) {
        String json = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                json = objectMapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.warn("[RESERVATION] could not serialize audit metadata: {}", e.getMessage());
            }
        }
        auditRepository.save(ReservationAuditEntity.builder()
                .reservationId(reservationId)
                .action(action)
                .actor(actor.id())
                .actorType(actor.type())
                .previousState(previous != null ? previous.name() : null)
                .newState(next != null ? next.name() : null)
                .metadata(json)
                .build());
    }

    @Transactional(readOnly = true)
    public List<ReservationAuditEntity> trail(UUID reservationId) {
        return auditRepository.findByReservationIdOrderByCreatedAtAsc(reservationId);
    }
}
