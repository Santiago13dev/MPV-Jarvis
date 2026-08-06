package com.whatsappmvp.application.service;

import com.whatsappmvp.infrastructure.persistence.entity.MessageEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.MessageJpaRepository;
import com.whatsappmvp.infrastructure.client.OpenAIServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Servicio de IA — fallback del motor híbrido.
 *
 * Construye dinámicamente el system prompt desde la configuración del negocio en BD.
 * Incluye los últimos N mensajes de la conversación como contexto.
 * Registra los tokens utilizados para control de costos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIService {

    private final OpenAIServiceClient openAIClient;
    private final MessageJpaRepository messageRepository;
    private final ResourceLoader resourceLoader;

    // Máximo de mensajes históricos a incluir como contexto
    private static final int MAX_HISTORY_MESSAGES = 6;

    /**
     * Genera una respuesta usando OpenAI con contexto del negocio y la conversación.
     *
     * @param conversationId ID de la conversación para obtener historial
     * @param userMessage    Mensaje actual del usuario
     * @return AIResult con la respuesta y tokens usados
     */
    @Transactional(readOnly = true)
    public OpenAIServiceClient.OpenAIResult generateResponse(UUID conversationId, String userMessage) {
        String systemPrompt = buildSystemPrompt();
        List<Map<String, String>> history = buildConversationHistory(conversationId);

        log.info("[AI] Generating response for conversation: {}", conversationId);
        return openAIClient.complete(systemPrompt, history, userMessage);
    }

    /**
     * Construye el system prompt leyendo desde el archivo system-prompt.txt.
     * Si hay error de lectura, usa el prompt por defecto.
     */
    private String buildSystemPrompt() {
        try {
            Resource resource = resourceLoader.getResource("classpath:system-prompt.txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[AI] Error reading system-prompt.txt, using default prompt", e);
            return getDefaultPrompt();
        }
    }

    /**
     * Obtiene el historial reciente de la conversación y lo convierte
     * al formato de messages que espera OpenAI.
     */
    private List<Map<String, String>> buildConversationHistory(UUID conversationId) {
        List<Map<String, String>> history = new ArrayList<>();

        if (conversationId == null) return history;

        var messages = messageRepository.findByConversationIdOrderBySentAtAsc(
                conversationId, PageRequest.of(0, MAX_HISTORY_MESSAGES));

        for (MessageEntity msg : messages.getContent()) {
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;

            String role = switch (msg.getDirection()) {
                case INBOUND -> "user";
                case OUTBOUND -> "assistant";
            };

            Map<String, String> entry = new HashMap<>();
            entry.put("role", role);
            entry.put("content", msg.getContent());
            history.add(entry);
        }

        return history;
    }

    private String getDefaultPrompt() {
        return """
                Eres un asistente virtual amable y profesional.
                Ayuda a los clientes con sus preguntas de manera clara y concisa.
                Si no sabes la respuesta, indica que un asesor los contactará pronto.
                Responde siempre en español.
                """;
    }
}
