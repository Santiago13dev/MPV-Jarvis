package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.domain.enums.ReceiptStatus;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationReceiptJpaRepository extends JpaRepository<ReservationReceiptEntity, UUID> {

    List<ReservationReceiptEntity> findByReservationIdOrderByUploadedAtDesc(UUID reservationId);

    List<ReservationReceiptEntity> findByReservationIdAndStatus(UUID reservationId, ReceiptStatus status);

    Optional<ReservationReceiptEntity> findByWaMessageId(String waMessageId);
}
