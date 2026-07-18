package com.whatsappmvp.infrastructure.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica eventos en tiempo real al dashboard Angular vía WebSocket STOMP.
 * El frontend se suscribe a /topic/conversations, /topic/session, etc.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    /** Nuevo mensaje entrante o saliente — actualiza el inbox del dashboard */
    public void publishNewMessage(Object payload) {
        messagingTemplate.convertAndSend("/topic/messages", payload);
        log.debug("[WS] Published to /topic/messages");
    }

    /** Cambio de estado de una conversación */
    public void publishConversationUpdate(Object payload) {
        messagingTemplate.convertAndSend("/topic/conversations", payload);
        log.debug("[WS] Published to /topic/conversations");
    }

    /** Cambio de estado de la sesión WhatsApp (conectado, QR, error...) */
    public void publishSessionStatus(Object payload) {
        messagingTemplate.convertAndSend("/topic/session", payload);
        log.debug("[WS] Published to /topic/session");
    }

    /** Métricas actualizadas en tiempo real */
    public void publishMetricsUpdate(Object payload) {
        messagingTemplate.convertAndSend("/topic/metrics", payload);
        log.debug("[WS] Published to /topic/metrics");
    }
}
