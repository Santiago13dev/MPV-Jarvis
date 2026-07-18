package com.whatsappmvp.application.service;

import com.whatsappmvp.infrastructure.persistence.entity.KeywordRuleEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.KeywordRuleJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Motor de matching de reglas por keywords.
 *
 * Modos de matching configurables por regla:
 * - ANY: el mensaje contiene AL MENOS UNA keyword de la lista
 * - ALL: el mensaje contiene TODAS las keywords de la lista
 * - EXACT: el mensaje es EXACTAMENTE una de las keywords
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordMatchingService {

    private final KeywordRuleJpaRepository keywordRuleRepository;

    /**
     * Busca la primera regla activa que haga match con el mensaje.
     * Las reglas están ordenadas por prioridad (descendente).
     * @return Optional con la respuesta de la regla, o vacío si no hay match.
     */
    @Transactional(readOnly = true)
    public Optional<String> findMatch(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        String normalizedMessage = normalize(userMessage);
        List<KeywordRuleEntity> activeRules = keywordRuleRepository
                .findByIsActiveTrueOrderByPriorityDesc();

        for (KeywordRuleEntity rule : activeRules) {
            if (matches(normalizedMessage, rule)) {
                log.info("[Keyword] Rule matched: '{}' (mode={})", rule.getName(), rule.getMatchMode());
                return Optional.of(rule.getResponse());
            }
        }

        return Optional.empty();
    }

    private boolean matches(String normalizedMessage, KeywordRuleEntity rule) {
        if (rule.getKeywords() == null || rule.getKeywords().length == 0) {
            return false;
        }

        String[] normalizedKeywords = Arrays.stream(rule.getKeywords())
                .map(this::normalize)
                .toArray(String[]::new);

        return switch (rule.getMatchMode().toUpperCase()) {
            case "ALL" -> Arrays.stream(normalizedKeywords)
                    .allMatch(normalizedMessage::contains);

            case "EXACT" -> Arrays.stream(normalizedKeywords)
                    .anyMatch(k -> normalizedMessage.equals(k));

            default -> // ANY
                Arrays.stream(normalizedKeywords)
                    .anyMatch(normalizedMessage::contains);
        };
    }

    private String normalize(String text) {
        return java.text.Normalizer
                .normalize(text.toLowerCase().trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
