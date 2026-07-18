package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.FaqItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FaqJpaRepository extends JpaRepository<FaqItemEntity, UUID> {
    List<FaqItemEntity> findByIsActiveTrueOrderByPriorityDesc();
}
