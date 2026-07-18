package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.request.WebhookMessageRequest;
import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.application.service.MessageProcessingService;
import com.whatsappmvp.config.AppProperties;
import com.whatsappmvp.domain.enums.MessageType;
import com.whatsappmvp.infrastructure.persistence.jpa.WhatsappSessionJpaRepository;
import com.whatsappmvp.infrastructure.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final MessageProcessingService messageProcessingService;
    private final WhatsappSessionJpaRepository sessionRepository;
    private final WebSocketEventPublisher wsPublisher;
    private final AppProperties props;

    @PostMapping("/message")
    public ResponseEntity<Void> receiveMessage(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody WebhookMessageRequest req) {

        if (!props.getWhatsapp().getWebhookSecret().equals(secret)) {
            log.warn("[Webhook] Invalid secret");
            return ResponseEntity.status(401).build();
        }

        MessageType type;
        try {
            type = req.getMessageType() != null
                ? MessageType.valueOf(req.getMessageType().toUpperCase())
                : MessageType.TEXT;
        } catch (IllegalArgumentException e) {
            type = MessageType.UNKNOWN;
        }

        final MessageType finalType = type;

        // Procesar en hilo separado para no bloquear el webhook
        new Thread(() -> {
            try {
                messageProcessingService.processIncomingMessage(
                    req.getPhone(), req.getDisplayName(),
                    req.getContent(), req.getWaMessageId(), finalType
                );
            } catch (Exception e) {
                log.error("[Webhook] Failed to process message from {}: {}", req.getPhone(), e.getMessage(), e);
            }
        }).start();

        return ResponseEntity.ok().build();
    }

    @PostMapping("/session-status")
    public ResponseEntity<Void> receiveSessionStatus(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody Map<String, Object> payload) {

        if (!props.getWhatsapp().getWebhookSecret().equals(secret)) {
            return ResponseEntity.status(401).build();
        }

        String status = (String) payload.get("status");
        String phone  = (String) payload.get("phoneNumber");
        String qr     = (String) payload.get("qrCode");

        sessionRepository.findBySessionName("default").ifPresent(session -> {
            session.setStatus(status != null ? status : "DISCONNECTED");
            if (phone != null) session.setPhoneNumber(phone);
            if (qr    != null) session.setQrCode(qr);
            session.setLastHeartbeat(LocalDateTime.now());
            if ("CONNECTED".equals(status)) {
                session.setConnectedAt(LocalDateTime.now());
                session.setErrorMessage(null);
            }
            sessionRepository.save(session);
        });

        wsPublisher.publishSessionStatus(payload);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/message-status")
    public ResponseEntity<Void> receiveMessageStatus(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody Map<String, Object> payload) {
        if (!props.getWhatsapp().getWebhookSecret().equals(secret)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok().build();
    }
}
