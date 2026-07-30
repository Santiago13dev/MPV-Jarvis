package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.MessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageJpaRepository extends JpaRepository<MessageEntity, UUID> {

    @Query("SELECT m FROM MessageEntity m JOIN FETCH m.conversation WHERE m.conversation.id = :conversationId ORDER BY m.sentAt ASC")
    Page<MessageEntity> findByConversationIdOrderBySentAtAsc(@Param("conversationId") UUID conversationId, Pageable pageable);

    Optional<MessageEntity> findByWaMessageId(String waMessageId);

    boolean existsByWaMessageId(String waMessageId);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE DATE(m.sentAt) = :date AND m.direction = 'INBOUND'")
    long countInboundByDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE DATE(m.sentAt) = :date AND m.direction = 'OUTBOUND'")
    long countOutboundByDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(m.aiTokensUsed), 0) FROM MessageEntity m WHERE DATE(m.sentAt) = :date")
    long sumAiTokensByDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE DATE(m.sentAt) = :date AND m.processedBy = 'AI' AND m.direction = 'OUTBOUND'")
    long countAiCallsByDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE DATE(m.sentAt) = :date AND m.processedBy = 'FAQ' AND m.direction = 'OUTBOUND'")
    long countFaqByDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE DATE(m.sentAt) = :date AND m.processedBy = 'KEYWORD' AND m.direction = 'OUTBOUND'")
    long countKeywordByDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE DATE(m.sentAt) = :date AND m.processedBy = 'HUMAN' AND m.direction = 'OUTBOUND'")
    long countHumanByDate(@Param("date") LocalDate date);
}
