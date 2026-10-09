package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.ReservationAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReservationAuditJpaRepository extends JpaRepository<ReservationAuditEntity, UUID> {

    List<ReservationAuditEntity> findByReservationIdOrderByCreatedAtAsc(UUID reservationId);
}
