package com.whatsappmvp.application.service;

import com.whatsappmvp.infrastructure.persistence.entity.BusinessConfigEntity;
import com.whatsappmvp.infrastructure.persistence.entity.MessageEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.BusinessConfigJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.MessageJpaRepository;
import com.whatsappmvp.infrastructure.client.OpenAIServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final BusinessConfigJpaRepository configRepository;
    private final MessageJpaRepository messageRepository;

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
     * Construye el system prompt dinámicamente desde la configuración del negocio.
     *
     * El prompt incluye:
     * - Nombre y tipo del negocio
     * - Rol del asistente
     * - Instrucciones de comportamiento
     * - Limitaciones (no inventar precios, escalar si no sabe)
     */
    private String buildSystemPrompt() {
        BusinessConfigEntity config = configRepository.findFirstByOrderByCreatedAtAsc()
                .orElse(null);

        if (config == null) {
            return getDefaultPrompt();
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Eres el asistente virtual de ").append(config.getBusinessName()).append(".\n");

        if (config.getBusinessType() != null && !config.getBusinessType().isBlank()) {
            prompt.append("El negocio es: ").append(config.getBusinessType()).append(".\n");
        }

        prompt.append("\n## TU ROL\n");
        prompt.append("Ayudas a los clientes respondiendo sus preguntas de manera amable, ");
        prompt.append("clara y profesional. Representas la imagen del negocio.\n");

        prompt.append("\n## REGLAS IMPORTANTES\n");
        prompt.append("1. Responde SIEMPRE en español a menos que el cliente escriba en otro idioma.\n");
        prompt.append("2. Sé amable, empático y usa emojis ocasionalmente para un tono cálido. 😊\n");
        prompt.append("3. Si no sabes la respuesta con certeza, di: 'Para darte información más precisa, ");
        prompt.append("voy a conectarte con un asesor.' NO inventes información.\n");
        prompt.append("4. No compartas información de competidores.\n");
        prompt.append("5. Mantén las respuestas concisas (máximo 3-4 párrafos).\n");
        prompt.append("6. Si el cliente está molesto o el tema es delicado, deriva a un asesor humano.\n");

        prompt.append("\n## INFORMACIÓN DEL NEGOCIO\n");
        // Aquí se puede expandir con más campos de la DB (servicios, precios, etc.)
        // cuando implementemos el BotPrompts con texto configurable completo

        return prompt.toString();
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
