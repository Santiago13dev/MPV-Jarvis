package com.whatsappmvp.application.service;

import com.whatsappmvp.domain.enums.ConversationStatus;
import com.whatsappmvp.infrastructure.client.OpenAIServiceClient;
import com.whatsappmvp.infrastructure.client.WhatsAppServiceClient;
import com.whatsappmvp.infrastructure.persistence.entity.BusinessConfigEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ConversationEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.BusinessConfigJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Servicio de transferencia a humano — híbrido.
 *
 * 1. Keywords explícitos ("hablar con alguien", "asesor", "queja", etc.)
 * 2. Detección de sentimiento negativo vía AI
 * 3. Timeout — si el bot respondió hace más de humanDelaySeconds y el cliente sigue escribiendo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanTransferService {

    private final ConversationJpaRepository conversationRepository;
    private final BusinessConfigJpaRepository configRepository;
    private final OpenAIServiceClient openAIClient;
    private final WhatsAppServiceClient whatsAppClient;

    // Keywords que trigger transferencia inmediata
    // NOTA: "persona" y "humano" se eliminaron porque causan falsos positivos
    // con mensajes como "somos 30 personas". Ya están cubiertos por
    // "hablar con una persona" y "hablar con un humano".
    private static final List<String> TRANSFER_KEYWORDS = List.of(
            "hablar con alguien", "hablar con una persona", "hablar con un humano",
            "quiero un asesor", "necesito un asesor", "puedo hablar con un asesor",
            "dueño", "encargado", "gerente",
            "queja", "reclamo", "problema grave", "esto es una basura",
            "quiero mi dinero", "reembolso", "estaf", "estafa",
            "hablar con alguien ya", "atención humana"
    );

    /**
     * Verifica si el mensaje contiene keywords de transferencia a humano.
     */
    public boolean hasTransferKeyword(String content) {
        if (content == null || content.isBlank()) return false;
        String normalized = content.trim().toLowerCase();
        return TRANSFER_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /**
     * Analiza el sentimiento del mensaje usando AI para detectar frustración.
     * Retorna true si el cliente parece molesto/frustrado.
     */
    public boolean isFrustrated(String content, List<Map<String, String>> history) {
        if (content == null || content.isBlank()) return false;

        String sentimentPrompt = """
                Analiza SOLO el último mensaje del usuario y responde ÚNICAMENTE con una palabra:
                - "FRUSTRADO" si el cliente está molesto, enojado, frustrado, usa groserías, amenazas, o expresiones de inconformidad fuerte.
                - "NORMAL" si el mensaje es neutral o positivo.

                Ejemplos de FRUSTRADO: "esto es una mierda", "quiero mi dinero ya", "son unos estafadores", "hablen con el dueño YA", "me tienen harto"
                Ejemplos de NORMAL: "buenos días", "cuánto cuesta?", "quiero hacer una reserva", "gracias"

                Responde SOLO con una palabra: FRUSTRADO o NORMAL.
                """;

        try {
            OpenAIServiceClient.OpenAIResult result = openAIClient.complete(sentimentPrompt, history, content);
            String sentiment = result.getText().trim().toUpperCase();
            boolean frustrated = sentiment.contains("FRUSTRADO");
            if (frustrated) {
                log.info("[HumanTransfer] AI detected frustration: {}", content.substring(0, Math.min(50, content.length())));
            }
            return frustrated;
        } catch (Exception e) {
            log.warn("[HumanTransfer] Sentiment analysis failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifica si ha pasado el timeout desde la última respuesta del bot.
     * Si el cliente escribe después del timeout, sugiere transferencia.
     */
    public boolean isTimeoutTriggered(ConversationEntity conversation) {
        BusinessConfigEntity config = configRepository.findFirstByOrderByCreatedAtAsc().orElse(null);
        if (config == null || config.getHumanDelaySeconds() == null) return false;

        int delaySeconds = config.getHumanDelaySeconds();
        if (delaySeconds <= 0) return false;

        // Buscar el último mensaje OUTBOUND del bot
        var messages = conversation.getMessages();
        LocalDateTime lastBotMessage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getDirection() == com.whatsappmvp.domain.enums.MessageDirection.OUTBOUND
                && messages.get(i).getProcessedBy() != com.whatsappmvp.domain.enums.ProcessedBy.HUMAN) {
                lastBotMessage = messages.get(i).getSentAt();
                break;
            }
        }

        if (lastBotMessage == null) return false;

        long secondsSinceLastBot = java.time.Duration.between(lastBotMessage, LocalDateTime.now()).getSeconds();
        if (secondsSinceLastBot > delaySeconds) {
            log.info("[HumanTransfer] Timeout triggered: {}s since last bot response (limit: {}s)",
                    secondsSinceLastBot, delaySeconds);
            return true;
        }
        return false;
    }

    /**
     * Ejecuta la transferencia a humano:
     * 1. Cambia estado de conversación a HUMAN_TAKEOVER
     * 2. Notifica al dueño vía WhatsApp
     * 3. Responde al cliente confirmando
     */
    @Transactional
    public void transferToHuman(ConversationEntity conversation, String remoteJid,
                                String reason, String lastMessage) {
        log.info("[HumanTransfer] Transferring conversation {} to human. Reason: {}",
                conversation.getId(), reason);

        // 1. Cambiar estado
        conversation.setStatus(ConversationStatus.HUMAN_TAKEOVER);
        conversationRepository.save(conversation);

        // 2. Notificar al dueño vía WhatsApp
        notifyOwner(conversation, reason, lastMessage);

        // 3. Responder al cliente
        String clientMsg = switch (reason) {
            case "KEYWORD" -> "Claro, te comunico con un asesor ahora mismo. Un momento por favor. 🙋";
            case "FRUSTRATION" -> "Veo que estás molesto/a. Voy a conectarte con un asesor que te pueda ayudar mejor. Un momento. 🙋";
            case "TIMEOUT" -> "Parece que necesitas más ayuda. Te comunico con un asesor humano. Un momento. 🙋";
            case "AI_OFFER_ACCEPTED" -> "¡Claro! Te comunico con un asesor ahora mismo. Un momento por favor. 🙋";
            default -> "Un asesor te atenderá muy pronto. Un momento por favor. 🙋";
        };

        String targetJid = remoteJid != null && !remoteJid.isBlank()
                ? remoteJid
                : conversation.getContact().getPhone() + "@s.whatsapp.net";

        try {
            whatsAppClient.sendText(targetJid, clientMsg);
        } catch (Exception e) {
            log.error("[HumanTransfer] Failed to notify client: {}", e.getMessage());
        }
    }

    /**
     * Notifica al dueño vía WhatsApp cuando un cliente necesita atención humana.
     */
    private void notifyOwner(ConversationEntity conversation, String reason, String lastMessage) {
        String ownerPhone = configRepository.findFirstByOrderByCreatedAtAsc()
                .map(BusinessConfigEntity::getAdminPhone)
                .orElse(null);
        if (ownerPhone == null || ownerPhone.isBlank()) {
            log.warn("[HumanTransfer] No admin phone configured in business_config — skipping notification");
            return;
        }
        String customerName = conversation.getContact().getDisplayName() != null
                ? conversation.getContact().getDisplayName()
                : conversation.getContact().getPhone();

        String notification = String.format(
                "⚠️ *CLIENTE NECESITA ATENCIÓN HUMANA*\n\n" +
                "👤 Cliente: %s\n" +
                "📱 Teléfono: %s\n" +
                "💬 Último mensaje: %s\n" +
                "❓ Razón: %s\n\n" +
                "Responde desde el dashboard o directamente por WhatsApp.",
                customerName,
                conversation.getContact().getPhone(),
                lastMessage != null ? lastMessage.substring(0, Math.min(100, lastMessage.length())) : "N/A",
                reason
        );

        try {
            // Usar el phone del dueño con formato @s.whatsapp.net
            String ownerJid = ownerPhone.replaceAll("[^0-9]", "") + "@s.whatsapp.net";
            whatsAppClient.sendText(ownerJid, notification);
            log.info("[HumanTransfer] Owner notified at {}", ownerPhone);
        } catch (Exception e) {
            log.error("[HumanTransfer] Failed to notify owner at {}: {}", ownerPhone, e.getMessage());
        }
    }
}
