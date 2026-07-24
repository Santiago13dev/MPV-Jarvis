package com.whatsappmvp.infrastructure.client;

import com.whatsappmvp.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Cliente HTTP hacia el WhatsApp Service (Node.js).
 * Spring Boot llama aquí para ENVIAR mensajes de salida.
 * Los mensajes ENTRANTES llegan por webhook (al revés).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppServiceClient {

    private final AppProperties props;

    private WebClient buildClient() {
        return WebClient.builder()
                .baseUrl(props.getWhatsapp().getServiceUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Webhook-Secret", props.getWhatsapp().getWebhookSecret())
                .build();
    }

    /** Enviar mensaje de texto */
    public void sendText(String to, String text) {
        Map<String, String> body = Map.of("to", to, "text", text);
        post("/messages/send/text", body);
    }

    /** Enviar imagen */
    public void sendImage(String to, String imageUrl, String caption) {
        Map<String, String> body = Map.of("to", to, "imageUrl", imageUrl, "caption", caption != null ? caption : "");
        post("/messages/send/image", body);
    }

    /** Enviar documento/PDF */
    public void sendDocument(String to, String docUrl, String filename, String mimetype) {
        Map<String, String> body = Map.of(
                "to", to, "docUrl", docUrl,
                "filename", filename, "mimetype", mimetype != null ? mimetype : "application/pdf"
        );
        post("/messages/send/document", body);
    }

    /** Enviar ubicación GPS */
    public void sendLocation(String to, double latitude, double longitude, String name) {
        Map<String, Object> body = Map.of(
                "to", to, "latitude", latitude,
                "longitude", longitude, "name", name != null ? name : ""
        );
        post("/messages/send/location", body);
    }

    /** Obtener estado de la sesión WhatsApp */
    public Map<?, ?> getSessionStatus() {
        return buildClient().get()
                .uri("/session/status")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorReturn(errorStatusMap("ERROR"))
                .block();
    }

    /** Obtener QR code en base64 */
    public Map<?, ?> getQrCode() {
        return buildClient().get()
                .uri("/session/qr")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorReturn(errorStatusMap("ERROR"))
                .block();
    }

    /** Forzar reconexión */
    public void reconnect() {
        post("/session/reconnect", Map.of());
    }

    /** Desconectar sesión */
    public void disconnect() {
        post("/session/disconnect", Map.of());
    }

    /** Resetear sesión — borra credenciales y genera QR nuevo */
    public void resetSession() {
        post("/session/reset", Map.of());
    }

    private Map<String, String> errorStatusMap(String status) {
        Map<String, String> map = new HashMap<>();
        map.put("status", status);
        return map;
    }

    private void post(String path, Object body) {
        buildClient().post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10))
                .block();
    }
}
