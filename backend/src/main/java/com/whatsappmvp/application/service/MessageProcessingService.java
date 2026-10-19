package com.whatsappmvp.application.service;

import com.whatsappmvp.domain.enums.ConversationStatus;
import com.whatsappmvp.domain.enums.MessageDirection;
import com.whatsappmvp.domain.enums.MessageType;
import com.whatsappmvp.domain.enums.ProcessedBy;
import com.whatsappmvp.application.reservation.ReservationConversationService;
import com.whatsappmvp.application.reservation.TextNormalizer;
import com.whatsappmvp.config.AppProperties;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionTemplate;

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

    private static final String ACTION_HUMAN_TRANSFER_OFFERED = "HUMAN_TRANSFER_OFFERED";

    /** Primera palabra que delata una pregunta cuando el mensaje no trae "?". */
    private static final java.util.Set<String> QUESTION_OPENERS = java.util.Set.of(
            "cuanto", "cuando", "donde", "cual", "cuales", "que", "quien", "quienes", "como",
            "tienen", "tiene", "hacen", "hace", "puedo", "pueden", "existe", "existen", "habra",
            "don", "cualquier", "sabes");

    // Per-phone lock registry — serializa mensajes del mismo usuario
    private final ConcurrentHashMap<String, Object> phoneLocks = new ConcurrentHashMap<>();

    private final RateLimitService rateLimitService;
    private final BusinessHoursService businessHoursService;
    private final KeywordMatchingService keywordMatchingService;
    private final FaqMatchingService faqMatchingService;
    private final AIService aiService;
    private final WhatsAppServiceClient whatsAppClient;
    private final WebSocketEventPublisher wsPublisher;
    private final HumanTransferService humanTransferService;
    private final AppProperties props;

    private final ContactJpaRepository contactRepository;
    private final ConversationJpaRepository conversationRepository;
    private final MessageJpaRepository messageRepository;
    private final BusinessConfigJpaRepository businessConfigRepository;
    private final ReservationConversationService reservationConversation;
    private final TransactionTemplate transactionTemplate;

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
    public void processIncomingMessage(String phone, String remoteJid, String displayName, String content,
                                       String waMessageId, MessageType messageType) {
        processIncomingMessage(phone, remoteJid, displayName, content, waMessageId, messageType, null, null);
    }

    /**
     * Igual que el anterior, con el adjunto (imagen/PDF en base64) cuando el cliente envía un comprobante de pago.
     */
    public void processIncomingMessage(String phone, String remoteJid, String displayName, String content,
                                       String waMessageId, MessageType messageType,
                                       String mediaBase64, String mediaMimeType) {
        // Serializar por número de teléfono — evita race conditions
        Object lock = phoneLocks.computeIfAbsent(phone, k -> new Object());
        List<PendingSend> pendingSends;

        // FASE 1: DB operations — bajo lock y transacción
        // Persiste inbound + outbound, actualiza conversación, encola respuestas
        synchronized (lock) {
            try {
                pendingSends = new ArrayList<>();
                transactionTemplate.executeWithoutResult(status -> {
                    processMessageInTransaction(phone, remoteJid, displayName, content, waMessageId, messageType,
                            mediaBase64, mediaMimeType, pendingSends);
                });
            } finally {
                phoneLocks.remove(phone);
            }
        }
        // ← Lock SUELTO. Otro mensaje del mismo teléfono puede empezar a procesar

        // FASE 2: External calls — FUERA de lock y transacción
        // Sleep + WhatsApp API — no bloquea otros mensajes del mismo teléfono
        for (PendingSend ps : pendingSends) {
            try {
                // Rate limit outbound: máximo 3 concurrentes + delay aleatorio 5-10s
                rateLimitService.acquireOutbound(phone);
                try {
                    // Delay humano aleatorio (5-10 segundos) — evita detección de spam por Meta
                    long delayMs = 5000 + (long) (Math.random() * 5000);
                    Thread.sleep(delayMs);
                    whatsAppClient.sendText(ps.targetJid(), ps.text());
                } finally {
                    rateLimitService.releaseOutbound(phone);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[Pipeline] Failed to send WhatsApp message to {}: {}", ps.targetJid(), e.getMessage());
            }
        }
    }

    private void processMessageInTransaction(String phone, String remoteJid, String displayName, String content,
                                              String waMessageId, MessageType messageType,
                                              String mediaBase64, String mediaMimeType,
                                              List<PendingSend> pendingSends) {
        log.info("[Pipeline] Processing message from: {} | type: {} | content: '{}'",
                phone, messageType, content != null ? content.substring(0, Math.min(50, content.length())) : "");

        // ── PASO 2: Obtener o crear contacto ──────────────────────────────────
        if (waMessageId != null && !waMessageId.isBlank()) {
            if (messageRepository.existsByWaMessageId(waMessageId)) {
                log.info("[Pipeline] Duplicate message {} — ignoring", waMessageId);
                return;
            }
        }

        // ── PASO 3: Obtener o crear contacto ──────────────────────────────────
        ContactEntity contact = getOrCreateContact(phone, displayName);

        if (Boolean.TRUE.equals(contact.getIsBlocked())) {
            log.info("[Pipeline] Contact is blocked: {} — ignoring", phone);
            return;
        }

        // ── PASO 4: Obtener o crear conversación ──────────────────────────────
        boolean[] isNewConversation = {false};
        ConversationEntity conversation = getOrCreateConversation(contact, isNewConversation);

        // Actualizar remoteJid si es nuevo o cambió
        if (remoteJid != null && !remoteJid.isBlank()
                && (conversation.getRemoteJid() == null || !conversation.getRemoteJid().equals(remoteJid))) {
            conversation.setRemoteJid(remoteJid);
            conversationRepository.save(conversation);
        }

        boolean isFirstMessage = isNewConversation[0];

        // ── PASO 2.5: Rate Limit ──────────────────────────────────────────────
        if (!rateLimitService.isAllowed(phone)) {
            log.warn("[Pipeline] Rate limit exceeded for: {} — sending notice", phone);
            sendAndPersistResponse(conversation, remoteJid,
                "Estás enviando muchos mensajes. Por favor espera un momento y vuelve a intentar.",
                ProcessedBy.SYSTEM, 0, pendingSends);
            return;
        }

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

        String normalizedContent = content != null ? content.trim().toLowerCase() : "";

        // ── PASO 5.45: Responder a oferta de asesor ──────────────────────────
        // Si la IA ofreció conectar con asesor y el cliente dice "si", ejecutar transferencia real
        if (ACTION_HUMAN_TRANSFER_OFFERED.equals(conversation.getPendingAction())) {
            boolean isAccept = normalizedContent.equals("si") || normalizedContent.equals("sí") ||
                    normalizedContent.equals("dale") || normalizedContent.equals("ok") ||
                    normalizedContent.equals("claro") || normalizedContent.equals("por favor") ||
                    normalizedContent.equals("si por favor") || normalizedContent.equals("sí por favor") ||
                    normalizedContent.startsWith("si ") || normalizedContent.startsWith("sí ");
            boolean isReject = normalizedContent.equals("no") || normalizedContent.equals("no gracias") ||
                    normalizedContent.contains("no quiero") || normalizedContent.contains("olvídalo") ||
                    normalizedContent.contains("olvidalo");

            if (isAccept) {
                log.info("[Pipeline] User accepted human transfer offer → executing transfer");
                conversation.setPendingAction(null);
                conversation.setPendingActionData(null);
                conversationRepository.save(conversation);
                humanTransferService.transferToHuman(conversation, remoteJid, "AI_OFFER_ACCEPTED", content);
                return;
            } else if (isReject) {
                log.info("[Pipeline] User rejected human transfer offer → clearing pending action");
                conversation.setPendingAction(null);
                conversation.setPendingActionData(null);
                conversationRepository.save(conversation);
                sendAndPersistResponse(conversation, remoteJid,
                        "¡Perfecto! Sigo aquí para ayudarte con lo que necesites. 😊",
                        ProcessedBy.SYSTEM, 0, pendingSends);
                return;
            }
            // If neither accept nor reject, clear the pending action and continue normal pipeline
            log.info("[Pipeline] Clearing HUMAN_TRANSFER_OFFERED pending action (unrecognized response)");
            conversation.setPendingAction(null);
            conversation.setPendingActionData(null);
            conversationRepository.save(conversation);
        }

        // ── PASO 5.5: Comprobante de pago (imagen/PDF) ──────────────────────
        // Se asocia a la reserva pendiente de pago del cliente. Si no hay ninguna, sigue el flujo normal
        // (el archivo se deriva a un asesor más abajo).
        if (mediaBase64 != null && !mediaBase64.isBlank()
                && (messageType == MessageType.IMAGE || messageType == MessageType.DOCUMENT)) {
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(mediaBase64);
                var receiptReply = reservationConversation.handleReceipt(
                        conversation, phone, bytes, mediaMimeType, content, waMessageId);
                if (receiptReply.isPresent()) {
                    log.info("[Pipeline] Payment receipt registered for {}", phone);
                    sendAndPersistResponse(conversation, remoteJid, receiptReply.get(), ProcessedBy.SYSTEM, 0, pendingSends);
                    return;
                }
            } catch (IllegalArgumentException e) {
                log.warn("[Pipeline] Invalid base64 attachment from {}: {}", phone, e.getMessage());
            }
        }

        // ── PASO 5.55: Transferencia a humano (keywords) ─────────────────────
        // Va ANTES del motor de reservas: si el cliente pide un asesor, esa petición manda sobre cualquier
        // borrador de reserva en curso (si no, el motor respondería con el recordatorio y lo dejaría atrapado).
        if (humanTransferService.hasTransferKeyword(normalizedContent)) {
            log.info("[Pipeline] HUMAN TRANSFER keyword detected → transferring to human");
            reservationConversation.discardDraft(conversation);
            humanTransferService.transferToHuman(conversation, remoteJid, "KEYWORD", content);
            sendAndPersistResponse(conversation, remoteJid,
                "Un asesor se comunicará contigo pronto. ¡Gracias por tu paciencia! 😊",
                ProcessedBy.SYSTEM, 0, pendingSends);
            return;
        }

        // ── PASO 5.6: MOTOR DE RESERVAS ─────────────────────────────────────
        // Crear / consultar / modificar / cancelar. El estado vive en reservation_drafts (PostgreSQL);
        // la disponibilidad y la creación las decide el backend. Lo que no es de reservas sigue el motor híbrido.
        if ((messageType == null || messageType == MessageType.TEXT || messageType == MessageType.UNKNOWN)
                && content != null && !content.isBlank()) {
            var reservationResult = reservationConversation.handleText(conversation, phone, content, waMessageId);
            switch (reservationResult.kind()) {
                case REPLY -> {
                    // El mensaje puede mezclar la reserva con otra pregunta
                    // ("reserva para 10 y voy con mi perro"): se contesta la FAQ primero
                    String engineText = withSideFaqAnswer(content, reservationResult.text());
                    sendAndPersistResponse(conversation, remoteJid, engineText,
                            ProcessedBy.SYSTEM, 0, pendingSends);
                    return;
                }
                case QUESTION_WITH_REMINDER -> {
                    // Pregunta ajena durante una reserva: se responde y se conserva el borrador
                    if (isMenuRequest(normalizedContent)) {
                        // El menú es un PDF: se envía y luego se retoma la reserva
                        sendMenuPdf(conversation, remoteJid, pendingSends);
                        sendAndPersistResponse(conversation, remoteJid, reservationResult.text(),
                                ProcessedBy.SYSTEM, 0, pendingSends);
                        return;
                    }
                    String answer = answerQuestionDuringReservation(conversation, content, remoteJid);
                    sendAndPersistResponse(conversation, remoteJid, answer + "\n\n" + reservationResult.text(),
                            ProcessedBy.SYSTEM, 0, pendingSends);
                    return;
                }
                case NOT_HANDLED -> { /* continúa con el motor híbrido */ }
            }
        }

        // ── PASO 5.62: Ubicación del restaurante ─────────────────────────────
        if (isLocationRequest(normalizedContent)) {
            log.info("[Pipeline] LOCATION request → sending restaurant location");
            String locationMsg = "📍 Estamos ubicados en:\n*BENDITO CHICHARRÓN*\nSibaté, Cundinamarca\n\nTe envío la ubicación exacta:";
            sendAndPersistResponse(conversation, remoteJid, locationMsg, ProcessedBy.SYSTEM, 0, pendingSends);
            sendLocation(remoteJid, conversation,
                    4.49083, -74.25944, "BENDITO CHICHARRÓN — Sibaté, Cundinamarca");
            return;
        }

        // ── PASO 5.65: Solicitud de menú — enviar PDF ─────────────────────────
        if (isMenuRequest(normalizedContent)) {
            log.info("[Pipeline] MENU request → sending PDF");
            sendMenuPdf(conversation, remoteJid, pendingSends);
            return;
        }

        // Solo procesar texto automáticamente (imágenes, docs, etc. → derivar a humano)
        // UNKNOWN con contenido de texto se procesa como texto (puede ser respuesta a mensaje)
        if (messageType != null && messageType != MessageType.TEXT && messageType != MessageType.UNKNOWN) {
            String response = "📎 Recibí tu archivo. Un asesor lo revisará y te responderá pronto. 😊";
            sendAndPersistResponse(conversation, remoteJid, response, ProcessedBy.SYSTEM, 0, pendingSends);
            return;
        }

        if (content == null || content.isBlank()) return;

        // boolean withinBusinessHours = businessHoursService.isWithinBusinessHours(); // DESHABILITADO

        // ── PASO 7: MOTOR HÍBRIDO ─────────────────────────────────────────────
        // Keywords y FAQs funcionan SIEMPRE (dentro o fuera de horario) y ANTES de la
        // bienvenida: si el primer mensaje del cliente es una pregunta ("¿tienen wifi?")
        // se contesta en vez de devolver solo el saludo.

        // 7A — Keywords
        var keywordMatch = keywordMatchingService.findMatch(content);
        if (keywordMatch.isPresent()) {
            log.info("[Pipeline] KEYWORD match → sending response");
            sendAndPersistResponse(conversation, remoteJid, keywordMatch.get(), ProcessedBy.KEYWORD, 0, pendingSends);
            return;
        }

        // 7B — FAQ Engine
        var faqMatch = faqMatchingService.findMatch(content);
        if (faqMatch.isPresent()) {
            log.info("[Pipeline] FAQ match → sending response");
            sendAndPersistResponse(conversation, remoteJid, faqMatch.get(), ProcessedBy.FAQ, 0, pendingSends);
            return;
        }

        // ── PASO 6: Mensaje de bienvenida (solo el primer mensaje) ────────────
        // Va DESPUÉS de keywords/FAQ: si el primer mensaje es una pregunta con match,
        // se contesta. Si es pregunta sin match, se salta a la IA (paso 9) en vez de
        // devolver solo el saludo ("¿hacen domicilios?" no debe recibir el bienvenido).
        if (isFirstMessage && !looksLikeQuestion(content)) {
            String welcome = businessHoursService.getWelcomeMessage();
            sendAndPersistResponse(conversation, remoteJid, welcome, ProcessedBy.SYSTEM, 0, pendingSends);

            // Enviar ubicación del restaurante
            sendLocation(remoteJid, conversation,
                    4.49083, -74.25944, "BENDITO CHICHARRÓN — Sibaté, Cundinamarca");

            // NO continuar a IA — la bienvenida es la única respuesta
            return;
        }

        // ── PASO 8: Fuera de horario → mensaje de aviso ───────────────────────
        // DESHABILITADO — AI funciona 24/7
        // if (!withinBusinessHours) {
        //     log.info("[Pipeline] Outside business hours — sending off-hours message");
        //     String offHoursMsg = businessHoursService.getOffHoursMessage();
        //     sendAndPersistResponse(conversation, remoteJid, offHoursMsg, ProcessedBy.SYSTEM, 0, pendingSends);
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
            try {
                OpenAIServiceClient.OpenAIResult aiResult = aiService.generateResponse(conversation.getId(), content);
                sendAndPersistResponse(conversation, remoteJid, aiResult.getText(), ProcessedBy.AI, aiResult.getTokensUsed(), pendingSends);

                // ── PASO 9.05: Si la IA ofreció un asesor, trackear la oferta ──
                String aiResponseLower = aiResult.getText().toLowerCase();
                boolean offeredAdvisor = aiResponseLower.contains("asesor") ||
                        aiResponseLower.contains("conecte con") || aiResponseLower.contains("conectarte con") ||
                        aiResponseLower.contains("comunico con") || aiResponseLower.contains("comunicarte con") ||
                        aiResponseLower.contains("hablar con") || aiResponseLower.contains("persona del restaurante");

                if (offeredAdvisor) {
                    log.info("[Pipeline] AI offered human transfer → setting HUMAN_TRANSFER_OFFERED pending action");
                    conversation.setPendingAction(ACTION_HUMAN_TRANSFER_OFFERED);
                    conversation.setPendingActionData("{\"offer\":true}");
                    conversationRepository.save(conversation);
                }

                // ── PASO 9.1: Verificar sentimiento después de responder ────────
                List<Map<String, String>> history = new ArrayList<>();
                if (humanTransferService.isFrustrated(content, history)) {
                    log.info("[Pipeline] AI detected frustration → transferring to human");
                    humanTransferService.transferToHuman(conversation, remoteJid, "FRUSTRATION", content);
                }
            } catch (Exception e) {
                log.error("[Pipeline] AI call failed: {}", e.getMessage());
                sendAndPersistResponse(conversation, remoteJid,
                    "Gracias por tu mensaje. Un asesor te atenderá pronto. 😊",
                    ProcessedBy.SYSTEM, 0, pendingSends);
            }
        } else {
            log.info("[Pipeline] AI disabled and no match found → generic response");
            String generic = "Gracias por tu mensaje. En breve un asesor te atenderá. 😊";
            sendAndPersistResponse(conversation, remoteJid, generic, ProcessedBy.SYSTEM, 0, pendingSends);
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

    private ConversationEntity getOrCreateConversation(ContactEntity contact, boolean[] isNewConversation) {
        return conversationRepository.findByContactIdAndIsDeletedFalse(contact.getId())
                .orElseGet(() -> {
                    log.info("[Pipeline] New conversation for contact: {}", contact.getPhone());
                    isNewConversation[0] = true;
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

    private record PendingSend(String targetJid, String text) {}

    private void sendAndPersistResponse(ConversationEntity conversation, String remoteJid,
                                         String responseText, ProcessedBy processedBy, int tokensUsed,
                                         List<PendingSend> pendingSends) {
        // Usar remoteJid (JID completo) para enviar, no solo el phone
        String targetJid = remoteJid != null && !remoteJid.isBlank()
                ? remoteJid
                : conversation.getContact().getPhone() + "@s.whatsapp.net";

        // Persistir respuesta OUTBOUND (dentro de la transacción)
        MessageEntity outbound = persistMessage(conversation, null,
                MessageDirection.OUTBOUND, responseText, MessageType.TEXT, processedBy, tokensUsed);
        outbound.setStatus("QUEUED");
        messageRepository.save(outbound);

        // Notificar dashboard
        wsPublisher.publishNewMessage(buildMessageEvent(outbound,
                conversation.getContact().getPhone(),
                conversation.getContact().getDisplayName()));

        // Cola para envío FUERA de la transacción (sleep + WhatsApp API)
        pendingSends.add(new PendingSend(targetJid, responseText));
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

    /**
     * Detecta si el usuario quiere ver la ubicación del restaurante.
     */
    private boolean isLocationRequest(String normalizedContent) {
        if (normalizedContent == null || normalizedContent.isBlank()) return false;
        return normalizedContent.contains("ubicacion") ||
               normalizedContent.contains("ubicación") ||
               normalizedContent.contains("ubican") ||
               normalizedContent.contains("ubicada") ||
               normalizedContent.contains("encuentran") ||
               normalizedContent.contains("encuentra") ||
               normalizedContent.contains("dónde están") ||
               normalizedContent.contains("donde estan") ||
               normalizedContent.contains("donde esta") ||
               normalizedContent.contains("dónde está") ||
               normalizedContent.contains("en dónde") ||
               normalizedContent.contains("en donde") ||
               normalizedContent.contains("como llego") ||
               normalizedContent.contains("cómo llego") ||
               normalizedContent.contains("dirección") ||
               normalizedContent.contains("direccion") ||
               normalizedContent.contains("quedan") ||
               normalizedContent.contains("queda") ||
               normalizedContent.contains("location");
    }

    /**
     * Detecta si el usuario quiere ver el menú/cart del restaurante.
     */
    private boolean isMenuRequest(String normalizedContent) {
        if (normalizedContent == null || normalizedContent.isBlank()) return false;
        return normalizedContent.contains("menu") ||
               normalizedContent.contains("menú") ||
               normalizedContent.contains("carta") ||
               normalizedContent.contains("platos") ||
               normalizedContent.contains("qué tienen") ||
               normalizedContent.contains("que tienen") ||
               normalizedContent.contains("qué sirven") ||
               normalizedContent.contains("que sirven") ||
               normalizedContent.contains("qué venden") ||
               normalizedContent.contains("que venden") ||
               normalizedContent.contains("envíame el menú") ||
               normalizedContent.contains("enviame el menu") ||
               normalizedContent.contains("pasame el menú") ||
               normalizedContent.contains("pasame el menu");
    }

    /**
     * Envía el PDF del menú al usuario.
     * El PDF está en static resources: /menu-bendito-chicharron.pdf
     */
    private void sendMenuPdf(ConversationEntity conversation, String remoteJid, List<PendingSend> pendingSends) {
        String targetJid = remoteJid != null && !remoteJid.isBlank()
                ? remoteJid
                : conversation.getContact().getPhone() + "@s.whatsapp.net";

        // Texto introductorio antes del PDF
        String introMessage = "📋 ¡Claro! Aquí tienes nuestro menú completo. 🍽️";
        sendAndPersistResponse(conversation, remoteJid, introMessage, ProcessedBy.SYSTEM, 0, pendingSends);

        // Ruta local del PDF dentro del container whatsapp-service
        String pdfPath = "/app/public/menu-bendito-chicharron.pdf";

        try {
            whatsAppClient.sendDocument(targetJid, pdfPath, "Menu-Bendito-Chicharron.pdf", "application/pdf");
            log.info("[Pipeline] Menu PDF sent to {}", targetJid);
        } catch (Exception e) {
            log.error("[Pipeline] Failed to send menu PDF to {}: {}", targetJid, e.getMessage());
            // Fallback: enviar mensaje de texto indicando que hay menú
            String fallbackMsg = "Disculpa, no pude enviar el menú en este momento. " +
                    "¿Puedes preguntar por horarios, precios o reservaciones? 😊";
            sendAndPersistResponse(conversation, remoteJid, fallbackMsg, ProcessedBy.SYSTEM, 0, pendingSends);
        }
    }

    /**
     * Responde una pregunta del usuario durante el flujo de reserva.
     * Usa FAQ matching y AI como fallback, sin interrumpir el flujo de reserva.
     *
     * @param conversation la conversación activa
     * @param question la pregunta del usuario
     * @param remoteJid el JID de WhatsApp
     * @return la respuesta a la pregunta
     */
    private String answerQuestionDuringReservation(ConversationEntity conversation, String question, String remoteJid) {
        log.info("[Pipeline] Answering question during reservation flow: {}", question);

        // Intentar FAQ matching primero
        var faqMatch = faqMatchingService.findMatch(question);
        if (faqMatch.isPresent()) {
            log.info("[Pipeline] FAQ match during reservation: {}", faqMatch.get());
            return faqMatch.get();
        }

        // Intentar keyword matching
        var keywordMatch = keywordMatchingService.findMatch(question);
        if (keywordMatch.isPresent()) {
            log.info("[Pipeline] Keyword match during reservation: {}", keywordMatch.get());
            return keywordMatch.get();
        }

        // Fallback a AI
        try {
            var aiResult = aiService.generateResponse(conversation.getId(), question);
            if (aiResult != null && aiResult.getText() != null && !aiResult.getText().isBlank()) {
                log.info("[Pipeline] AI response during reservation (tokens: {})", aiResult.getTokensUsed());
                return aiResult.getText();
            }
        } catch (Exception e) {
            log.error("[Pipeline] AI error during reservation question: {}", e.getMessage());
        }

        // Default response if nothing works
        return "Con gusto te ayudo con eso. ¿En qué más puedo ayudarte?";
    }

    /**
     * Combina la respuesta del motor de reservas con la FAQ de la pregunta lateral del mensaje.
     * Ej.: "reserva para 10 y voy con mi perro" → FAQ de mascotas + "Para cuántas personas…".
     */
    private String withSideFaqAnswer(String content, String engineText) {
        var sideFaq = faqMatchingService.findMatch(content, MessageProcessingService::isReservationFaq, 3);
        if (sideFaq.isEmpty()) {
            return engineText;
        }
        log.info("[Pipeline] Side FAQ while answering reservation: {}", sideFaq.get());
        return sideFaq.get() + "\n\n" + engineText;
    }

    /** FAQs que pertenecen al dominio de reservas: el motor ya responde esas, no se anteponen. */
    private static boolean isReservationFaq(FaqItemEntity faq) {
        String question = faq.getQuestion().toLowerCase(Locale.ROOT);
        if (question.contains("reserva")) return true;
        String[] keywords = faq.getKeywords();
        if (keywords == null) return false;
        for (String keyword : keywords) {
            String k = keyword.toLowerCase(Locale.ROOT);
            if (k.contains("reserv") || k.equals("mesa") || k.equals("mesas") || k.equals("cita")
                    || k.equals("apartar") || k.contains("disponibilidad") || k.contains("cupo")) {
                return true;
            }
        }
        return false;
    }

    /** ¿El primer mensaje es una pregunta? Si lo es, se contesta (FAQ/IA) en vez de mandar la bienvenida. */
    private static boolean looksLikeQuestion(String content) {
        if (content.contains("?")) return true;
        String n = TextNormalizer.normalize(content);
        if (n.isEmpty()) return false;
        String first = n.split(" ")[0];
        return QUESTION_OPENERS.contains(first) || n.startsWith("hay ") || n.startsWith("se puede ")
                || n.startsWith("me dan ") || n.startsWith("nos dan ");
    }
}
