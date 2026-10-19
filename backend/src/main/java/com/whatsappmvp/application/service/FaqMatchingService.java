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
import java.util.function.Predicate;

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
        return findMatch(userMessage, faq -> false, 2);
    }

    /**
     * Igual que {@link #findMatch(String)} pero ignorando las FAQs que cumplan el filtro.
     * Se usa cuando el mensaje lo está tratando otro motor (p. ej. la reserva) y solo queremos
     * la FAQ de la pregunta lateral ("reserva para 10 y voy con mi perro" → FAQ de mascotas).
     *
     * @param minScore score mínimo: el lado de la reserva exige 3 para no pegarle al mensaje
     *                 una FAQ débil (p. ej. la de horarios cuando solo dice "mediodía").
     */
    @Transactional
    public Optional<String> findMatch(String userMessage, Predicate<FaqItemEntity> exclude, int minScore) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        String normalizedMessage = normalize(userMessage);
        List<FaqItemEntity> activeFaqs = faqRepository.findByIsActiveTrueOrderByPriorityDesc();

        FaqItemEntity bestMatch = null;
        int bestScore = 0;

        for (FaqItemEntity faq : activeFaqs) {
            if (exclude.test(faq)) continue;
            int score = calculateMatchScore(normalizedMessage, faq);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = faq;
            }
        }

        // Umbral mínimo de score para considerar un match válido
        if (bestScore >= minScore && bestMatch != null) {
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
            if (normalizedKeyword.isEmpty()) continue;

            String plural = normalizedKeyword + "s";
            String singular = normalizedKeyword.length() > 3 && normalizedKeyword.endsWith("s")
                    ? normalizedKeyword.substring(0, normalizedKeyword.length() - 1)
                    : null;

            // Coincidencia de palabra completa — mayor peso.
            // Se acepta también el plural/singular de la palabra: "domicilio" vs "¿hacen domicilios?".
            // Las formas derivadas SIEMPRE con límite de palabra: si no, el singular "dia" (3 letras)
            // entraba por substring dentro de "mediodía" y la FAQ de horarios se pegaba a cualquier mensaje.
            if (containsWholeWord(normalizedMessage, normalizedKeyword)
                    || matchesWholeWord(normalizedMessage, plural)
                    || (singular != null && matchesWholeWord(normalizedMessage, singular))) {
                score += 3;
            }
            // Misma raíz con distinta terminación ("reserva" vs "¿cómo reservo?") — peso medio
            else if (normalizedKeyword.length() >= 5
                    && containsWordWithPrefix(normalizedMessage, normalizedKeyword.substring(0, 5))) {
                score += 2;
            }
            // Coincidencia parcial — peso menor
            else if (normalizedMessage.contains(normalizedKeyword)
                    || normalizedMessage.contains(plural)
                    || (singular != null && normalizedMessage.contains(singular))) {
                score += 1;
            }
        }

        return score;
    }

    /** ¿Existe en el mensaje una palabra que empiece por el prefijo (mínimo 5 letras)? */
    private boolean containsWordWithPrefix(String text, String prefix) {
        for (String word : text.split("\\s+")) {
            if (word.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Verifica si el texto contiene la keyword como palabra completa (no substring).
     * Ej: "precio" coincide en "qué precio tiene" pero no en "apreciado".
     * Las palabras de 3 letras o menos se buscan por simple contención (p. ej. "red").
     */
    private boolean containsWholeWord(String text, String keyword) {
        if (keyword.length() <= 3) {
            return text.contains(keyword);
        }
        return matchesWholeWord(text, keyword);
    }

    /** Igual que {@link #containsWholeWord} pero sin el atajo de palabras cortas. */
    private boolean matchesWholeWord(String text, String keyword) {
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
