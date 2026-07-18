package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.infrastructure.persistence.entity.KeywordRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface KeywordRuleJpaRepository extends JpaRepository<KeywordRuleEntity, UUID> {
    List<KeywordRuleEntity> findByIsActiveTrueOrderByPriorityDesc();
}
