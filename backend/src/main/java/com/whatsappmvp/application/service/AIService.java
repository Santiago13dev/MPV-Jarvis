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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    /**
     * Extrae datos de reserva de un mensaje usando LLM.
     * Retorna un mapa con los campos extraídos (null donde no se detectó nada).
     * Se usa como fallback cuando regex no extrae todos los campos requeridos.
     *
     * @param message mensaje del usuario
     * @return Map con keys: customerName, date, time, peopleCount, motive, honoree
     */
    public Map<String, Object> extractReservationData(String message) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dayOfWeek = LocalDate.now().getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.of("es"));

        String extractionPrompt = String.format("""
                Eres un asistente que extrae datos de reserva de restaurantes de mensajes en español.
                Hoy es %s (%s).

                Reglas del restaurante:
                - Solo acepta reservas los SÁBADOS
                - Horarios disponibles: 11:30, 12:00, 12:30, 13:00
                - "mañana" = próximo sábado si hoy no es sábado, o el sábado siguiente si hoy es sábado
                - "sábado" o "sabado" = próximo sábado
                - Si el cliente dice "10 personas", "somos 10", "para 10" → peopleCount = 10
                - Si dice "a la 1", "una de la tarde" → time = "13:00"
                - "12 de la tarde" → time = "12:00"
                - "12:30" → time = "12:30"
                - "11:30" → time = "11:30"
                - Detecta motivo: cumpleaños, reunión familiar, reunión de amigos, celebración, negocio
                - Detecta homenajeado si se menciona: "para mi mamá", "en honor a X"
                - Detecta nombre: "mi nombre es X", "soy X", "me llamo X", "nombre: X"

                Extrae SOLO los campos presentes en el mensaje. Si un campo no está, ponlo como null.
                Calcula la fecha correcta basándote en "mañana" o "sábado" relativo a hoy.

                Mensaje del usuario: "%s"

                Responde SOLO con JSON válido (sin texto adicional):
                {
                  "customerName": null,
                  "date": null,
                  "time": null,
                  "peopleCount": null,
                  "motive": null,
                  "honoree": null
                }

                Ejemplos:
                - "quiero reservar para mañana 10 personas" → {"date":"2026-09-06","peopleCount":10,"customerName":null,"time":null,"motive":null,"honoree":null}
                - "somos 5, a las 12, para el sábado" → {"date":"2026-09-12","time":"12:00","peopleCount":5,"customerName":null,"motive":null,"honoree":null}
                - "reservar para el cumple de mi mamá, 8 personas, sábado a la 1" → {"date":"2026-09-12","time":"13:00","peopleCount":8,"motive":"cumpleaños","honoree":"mamá","customerName":null}
                """, today, dayOfWeek, message);

        try {
            OpenAIServiceClient.OpenAIResult result = openAIClient.complete(
                    extractionPrompt, new ArrayList<>(), message);

            if (result == null || result.getText() == null || result.getText().isBlank()) {
                log.warn("[AI] Empty response from reservation data extraction");
                return Map.of();
            }

            return parseExtractionResponse(result.getText());
        } catch (Exception e) {
            log.error("[AI] Error extracting reservation data: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Parsea la respuesta JSON del LLM para extraer datos de reserva.
     * Maneja casos donde el JSON puede estar envuelto en markdown code blocks.
     */
    private Map<String, Object> parseExtractionResponse(String response) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = response.trim();

            // Remove markdown code blocks if present
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            JsonNode node = mapper.readTree(json);
            Map<String, Object> result = new HashMap<>();

            if (node.has("customerName") && !node.get("customerName").isNull()) {
                result.put("customerName", node.get("customerName").asText());
            }
            if (node.has("date") && !node.get("date").isNull()) {
                result.put("date", node.get("date").asText());
            }
            if (node.has("time") && !node.get("time").isNull()) {
                result.put("time", node.get("time").asText());
            }
            if (node.has("peopleCount") && !node.get("peopleCount").isNull()) {
                result.put("peopleCount", node.get("peopleCount").asInt());
            }
            if (node.has("motive") && !node.get("motive").isNull()) {
                result.put("motive", node.get("motive").asText());
            }
            if (node.has("honoree") && !node.get("honoree").isNull()) {
                result.put("honoree", node.get("honoree").asText());
            }

            log.info("[AI] Extracted reservation data: {}", result);
            return result;
        } catch (Exception e) {
            log.error("[AI] Error parsing extraction response: {}", e.getMessage());
            return Map.of();
        }
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
