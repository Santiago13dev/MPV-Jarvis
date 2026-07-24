package com.whatsappmvp.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whatsappmvp.adapter.dto.request.ReservationRequest;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.infrastructure.persistence.entity.ConversationEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationFlowService {

    private final ConversationJpaRepository conversationRepository;
    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;

    public static final String ACTION_ASK_CONFIRMATION = "RESERVATION_ASK_CONFIRMATION";
    public static final String ACTION_COLLECT_DATA = "RESERVATION_COLLECT_DATA";
    public static final String ACTION_CANCEL_CONFIRM = "RESERVATION_CANCEL_CONFIRM";

    /**
     * Checks if the conversation has a pending reservation action.
     */
    public boolean hasPendingAction(ConversationEntity conversation) {
        return conversation.getPendingAction() != null && !conversation.getPendingAction().isBlank();
    }

    /**
     * Handles the next step of a reservation flow.
     * Returns Optional with the response text if handled, empty if not a reservation flow message.
     */
    @Transactional
    public Optional<String> handleStep(ConversationEntity conversation, String content, String phone, String displayName) {
        String action = conversation.getPendingAction();
        if (action == null || action.isBlank()) return Optional.empty();

        String normalized = content.trim().toLowerCase();

        return switch (action) {
            case ACTION_ASK_CONFIRMATION -> handleConfirmation(conversation, normalized, phone, displayName);
            case ACTION_COLLECT_DATA -> handleCollectData(conversation, content.trim(), phone, displayName);
            case ACTION_CANCEL_CONFIRM -> handleCancelConfirmation(conversation, normalized, phone);
            default -> {
                clearPendingAction(conversation);
                yield Optional.empty();
            }
        };
    }

    /**
     * Starts the reservation flow: asks SI/NO confirmation.
     */
    @Transactional
    public String startFlow(ConversationEntity conversation) {
        conversation.setPendingAction(ACTION_ASK_CONFIRMATION);
        conversation.setPendingActionData(null);
        conversationRepository.save(conversation);
        return "¿Deseas hacer una reserva? Responde *SI* o *NO*";
    }

    /**
     * Starts the cancellation flow: asks SI/NO confirmation.
     */
    @Transactional
    public String startCancelFlow(ConversationEntity conversation, String phone) {
        conversation.setPendingAction(ACTION_CANCEL_CONFIRM);
        ObjectNode data = objectMapper.createObjectNode();
        data.put("phone", phone);
        conversation.setPendingActionData(data.toString());
        conversationRepository.save(conversation);
        return "¿Deseas cancelar tu reserva? Responde *SI* o *NO*";
    }

    // ── Step: Confirmation (SI/NO) ──────────────────────────────────────

    private Optional<String> handleConfirmation(ConversationEntity conversation, String normalized, String phone, String displayName) {
        if (normalized.equals("si") || normalized.equals("sí")) {
            log.info("[ReservationFlow] User confirmed reservation — requesting data");
            conversation.setPendingAction(ACTION_COLLECT_DATA);
            ObjectNode data = objectMapper.createObjectNode();
            data.put("phone", phone);
            if (displayName != null && !displayName.isBlank()) {
                data.put("customerName", displayName);
            }
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return Optional.of(
                "Perfecto, por favor envíame los siguientes datos:\n\n" +
                "1. *Nombre completo*\n" +
                "2. *Fecha* (dd/mm/aaaa)\n" +
                "3. *Número de personas*\n" +
                "4. *Motivo de la reserva*\n" +
                "5. *Nombre del homenajeado* (si aplica, responde *NA* si no)\n" +
                "6. *Hora preferida* (ej: 12:00)\n\n" +
                "Envíalos todos juntos o uno por uno."
            );
        } else if (normalized.equals("no")) {
            log.info("[ReservationFlow] User declined reservation");
            clearPendingAction(conversation);
            return Optional.of("¡Perfecto! Si necesitas algo más, estoy aquí. 😊");
        } else {
            return Optional.of("Por favor responde *SI* o *NO*");
        }
    }

    // ── Step: Collect data ───────────────────────────────────────────────

    private Optional<String> handleCollectData(ConversationEntity conversation, String content, String phone, String displayName) {
        try {
            JsonNode dataNode = objectMapper.readTree(
                conversation.getPendingActionData() != null ? conversation.getPendingActionData() : "{}"
            );

            String storedPhone = dataNode.has("phone") ? dataNode.get("phone").asText() : phone;
            String storedName = dataNode.has("customerName") ? dataNode.get("customerName").asText() : null;

            // Try to parse all fields from the message
            // Format: name, date, people, motive, honoree, time
            String[] lines = content.split("\\n");
            String customerName = storedName;
            LocalDateTime reservationDate = null;
            Integer peopleCount = null;
            String motive = null;
            String honoree = null;
            String time = null;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                // Try numbered format: "1. Nombre"
                String value = trimmed;
                Pattern numPattern = Pattern.compile("^\\d+[.\\-)]\\s*(.+)$");
                Matcher m = numPattern.matcher(trimmed);
                if (m.matches()) {
                    value = m.group(1).trim();
                }

                String lower = value.toLowerCase();

                // Date pattern: dd/mm/aaaa or dd-mm-aaaa
                if (reservationDate == null) {
                    try {
                        reservationDate = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                        continue;
                    } catch (DateTimeParseException ignored) {}
                    try {
                        reservationDate = LocalDateTime.parse(value + " 12:00", DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
                        continue;
                    } catch (DateTimeParseException ignored) {}
                }

                // Time pattern: HH:mm
                if (time == null && lower.matches("\\d{1,2}:\\d{2}")) {
                    time = value;
                    continue;
                }

                // People count: just a number
                if (peopleCount == null && lower.matches("\\d+")) {
                    peopleCount = Integer.parseInt(lower);
                    continue;
                }

                // "NA" or "na" for honoree
                if (honoree == null && lower.equals("na")) {
                    honoree = "N/A";
                    continue;
                }

                // Heuristic: first non-date, non-time, non-number line = name; second = motive; third = honoree
                if (customerName == null || customerName.isBlank()) {
                    customerName = value;
                } else if (motive == null) {
                    motive = value;
                } else if (honoree == null) {
                    honoree = value;
                }
            }

            // Validate required fields
            if (customerName == null || customerName.isBlank()) {
                return Optional.of("Necesito tu *nombre completo*. Por favor envíalo.");
            }
            if (reservationDate == null) {
                return Optional.of("Necesito la *fecha* de la reserva (formato: dd/mm/aaaa). Por favor envíala.");
            }

            // Create reservation
            ReservationRequest req = new ReservationRequest();
            req.setCustomerName(customerName);
            req.setPhoneNumber(storedPhone);
            req.setReservationDate(reservationDate);
            req.setAmount(new BigDecimal("50.00"));
            req.setStatus(ReservationStatus.CONFIRMADA);

            StringBuilder notes = new StringBuilder();
            if (peopleCount != null) notes.append("Personas: ").append(peopleCount).append(". ");
            if (motive != null) notes.append("Motivo: ").append(motive).append(". ");
            if (honoree != null) notes.append("Homenajeado: ").append(honoree).append(". ");
            if (time != null) notes.append("Hora: ").append(time).append(".");
            req.setNotes(notes.length() > 0 ? notes.toString() : null);

            reservationService.createReservation(req);
            clearPendingAction(conversation);

            String timeInfo = time != null ? " a las " + time : "";
            String response = String.format(
                "✅ *¡Reserva confirmada!*\n\n" +
                "👤 %s\n" +
                "📅 %s%s\n" +
                "💰 $50.00\n\n" +
                "Gracias por tu reserva. ¡Te esperamos! 🎉",
                customerName,
                reservationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                timeInfo
            );

            log.info("[ReservationFlow] Reservation created for {} on {}", customerName, reservationDate);
            return Optional.of(response);

        } catch (Exception e) {
            log.error("[ReservationFlow] Error parsing reservation data: {}", e.getMessage());
            clearPendingAction(conversation);
            return Optional.of("Hubo un error procesando tu reserva. Por favor intenta de nuevo escribiendo *reservar*.");
        }
    }

    // ── Step: Cancel confirmation ────────────────────────────────────────

    private Optional<String> handleCancelConfirmation(ConversationEntity conversation, String normalized, String phone) {
        if (normalized.equals("si") || normalized.equals("sí")) {
            log.info("[ReservationFlow] User confirmed cancellation");
            try {
                var cancelled = reservationService.cancelLatestByPhoneNumber(phone);
                clearPendingAction(conversation);
                if (cancelled.isPresent()) {
                    var r = cancelled.get();
                    String dateStr = r.getReservationDate() != null
                        ? r.getReservationDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        : "N/A";
                    return Optional.of(String.format(
                        "✅ *Reserva cancelada*\n\n" +
                        "👤 %s\n" +
                        "📅 %s\n\n" +
                        "Si necesitas hacer una nueva reserva, escribe *reservar*.",
                        r.getCustomerName(), dateStr
                    ));
                } else {
                    return Optional.of("No encontré ninguna reserva activa a tu nombre. Si necesitas hacer una nueva reserva, escribe *reservar*.");
                }
            } catch (Exception e) {
                log.error("[ReservationFlow] Error cancelling reservation: {}", e.getMessage());
                clearPendingAction(conversation);
                return Optional.of("Hubo un error al cancelar tu reserva. Por favor intenta de nuevo.");
            }
        } else if (normalized.equals("no")) {
            log.info("[ReservationFlow] User declined cancellation");
            clearPendingAction(conversation);
            return Optional.of("¡Perfecto! Tu reserva sigue activa. 😊");
        } else {
            return Optional.of("Por favor responde *SI* o *NO*");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    @Transactional
    public void clearPendingAction(ConversationEntity conversation) {
        conversation.setPendingAction(null);
        conversation.setPendingActionData(null);
        conversationRepository.save(conversation);
    }
}
