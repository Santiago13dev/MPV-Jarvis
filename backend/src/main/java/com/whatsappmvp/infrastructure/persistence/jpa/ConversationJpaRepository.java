package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.domain.enums.ConversationStatus;
import com.whatsappmvp.infrastructure.persistence.entity.ConversationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationJpaRepository extends JpaRepository<ConversationEntity, UUID> {

    Optional<ConversationEntity> findByContactId(UUID contactId);

    @Query("SELECT c FROM ConversationEntity c JOIN FETCH c.contact ORDER BY c.lastMessageAt DESC")
    Page<ConversationEntity> findAllByOrderByLastMessageAtDesc(Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c JOIN FETCH c.contact WHERE c.status = :status ORDER BY c.lastMessageAt DESC")
    Page<ConversationEntity> findByStatusOrderByLastMessageAtDesc(
            @Param("status") ConversationStatus status, Pageable pageable);

    long countByStatus(ConversationStatus status);

    @Query("SELECT c FROM ConversationEntity c JOIN FETCH c.contact ct " +
           "WHERE LOWER(ct.phone) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(ct.displayName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "ORDER BY c.lastMessageAt DESC")
    Page<ConversationEntity> searchByContactPhoneOrName(
            @Param("query") String query, Pageable pageable);
}
