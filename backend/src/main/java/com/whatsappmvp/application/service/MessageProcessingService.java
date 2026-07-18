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
import java.util.Map;
import java.math.BigDecimal;
import com.whatsappmvp.adapter.dto.request.ReservationRequest;

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

    private final ContactJpaRepository contactRepository;
    private final ConversationJpaRepository conversationRepository;
    private final MessageJpaRepository messageRepository;
    private final BusinessConfigJpaRepository businessConfigRepository;
    private final ReservationService reservationService;

    /**
     * Punto de entrada principal — llamado por WebhookController.
     *
     * @param phone       Número del remitente (ej: 573001234567)
     * @param displayName Nombre en WhatsApp del remitente
     * @param content     Texto del mensaje
     * @param waMessageId ID original de WhatsApp
     * @param messageType Tipo de mensaje (TEXT, IMAGE, etc.)
     */
    @Transactional
    public void processIncomingMessage(String phone, String displayName, String content,
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

        // Solo procesar texto automáticamente (imágenes, docs, etc. → derivar a humano)
        if (!MessageType.TEXT.equals(messageType)) {
            String response = "📎 Recibí tu archivo. Un asesor lo revisará y te responderá pronto. 😊";
            sendAndPersistResponse(conversation, response, ProcessedBy.SYSTEM, 0);
            return;
        }

        // ── PASO 6: Verificar horario laboral ─────────────────────────────────
        if (!businessHoursService.isWithinBusinessHours()) {
            log.info("[Pipeline] Outside business hours — sending off-hours message");
            String offHoursMsg = businessHoursService.getOffHoursMessage();
            sendAndPersistResponse(conversation, offHoursMsg, ProcessedBy.SYSTEM, 0);
            return;
        }

        // ── PASO 7: Mensaje de bienvenida (primer mensaje del día/conversación) ─
        if (isFirstMessage) {
            String welcome = businessHoursService.getWelcomeMessage();
            sendAndPersistResponse(conversation, welcome, ProcessedBy.SYSTEM, 0);
            // Continuar procesando para dar respuesta también al contenido
        }

        if (content == null || content.isBlank()) return;

        // ── PASO 8: MOTOR HÍBRIDO ─────────────────────────────────────────────

        // 8.0 — Reservas
        if (content.toLowerCase().contains("reservar") || content.toLowerCase().contains("reserva")) {
            log.info("[Pipeline] RESERVATION keyword match → creating pending reservation");
            
            ReservationRequest req = new ReservationRequest();
            req.setCustomerName(displayName != null && !displayName.isBlank() ? displayName : phone);
            req.setPhoneNumber(phone);
            req.setReservationDate(LocalDateTime.now().plusDays(1)); // Mañana por defecto
            req.setAmount(new BigDecimal("50.00")); // Monto por defecto
            
            reservationService.createReservation(req);
            
            String resText = "¡Hola! He registrado tu solicitud de reserva para mañana. El monto a pagar es de $50.00. Un asesor te contactará pronto para confirmar los detalles. 📅";
            sendAndPersistResponse(conversation, resText, ProcessedBy.SYSTEM, 0);
            return;
        }

        // 8A — Keywords
        var keywordMatch = keywordMatchingService.findMatch(content);
        if (keywordMatch.isPresent()) {
            log.info("[Pipeline] KEYWORD match → sending response");
            sendAndPersistResponse(conversation, keywordMatch.get(), ProcessedBy.KEYWORD, 0);
            return;
        }

        // 8B — FAQ Engine
        var faqMatch = faqMatchingService.findMatch(content);
        if (faqMatch.isPresent()) {
            log.info("[Pipeline] FAQ match → sending response");
            sendAndPersistResponse(conversation, faqMatch.get(), ProcessedBy.FAQ, 0);
            return;
        }

        // 8C — AI Fallback
        BusinessConfigEntity config = businessConfigRepository.findFirstByOrderByCreatedAtAsc().orElse(null);
        boolean aiEnabled = config == null || Boolean.TRUE.equals(config.getAiEnabled());

        if (aiEnabled) {
            log.info("[Pipeline] No rule/FAQ match → escalating to AI");
            OpenAIServiceClient.OpenAIResult aiResult = aiService.generateResponse(conversation.getId(), content);
            sendAndPersistResponse(conversation, aiResult.getText(), ProcessedBy.AI, aiResult.getTokensUsed());
        } else {
            log.info("[Pipeline] AI disabled and no match found → generic response");
            String generic = "Gracias por tu mensaje. En breve un asesor te atenderá. 😊";
            sendAndPersistResponse(conversation, generic, ProcessedBy.SYSTEM, 0);
        }
    }

    // ── Métodos auxiliares ─────────────────────────────────────────────────────

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
        return conversationRepository.findByContactId(contact.getId())
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

    private void sendAndPersistResponse(ConversationEntity conversation,
                                         String responseText, ProcessedBy processedBy, int tokensUsed) {
        // Enviar vía WhatsApp Service
        try {
            whatsAppClient.sendText(conversation.getContact().getPhone(), responseText);
        } catch (Exception e) {
            log.error("[Pipeline] Failed to send WhatsApp message: {}", e.getMessage());
        }

        // Persistir respuesta OUTBOUND
        MessageEntity outbound = persistMessage(conversation, null,
                MessageDirection.OUTBOUND, responseText, MessageType.TEXT, processedBy, tokensUsed);

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
