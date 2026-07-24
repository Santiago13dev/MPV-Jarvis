package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.whatsappmvp.domain.enums.ReservationStatus;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationJpaRepository extends JpaRepository<ReservationEntity, UUID> {
    long countByStatus(ReservationStatus status);

    @Query("SELECT r FROM ReservationEntity r WHERE r.phoneNumber = :phone AND r.status <> 'CANCELADA' ORDER BY r.createdAt DESC LIMIT 1")
    Optional<ReservationEntity> findLatestActiveByPhoneNumber(@Param("phone") String phone);
}
