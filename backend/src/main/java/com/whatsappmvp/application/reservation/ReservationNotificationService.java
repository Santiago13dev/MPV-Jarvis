package com.whatsappmvp.application.reservation;

import com.whatsappmvp.config.AppProperties;
import com.whatsappmvp.domain.enums.MessageDirection;
import com.whatsappmvp.domain.enums.MessageType;
import com.whatsappmvp.domain.enums.ProcessedBy;
import com.whatsappmvp.infrastructure.client.WhatsAppServiceClient;
import com.whatsappmvp.infrastructure.persistence.entity.BusinessConfigEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ConversationEntity;
import com.whatsappmvp.infrastructure.persistence.entity.MessageEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.BusinessConfigJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ContactJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.MessageJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Atiende los eventos de dominio DESPUÉS del commit: avisa por WhatsApp al cliente (confirmación,
 * comprobante rechazado, vencimiento, cancelación por el restaurante) y al administrador (comprobante nuevo).
 * Nunca se notifica algo que luego hizo rollback, y el envío (HTTP) no bloquea la transacción.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationNotificationService {

    private final ReservationJpaRepository reservationRepository;
    private final ContactJpaRepository contactRepository;
    private final ConversationJpaRepository conversationRepository;
    private final MessageJpaRepository messageRepository;
    private final BusinessConfigJpaRepository configRepository;
    private final WhatsAppServiceClient whatsApp;
    private final ReservationMessages messages;
    private final AppProperties props;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reservation-notifier");
        t.setDaemon(true);
        return t;
    });

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvent(ReservationNotificationEvent event) {
        executor.submit(() -> {
            try {
                dispatch(event);
            } catch (Exception e) {
                log.error("[RESERVATION] notification failed type={} reservationId={}: {}",
                        event.type(), event.reservationId(), e.getMessage());
            }
        });
    }

    private void dispatch(ReservationNotificationEvent e) {
        ReservationEntity r = reservationRepository.findById(e.reservationId()).orElse(null);
        if (r == null) return;
        switch (e.type()) {
            case CONFIRMED -> {
                toCustomer(r, messages.confirmedNotification(r));
                // Resumen también al número configurado para el comprobante, si es distinto
                if (r.getReceiptPhone() != null && !digits(r.getReceiptPhone()).equals(digits(r.getPhoneNumber()))) {
                    sendRaw(toJid(r.getReceiptPhone()), messages.confirmedNotification(r));
                }
            }
            case RECEIPT_REJECTED -> toCustomer(r, messages.receiptRejectedNotification(r, e.note()));
            case EXPIRED -> toCustomer(r, messages.expiredNotification(r));
            case CANCELLED_BY_ADMIN -> toCustomer(r, messages.cancelledByAdminNotification(r, e.note()));
            case RECEIPT_RECEIVED -> toAdmin(messages.adminReceiptReceived(r));
        }
    }

    private void toCustomer(ReservationEntity r, String text) {
        ConversationEntity conv = contactRepository.findByPhone(r.getPhoneNumber())
                .flatMap(c -> conversationRepository.findByContactIdAndIsDeletedFalse(c.getId()))
                .orElse(null);
        String jid = conv != null && conv.getRemoteJid() != null && !conv.getRemoteJid().isBlank()
                ? conv.getRemoteJid() : toJid(r.getPhoneNumber());
        if (sendRaw(jid, text) && conv != null) {
            // Queda en el historial de la conversación para que el asesor lo vea en el dashboard
            messageRepository.save(MessageEntity.builder()
                    .conversation(conv)
                    .direction(MessageDirection.OUTBOUND)
                    .content(text)
                    .messageType(MessageType.TEXT)
                    .processedBy(ProcessedBy.SYSTEM)
                    .status("SENT")
                    .aiTokensUsed(0)
                    .build());
        }
    }

    private void toAdmin(String text) {
        String phone = configRepository.findFirstByOrderByCreatedAtAsc()
                .map(BusinessConfigEntity::getAdminPhone)
                .filter(p -> p != null && !p.isBlank())
                .orElse(props.getAdmin().getPhone());
        if (phone == null || phone.isBlank()) {
            log.warn("[RESERVATION] no admin phone configured — admin notification skipped");
            return;
        }
        sendRaw(toJid(phone), text);
    }

    private boolean sendRaw(String jid, String text) {
        try {
            whatsApp.sendText(jid, text);
            return true;
        } catch (Exception ex) {
            log.error("[RESERVATION] WhatsApp send failed to={}: {}", jid, ex.getMessage());
            return false;
        }
    }

    private String toJid(String phone) {
        String d = digits(phone);
        // Celular colombiano de 10 dígitos sin indicativo
        if (d.length() == 10 && d.startsWith("3")) d = "57" + d;
        return d + "@s.whatsapp.net";
    }

    private String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }
}
