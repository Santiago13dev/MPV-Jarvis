package com.whatsappmvp.application.service;

import com.whatsappmvp.domain.enums.ConversationStatus;
import com.whatsappmvp.domain.enums.MessageDirection;
import com.whatsappmvp.domain.enums.MessageType;
import com.whatsappmvp.domain.enums.ProcessedBy;
import com.whatsappmvp.infrastructure.client.OpenAIServiceClient;
import com.whatsappmvp.infrastructure.client.WhatsAppServiceClient;
import com.whatsappmvp.infrastructure.persistence.entity.*;
import com.whatsappmvp.infrastructure.persistence.jpa.*;
import com.whatsappmvp.infrastructure.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * MESSAGE PROCESSING SERVICE — Núcleo del sistema
 * ============================================================
 *
 * Implementa el pipeline completo de procesamiento de mensajes entrantes:
 *
 * 1. Rate limit check        → ¿spam?
 * 2. Contacto bloqueado      → ignorar
 * 3. Horario laboral         → mensaje fuera de horario
 * 4. Estado conversación     → HUMAN_TAKEOVER → no automatizar
 * 5. Motor híbrido:
 *    A. Keywords             → respuesta directa
 *    B. FAQ engine           → respuesta FAQ
 *    C. AI fallback          → OpenAI
 * 6. Persistir mensaje + respuesta en BD
 * 7. Publicar evento WebSocket al dashboard
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageProcessingService {

    private final RateLimitService rateLimitService;
    private final BusinessHoursService businessHoursService;
    private final KeywordMatchingService keywordMatchingService;
    private final FaqMatchingService faqMatchingService;
    private final AIService aiService;
    private final WhatsAppServiceClient whatsAppClient;
    private final WebSocketEventPublisher wsPublisher;
    private final HumanTransferService humanTransferService;

    private final ContactJpaRepository contactRepository;
    private final ConversationJpaRepository conversationRepository;
    private final MessageJpaRepository messageRepository;
    private final BusinessConfigJpaRepository businessConfigRepository;
    private final ReservationService reservationService;
    private final ReservationFlowService reservationFlowService;

    /**
     * Punto de entrada principal — llamado por WebhookController.
     *
     * @param phone       Número del remitente (ej: 573001234567)
     * @param remoteJid   JID completo de WhatsApp (ej: 573001234567@s.whatsapp.net o xxx@lid)
     * @param displayName Nombre en WhatsApp del remitente
     * @param content     Texto del mensaje
     * @param waMessageId ID original de WhatsApp
     * @param messageType Tipo de mensaje (TEXT, IMAGE, etc.)
     */
    @Transactional
    public void processIncomingMessage(String phone, String remoteJid, String displayName, String content,
                                       String waMessageId, MessageType messageType) {
        log.info("[Pipeline] Processing message from: {} | type: {} | content: '{}'",
                phone, messageType, content != null ? content.substring(0, Math.min(50, content.length())) : "");

        // ── PASO 1: Rate Limit ────────────────────────────────────────────────
        if (!rateLimitService.isAllowed(phone)) {
            log.warn("[Pipeline] Rate limit exceeded for: {} — ignoring", phone);
            return;
        }

        // ── PASO 2: Obtener o crear contacto ──────────────────────────────────
        ContactEntity contact = getOrCreateContact(phone, displayName);

        if (Boolean.TRUE.equals(contact.getIsBlocked())) {
            log.info("[Pipeline] Contact is blocked: {} — ignoring", phone);
            return;
        }

        // ── PASO 3: Obtener o crear conversación ──────────────────────────────
        ConversationEntity conversation = getOrCreateConversation(contact);

        // Actualizar remoteJid si es nuevo o cambió
        if (remoteJid != null && !remoteJid.isBlank()
                && (conversation.getRemoteJid() == null || !conversation.getRemoteJid().equals(remoteJid))) {
            conversation.setRemoteJid(remoteJid);
            conversationRepository.save(conversation);
        }

        boolean isFirstMessage = conversation.getMessages().isEmpty();

        // ── PASO 4: Persistir mensaje INBOUND ─────────────────────────────────
        MessageEntity inboundMessage = persistMessage(conversation, waMessageId,
                MessageDirection.INBOUND, content, messageType, null, 0);

        // Actualizar last_seen del contacto
        contact.setLastSeen(LocalDateTime.now());
        contactRepository.save(contact);

        // Actualizar conversación
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setUnreadCount(conversation.getUnreadCount() + 1);
        conversationRepository.save(conversation);

        // Publicar evento al dashboard
        wsPublisher.publishNewMessage(buildMessageEvent(inboundMessage, phone, displayName));
        wsPublisher.publishConversationUpdate(buildConversationEvent(conversation));

        // ── PASO 5: Si está en HUMAN_TAKEOVER, no automatizar ─────────────────
        if (ConversationStatus.HUMAN_TAKEOVER.equals(conversation.getStatus())) {
            log.info("[Pipeline] Conversation {} is in HUMAN_TAKEOVER — skipping automation", conversation.getId());
            return;
        }

        // ── PASO 5.4: Cancelar reserva — antes de iniciar reserva ───────────
        String normalizedContent = content != null ? content.trim().toLowerCase() : "";
        if (normalizedContent.contains("cancelar") && normalizedContent.contains("reserva")) {
            log.info("[Pipeline] CANCEL RESERVATION trigger → starting cancel flow");
            String cancelMsg = reservationFlowService.startCancelFlow(conversation, phone);
            sendAndPersistResponse(conversation, remoteJid, cancelMsg, ProcessedBy.SYSTEM, 0);
            return;
        }

        // ── PASO 5.5: Reservar — iniciar flujo SIEMPRE (antes de FAQs) ───────
        if (normalizedContent.equals("reservar") || normalizedContent.equals("reserva")) {
            log.info("[Pipeline] RESERVATION trigger → starting reservation flow");
            String resMsg = reservationFlowService.startFlow(conversation);
            sendAndPersistResponse(conversation, remoteJid, resMsg, ProcessedBy.SYSTEM, 0);
            return;
        }

        // ── PASO 5.6: Si hay una acción pendiente (reserva en curso) ────────
        if (reservationFlowService.hasPendingAction(conversation)) {
            log.info("[Pipeline] Pending reservation action detected → handling step");
            var stepResponse = reservationFlowService.handleStep(conversation, content, phone, displayName);
            if (stepResponse.isPresent()) {
                sendAndPersistResponse(conversation, remoteJid, stepResponse.get(), ProcessedBy.SYSTEM, 0);
                return;
            }
            // If handleStep returned empty, the action was cleared or unrecognized — continue normal pipeline
        }

        // ── PASO 5.7: Transferencia a humano (keywords) ──────────────────────
        if (humanTransferService.hasTransferKeyword(normalizedContent)) {
            log.info("[Pipeline] HUMAN TRANSFER keyword detected → transferring to human");
            humanTransferService.transferToHuman(conversation, remoteJid, "KEYWORD", content);
            return;
        }

        // Solo procesar texto automáticamente (imágenes, docs, etc. → derivar a humano)
        if (!MessageType.TEXT.equals(messageType)) {
            String response = "📎 Recibí tu archivo. Un asesor lo revisará y te responderá pronto. 😊";
            sendAndPersistResponse(conversation, remoteJid, response, ProcessedBy.SYSTEM, 0);
            return;
        }

        if (content == null || content.isBlank()) return;

        // boolean withinBusinessHours = businessHoursService.isWithinBusinessHours(); // DESHABILITADO

        // ── PASO 6: Mensaje de bienvenida (primer mensaje del día/conversación) ─
        if (isFirstMessage) {
            String welcome = businessHoursService.getWelcomeMessage();
            sendAndPersistResponse(conversation, remoteJid, welcome, ProcessedBy.SYSTEM, 0);

            // Enviar ubicación del restaurante
            sendLocation(remoteJid, conversation,
                    4.49083, -74.25944, "BENDITO CHICHARRÓN — Sibaté, Cundinamarca");
        }

        // ── PASO 7: MOTOR HÍBRIDO ─────────────────────────────────────────────
        // Keywords y FAQs funcionan SIEMPRE (dentro o fuera de horario).
        // Solo AI y reservas se bloquean fuera de horario.

        // 7A — Keywords
        var keywordMatch = keywordMatchingService.findMatch(content);
        if (keywordMatch.isPresent()) {
            log.info("[Pipeline] KEYWORD match → sending response");
            sendAndPersistResponse(conversation, remoteJid, keywordMatch.get(), ProcessedBy.KEYWORD, 0);
            return;
        }

        // 7B — FAQ Engine
        var faqMatch = faqMatchingService.findMatch(content);
        if (faqMatch.isPresent()) {
            log.info("[Pipeline] FAQ match → sending response");
            sendAndPersistResponse(conversation, remoteJid, faqMatch.get(), ProcessedBy.FAQ, 0);
            return;
        }

        // ── PASO 8: Fuera de horario → mensaje de aviso ───────────────────────
        // DESHABILITADO — AI funciona 24/7
        // if (!withinBusinessHours) {
        //     log.info("[Pipeline] Outside business hours — sending off-hours message");
        //     String offHoursMsg = businessHoursService.getOffHoursMessage();
        //     sendAndPersistResponse(conversation, remoteJid, offHoursMsg, ProcessedBy.SYSTEM, 0);
        //     return;
        // }

        // ── PASO 8.5: Timeout — transferir a humano si pasó demasiado tiempo ──
        if (!isFirstMessage && humanTransferService.isTimeoutTriggered(conversation)) {
            log.info("[Pipeline] Timeout triggered → transferring to human");
            humanTransferService.transferToHuman(conversation, remoteJid, "TIMEOUT", content);
            return;
        }

        // ── PASO 9: Dentro de horario — AI Fallback ────────────────────────

        // 9C — AI Fallback
        BusinessConfigEntity config = businessConfigRepository.findFirstByOrderByCreatedAtAsc().orElse(null);
        boolean aiEnabled = config == null || Boolean.TRUE.equals(config.getAiEnabled());

        if (aiEnabled) {
            log.info("[Pipeline] No rule/FAQ match → escalating to AI");
            OpenAIServiceClient.OpenAIResult aiResult = aiService.generateResponse(conversation.getId(), content);
            sendAndPersistResponse(conversation, remoteJid, aiResult.getText(), ProcessedBy.AI, aiResult.getTokensUsed());

            // ── PASO 9.1: Verificar sentimiento después de responder ────────
            // Si el cliente parece frustrado, transferir a humano
            List<Map<String, String>> history = new ArrayList<>();
            if (humanTransferService.isFrustrated(content, history)) {
                log.info("[Pipeline] AI detected frustration → transferring to human");
                humanTransferService.transferToHuman(conversation, remoteJid, "FRUSTRATION", content);
            }
        } else {
            log.info("[Pipeline] AI disabled and no match found → generic response");
            String generic = "Gracias por tu mensaje. En breve un asesor te atenderá. 😊";
            sendAndPersistResponse(conversation, remoteJid, generic, ProcessedBy.SYSTEM, 0);
        }
    }

    // ── Métodos auxiliares ─────────────────────────────────────────────────────

    private void sendLocation(String remoteJid, ConversationEntity conversation,
                              double latitude, double longitude, String name) {
        String targetJid = remoteJid != null && !remoteJid.isBlank()
                ? remoteJid
                : conversation.getContact().getPhone() + "@s.whatsapp.net";

        try {
            whatsAppClient.sendLocation(targetJid, latitude, longitude, name);
        } catch (Exception e) {
            log.error("[Pipeline] Failed to send location to {}: {}", targetJid, e.getMessage());
        }
    }

    private ContactEntity getOrCreateContact(String phone, String displayName) {
        return contactRepository.findByPhone(phone)
                .orElseGet(() -> {
                    log.info("[Pipeline] New contact: {}", phone);
                    return contactRepository.save(ContactEntity.builder()
                            .phone(phone)
                            .displayName(displayName)
                            .lastSeen(LocalDateTime.now())
                            .build());
                });
    }

    private ConversationEntity getOrCreateConversation(ContactEntity contact) {
        return conversationRepository.findByContactIdAndIsDeletedFalse(contact.getId())
                .orElseGet(() -> {
                    log.info("[Pipeline] New conversation for contact: {}", contact.getPhone());
                    return conversationRepository.save(ConversationEntity.builder()
                            .contact(contact)
                            .status(ConversationStatus.AUTO)
                            .lastMessageAt(LocalDateTime.now())
                            .build());
                });
    }

    private MessageEntity persistMessage(ConversationEntity conversation, String waMessageId,
                                          MessageDirection direction, String content,
                                          MessageType type, ProcessedBy processedBy, int tokensUsed) {
        MessageEntity message = MessageEntity.builder()
                .conversation(conversation)
                .waMessageId(waMessageId)
                .direction(direction)
                .content(content)
                .messageType(type)
                .processedBy(processedBy)
                .aiTokensUsed(tokensUsed)
                .sentAt(LocalDateTime.now())
                .build();
        return messageRepository.save(message);
    }

    private void sendAndPersistResponse(ConversationEntity conversation, String remoteJid,
                                         String responseText, ProcessedBy processedBy, int tokensUsed) {
        // Usar remoteJid (JID completo) para enviar, no solo el phone
        String targetJid = remoteJid != null && !remoteJid.isBlank()
                ? remoteJid
                : conversation.getContact().getPhone() + "@s.whatsapp.net";

        // Enviar vía WhatsApp Service
        boolean sent = false;
        try {
            whatsAppClient.sendText(targetJid, responseText);
            sent = true;
        } catch (Exception e) {
            log.error("[Pipeline] Failed to send WhatsApp message to {}: {}", targetJid, e.getMessage());
        }

        // Persistir respuesta OUTBOUND (marcar status según éxito)
        MessageEntity outbound = persistMessage(conversation, null,
                MessageDirection.OUTBOUND, responseText, MessageType.TEXT, processedBy, tokensUsed);
        outbound.setStatus(sent ? "SENT" : "FAILED");
        messageRepository.save(outbound);

        // Notificar dashboard
        wsPublisher.publishNewMessage(buildMessageEvent(outbound,
                conversation.getContact().getPhone(),
                conversation.getContact().getDisplayName()));
    }

    private Map<String, Object> buildMessageEvent(MessageEntity msg, String phone, String name) {
        return Map.of(
                "type", "NEW_MESSAGE",
                "messageId", msg.getId().toString(),
                "conversationId", msg.getConversation().getId().toString(),
                "phone", phone,
                "displayName", name != null ? name : phone,
                "content", msg.getContent() != null ? msg.getContent() : "",
                "direction", msg.getDirection().name(),
                "processedBy", msg.getProcessedBy() != null ? msg.getProcessedBy().name() : "UNKNOWN",
                "sentAt", msg.getSentAt() != null ? msg.getSentAt().toString() : LocalDateTime.now().toString()
        );
    }

    private Map<String, Object> buildConversationEvent(ConversationEntity conv) {
        return Map.of(
                "type", "CONVERSATION_UPDATE",
                "conversationId", conv.getId().toString(),
                "status", conv.getStatus().name(),
                "unreadCount", conv.getUnreadCount(),
                "lastMessageAt", conv.getLastMessageAt() != null ? conv.getLastMessageAt().toString() : ""
        );
    }
}
