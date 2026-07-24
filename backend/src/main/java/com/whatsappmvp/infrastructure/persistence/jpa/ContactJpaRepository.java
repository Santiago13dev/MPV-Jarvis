package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.ContactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactJpaRepository extends JpaRepository<ContactEntity, UUID> {
    Optional<ContactEntity> findByPhone(String phone);
    boolean existsByPhone(String phone);

    @Query("SELECT COUNT(c) FROM ContactEntity c WHERE DATE(c.firstSeen) = :date")
    long countNewContactsByDate(@Param("date") LocalDate date);
}
