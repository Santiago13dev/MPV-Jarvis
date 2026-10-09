package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.ReservationDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationDraftJpaRepository extends JpaRepository<ReservationDraftEntity, UUID> {

    Optional<ReservationDraftEntity> findByConversationId(UUID conversationId);

    void deleteByConversationId(UUID conversationId);
}
