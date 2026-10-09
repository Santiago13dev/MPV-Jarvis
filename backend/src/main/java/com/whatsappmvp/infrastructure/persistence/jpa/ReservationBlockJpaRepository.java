package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.ReservationBlockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReservationBlockJpaRepository extends JpaRepository<ReservationBlockEntity, UUID> {

    List<ReservationBlockEntity> findByBlockDate(LocalDate blockDate);

    List<ReservationBlockEntity> findByBlockDateGreaterThanEqualOrderByBlockDateAsc(LocalDate from);
}
