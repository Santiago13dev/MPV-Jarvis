package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.ContactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactJpaRepository extends JpaRepository<ContactEntity, UUID> {
    Optional<ContactEntity> findByPhone(String phone);
    boolean existsByPhone(String phone);
}
