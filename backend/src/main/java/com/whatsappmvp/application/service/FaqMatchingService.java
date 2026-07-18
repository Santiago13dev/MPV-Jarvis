package com.whatsappmvp.application.service;

import com.whatsappmvp.infrastructure.persistence.entity.FaqItemEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.FaqJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Motor de matching de FAQs.
 *
 * Estrategia de matching (en orden de prioridad):
 * 1. Coincidencia exacta de keyword (más alta prioridad)
 * 2. Coincidencia parcial: el mensaje del usuario contiene alguna keyword
 * 3. Retorna el FAQ con mayor puntuación de coincidencia
 *
 * Para producción avanzada, este servicio puede reemplazarse con
 * embeddings semánticos (sentence-transformers o OpenAI embeddings).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqMatchingService {

    private final FaqJpaRepository faqRepository;

    /**
     * Intenta encontrar una FAQ que responda al mensaje del usuario.
     * @return Optional con la respuesta de la FAQ, o vacío si no hay match.
     */
    @Transactional
    public Optional<String> findMatch(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        String normalizedMessage = normalize(userMessage);
        List<FaqItemEntity> activeFaqs = faqRepository.findByIsActiveTrueOrderByPriorityDesc();

        FaqItemEntity bestMatch = null;
        int bestScore = 0;

        for (FaqItemEntity faq : activeFaqs) {
            int score = calculateMatchScore(normalizedMessage, faq);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = faq;
            }
        }

        // Umbral mínimo de score para considerar un match válido
        if (bestScore >= 2 && bestMatch != null) {
            log.info("[FAQ] Match found: '{}' → score={}", bestMatch.getQuestion(), bestScore);

            // Incrementar contador de uso
            bestMatch.setMatchCount(bestMatch.getMatchCount() + 1);
            faqRepository.save(bestMatch);

            return Optional.of(bestMatch.getAnswer());
        }

        log.debug("[FAQ] No match found for: '{}'", userMessage);
        return Optional.empty();
    }

    /**
     * Calcula un score de coincidencia entre el mensaje del usuario y una FAQ.
     * Score más alto = mejor match.
     */
    private int calculateMatchScore(String normalizedMessage, FaqItemEntity faq) {
        int score = 0;

        if (faq.getKeywords() == null || faq.getKeywords().length == 0) {
            return 0;
        }

        for (String keyword : faq.getKeywords()) {
            String normalizedKeyword = normalize(keyword);

            // Coincidencia exacta de palabra completa — mayor peso
            if (containsWholeWord(normalizedMessage, normalizedKeyword)) {
                score += 3;
            }
            // Coincidencia parcial — peso menor
            else if (normalizedMessage.contains(normalizedKeyword)) {
                score += 1;
            }
        }

        return score;
    }

    /**
     * Verifica si el texto contiene la keyword como palabra completa (no substring).
     * Ej: "precio" coincide en "qué precio tiene" pero no en "apreciado"
     */
    private boolean containsWholeWord(String text, String keyword) {
        if (keyword.length() <= 3) {
            // Palabras cortas: coincidencia simple para evitar falsos negativos
            return text.contains(keyword);
        }
        // Regex: límite de palabra o inicio/fin de string
        String pattern = "(^|\\s)" + java.util.regex.Pattern.quote(keyword) + "($|\\s|[?!.,])";
        return text.matches(".*" + pattern + ".*");
    }

    /**
     * Normaliza texto: minúsculas, sin acentos, sin puntuación extra.
     */
    private String normalize(String text) {
        return java.text.Normalizer
                .normalize(text.toLowerCase().trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "") // quitar acentos
                .replaceAll("[^a-z0-9\\s]", " ")                      // quitar puntuación
                .replaceAll("\\s+", " ")
                .trim();
    }
}
