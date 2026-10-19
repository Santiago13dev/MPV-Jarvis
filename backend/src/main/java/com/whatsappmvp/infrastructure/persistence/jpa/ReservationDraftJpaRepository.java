package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.ReservationDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationDraftJpaRepository extends JpaRepository<ReservationDraftEntity, UUID> {

    Optional<ReservationDraftEntity> findByConversationId(UUID conversationId);

    void deleteByConversationId(UUID conversationId);

    /** Borra los borradores vencidos: esas conversaciones ya no vuelven a escribir. */
    @Modifying
    @Transactional
    int deleteByUpdatedAtBefore(LocalDateTime cutoff);
}
