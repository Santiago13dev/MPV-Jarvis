package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.BusinessConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessConfigJpaRepository extends JpaRepository<BusinessConfigEntity, UUID> {
    // Siempre habrá una sola config — tomamos la primera
    Optional<BusinessConfigEntity> findFirstByOrderByCreatedAtAsc();
}
