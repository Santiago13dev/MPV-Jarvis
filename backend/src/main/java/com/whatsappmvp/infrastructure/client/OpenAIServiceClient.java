package com.whatsappmvp.infrastructure.client;

import com.whatsappmvp.config.AppProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP hacia OpenAI API.
 * Solo se invoca como FALLBACK cuando el motor híbrido no resuelve el mensaje.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAIServiceClient {

    private final AppProperties props;

    private WebClient buildClient() {
        return WebClient.builder()
                .baseUrl(props.getOpenai().getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getOpenai().getApiKey())
                .build();
    }

    /**
     * Envía mensajes al modelo OpenAI y retorna la respuesta de texto + tokens usados.
     *
     * @param systemPrompt  Contexto del negocio (construido dinámicamente desde DB)
     * @param conversationHistory  Últimos mensajes de la conversación para contexto
     * @param userMessage   El mensaje actual del usuario
     * @return OpenAIResult con el texto de respuesta y la cantidad de tokens usados
     */
    public OpenAIResult complete(String systemPrompt, List<Map<String, String>> conversationHistory, String userMessage) {
        if (props.getOpenai().getApiKey() == null || props.getOpenai().getApiKey().isBlank()) {
            log.warn("[OpenAI] API key not configured — returning fallback response");
            return new OpenAIResult("En este momento no puedo procesar tu consulta. Un asesor te contactará pronto. 🙏", 0);
        }

        // Construir array de mensajes
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // Agregar historial (máximo últimos 6 mensajes para no gastar tokens)
        if (conversationHistory != null) {
            int start = Math.max(0, conversationHistory.size() - 6);
            messages.addAll(conversationHistory.subList(start, conversationHistory.size()));
        }

        // Agregar mensaje actual
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = Map.of(
                "model", props.getOpenai().getModel(),
                "messages", messages,
                "max_tokens", props.getOpenai().getMaxTokens(),
                "temperature", props.getOpenai().getTemperature()
        );

        try {
            Map<?, ?> response = buildClient().post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                            .map(body -> {
                                log.error("[OpenAI] API error ({}): {}", clientResponse.statusCode(), body);
                                return new RuntimeException("API error: " + clientResponse.statusCode());
                            }))
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (response == null) {
                return fallback();
            }

            // Extraer texto de la respuesta
            @SuppressWarnings("unchecked")
            List<Map<?, ?>> choices = (List<Map<?, ?>>) response.get("choices");
            Map<?, ?> message = (Map<?, ?>) choices.get(0).get("message");
            String content = (String) message.get("content");

            // Extraer tokens usados
            Map<?, ?> usage = (Map<?, ?>) response.get("usage");
            int tokensUsed = usage != null ? ((Number) usage.get("total_tokens")).intValue() : 0;

            log.info("[OpenAI] Response generated. Tokens used: {}", tokensUsed);
            return new OpenAIResult(content.trim(), tokensUsed);

        } catch (Exception e) {
            log.error("[OpenAI] Error calling API: {}", e.getMessage());
            return fallback();
        }
    }

    private OpenAIResult fallback() {
        return new OpenAIResult(
                "En este momento estoy teniendo dificultades. Un asesor te atenderá muy pronto. 🙏",
                0
        );
    }

    @Data
    public static class OpenAIResult {
        private final String text;
        private final int tokensUsed;
    }
}
