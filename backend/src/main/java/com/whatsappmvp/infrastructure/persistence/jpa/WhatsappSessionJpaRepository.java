package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.WhatsappSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsappSessionJpaRepository extends JpaRepository<WhatsappSessionEntity, UUID> {
    Optional<WhatsappSessionEntity> findBySessionName(String sessionName);
}
