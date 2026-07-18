package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.BusinessHoursEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BusinessHoursJpaRepository extends JpaRepository<BusinessHoursEntity, UUID> {
    List<BusinessHoursEntity> findByBusinessConfigIdOrderByDayOfWeekAsc(UUID configId);
}
