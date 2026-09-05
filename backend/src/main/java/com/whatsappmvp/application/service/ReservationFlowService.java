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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
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
    private final AIService aiService;

    public static final String ACTION_COLLECTING = "RESERVATION_COLLECTING";
    public static final String ACTION_CANCEL_CONFIRM = "RESERVATION_CANCEL_CONFIRM";

    private static final ZoneId COLOMBIA_TZ = ZoneId.of("America/Bogota");

    private LocalDate nowColombia() {
        return LocalDate.now(COLOMBIA_TZ);
    }

    public boolean hasPendingAction(ConversationEntity conversation) {
        if (conversation.getPendingAction() == null || conversation.getPendingAction().isBlank()) return false;
        // Auto-expirar acciones pendientes mayores a 30 minutos
        if (conversation.getLastMessageAt() != null) {
            long minutesSinceLastMessage = java.time.Duration.between(
                conversation.getLastMessageAt(), LocalDateTime.now()).toMinutes();
            if (minutesSinceLastMessage > 30) {
                log.info("[ReservationFlow] Pending action expired ({} min since last message)", minutesSinceLastMessage);
                clearPendingAction(conversation);
                return false;
            }
        }
        return true;
    }

    @Transactional
    public Optional<String> handleStep(ConversationEntity conversation, String content, String phone, String displayName) {
        String action = conversation.getPendingAction();
        if (action == null || action.isBlank()) return Optional.empty();

        String normalized = content.trim().toLowerCase();

        return switch (action) {
            case ACTION_COLLECTING -> handleCollecting(conversation, content.trim(), phone, displayName);
            case ACTION_CANCEL_CONFIRM -> handleCancelConfirmation(conversation, normalized, phone);
            default -> {
                clearPendingAction(conversation);
                yield Optional.empty();
            }
        };
    }

    @Transactional
    public String startFlow(ConversationEntity conversation, String content, String displayName) {
        // Use smartParse for aggressive extraction (regex + LLM fallback)
        ParsedReservation parsed = smartParse(content, displayName);

        ObjectNode data = objectMapper.createObjectNode();
        data.put("phone", conversation.getContact().getPhone());
        if (parsed.customerName != null && !parsed.customerName.isBlank()) data.put("customerName", parsed.customerName);
        if (parsed.parsedDate != null) data.put("parsedDate", parsed.parsedDate.toString());
        if (parsed.peopleCount > 0) data.put("peopleCount", parsed.peopleCount);
        if (parsed.motive != null) data.put("motive", parsed.motive);
        if (parsed.honoree != null) data.put("honoree", parsed.honoree);
        if (parsed.time != null) data.put("time", parsed.time);

        // Handle "mañana" + not Saturday
        if (parsed.mananaNotSaturday) {
            LocalDate nextSat = findNextSaturday();
            LocalDate tomorrow = nowColombia().plusDays(1);
            data.put("parsedDate", nextSat.toString());
            conversation.setPendingAction(ACTION_COLLECTING);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return String.format(
                "Mañana (%s %s) no hacemos reservas.\n\n" +
                "Solo hacemos reservas los sábados.\n\n" +
                "El próximo sábado es: *%s*\n\n" +
                "¿Quieres reservar para ese día? Responde *SI* o *NO*, o envía los datos de tu reserva.",
                getDayName(tomorrow.getDayOfWeek()),
                tomorrow.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            );
        }

        // Handle day name mentioned (not Saturday)
        if (parsed.dayNameMentioned != null && parsed.parsedDate == null) {
            conversation.setPendingAction(ACTION_COLLECTING);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return buildDayNotAvailableMessage(parsed.dayNameMentioned);
        }

        // Handle specific date that's not Saturday
        if (parsed.parsedDate != null && parsed.parsedDate.getDayOfWeek() != DayOfWeek.SATURDAY) {
            LocalDate nextSat = findNextSaturday();
            String dayName = getDayName(parsed.parsedDate.getDayOfWeek());
            data.put("parsedDate", nextSat.toString());
            conversation.setPendingAction(ACTION_COLLECTING);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return String.format(
                "Solo hacemos reservas los sábados.\n\n" +
                "La fecha que mencionaste (%s %s) no aplica.\n\n" +
                "El próximo sábado es: *%s*\n\n" +
                "¿Quieres reservar para ese día? Responde *SI* o *NO*, o envía los datos de tu reserva.",
                dayName,
                parsed.parsedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            );
        }

        conversation.setPendingAction(ACTION_COLLECTING);
        conversation.setPendingActionData(data.toString());
        conversationRepository.save(conversation);

        boolean hasName = parsed.customerName != null && !parsed.customerName.isBlank();
        boolean hasDate = parsed.parsedDate != null;
        boolean hasPeople = parsed.peopleCount > 0;
        boolean hasTime = parsed.time != null;

        // If ALL required fields present, show confirmation summary
        if (hasName && hasDate && hasPeople && hasTime) {
            return buildConfirmationSummary(data, conversation.getContact().getPhone());
        }

        // If SOME fields present, show what was detected and what's missing
        if (hasDate || hasPeople || hasTime || hasName) {
            return buildCollectingMessage(parsed);
        }

        // No fields detected - ask for all
        return "¡Claro! Para hacer tu reserva necesito algunos datos:\n\n" +
               "📅 Fecha (solo sábados)\n" +
               "👥 Número de personas\n" +
               "🕐 Hora (11:30, 12:00, 12:30 o 13:00)\n" +
               "👤 Tu nombre\n" +
               "🎂 Motivo (cumpleaños, reunión, etc.)\n\n" +
               "Envíalos todos juntos o uno por uno.";
    }

    @Transactional
    public String startCancelFlow(ConversationEntity conversation, String phone) {
        conversation.setPendingAction(ACTION_CANCEL_CONFIRM);
        ObjectNode data = objectMapper.createObjectNode();
        data.put("phone", phone);
        conversation.setPendingActionData(data.toString());
        conversationRepository.save(conversation);
        return "Deseas cancelar tu reserva? Se eliminara del sistema. Responde *SI* o *NO*";
    }

    // ── Step: Collect data (unified) ──────────────────────────────────────

    private Optional<String> handleCollecting(ConversationEntity conversation, String content, String phone, String displayName) {
        String normalized = content.trim().toLowerCase();

        if (normalized.equals("si") || normalized.equals("sí")) {
            log.info("[ReservationFlow] User confirmed - checking if we can create reservation");
            ObjectNode data = readData(conversation);
            data.put("phone", phone);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);

            boolean hasName = data.has("customerName") && !data.get("customerName").asText().isBlank();
            boolean hasDate = data.has("parsedDate") && !data.get("parsedDate").isNull();
            boolean hasPeople = data.has("peopleCount") && data.get("peopleCount").asInt() > 0;
            boolean hasTime = data.has("time") && !data.get("time").isNull();

            if (hasName && hasDate && hasPeople && hasTime) {
                return createReservationFromData(conversation, phone);
            } else {
                // Show confirmation summary with what we have, asking for missing
                return Optional.of(buildConfirmationSummaryWithMissing(data));
            }
        }

        if (normalized.equals("no")) {
            log.info("[ReservationFlow] User cancelled reservation flow");
            clearPendingAction(conversation);
            return Optional.of("¡Perfecto! Si necesitas algo más, estoy aquí.");
        }

        // ── Detectar si el mensaje es una pregunta, saludo o mensaje conversacional ──
        // NO limpiar el pending action - mantener el flujo de reserva
        boolean isQuestion = normalized.contains("?") || normalized.contains("¿");
        boolean isGreeting = normalized.equals("hola") || normalized.equals("buenos dias") ||
                normalized.equals("buenas tardes") || normalized.equals("buenas noches") ||
                normalized.equals("buen dia") || normalized.equals("ok") || normalized.equals("gracias") ||
                normalized.equals("entendido") || normalized.equals("va") || normalized.equals("dale");

        // Detectar si NO es datos de reserva (usar isNotReservationData que ya existe)
        boolean isNotData = isNotReservationData(normalized);

        // Detectar si es una INTENCIÓN de agregar más personas (e.g. "llevar 3 más", "agregar 2")
        boolean isAddPeople = normalized.contains("más") || normalized.contains("mas") ||
                normalized.contains("agregar") || normalized.contains("adicionar") || normalized.contains("adición");

        if (isNotData && !isAddPeople) {
            log.info("[ReservationFlow] Non-reservation message detected during flow - answering as question");
            Optional<String> interruptionResponse = handleInterruption(conversation, content, phone, displayName);
            if (interruptionResponse.isPresent()) {
                return interruptionResponse;
            }
        }

        // Check if message also contains reservation data (e.g. "quiero reservar para mañana 10 personas?")
        boolean hasReservationDataInQuestion = isQuestion && (
                normalized.contains("reserv") || normalized.contains("mañana") || normalized.contains("manana") ||
                normalized.contains("sabado") || normalized.contains("sábado") ||
                normalized.contains("personas") || normalized.contains("somos") ||
                parsePeopleCount(normalized, normalized) != null ||
                parseDateFromText(normalized) != null ||
                parseTime(normalized, normalized) != null
        );

        if ((isQuestion || isGreeting) && !hasReservationDataInQuestion && !isAddPeople) {
            log.info("[ReservationFlow] Question/greeting detected during flow - preserving reservation context");
            Optional<String> interruptionResponse = handleInterruption(conversation, content, phone, displayName);
            if (interruptionResponse.isPresent()) {
                return interruptionResponse;
            }
        }

        // ── Detectar "llevar X más" / "agregar X" — sumar al conteo existente ──
        if (isAddPeople) {
            Pattern addPattern = Pattern.compile("(?:llevar|agregar|adicionar|sumar)\\s+(\\d+)\\s*(?:más|mas|personas?|ademas|además)?");
            Matcher addMatcher = addPattern.matcher(normalized);
            if (addMatcher.find()) {
                int additionalPeople = Integer.parseInt(addMatcher.group(1));
                ObjectNode dataNode = readData(conversation);
                int existingCount = dataNode.has("peopleCount") ? dataNode.get("peopleCount").asInt() : 0;
                int newCount = existingCount + additionalPeople;
                dataNode.put("peopleCount", newCount);
                conversation.setPendingActionData(dataNode.toString());
                conversationRepository.save(conversation);
                log.info("[ReservationFlow] Adding {} people to existing {} = {}", additionalPeople, existingCount, newCount);

                // Build status with ALL collected data
                StringBuilder status = new StringBuilder();
                status.append("¡Listo! Actualicé tu reserva a *").append(newCount).append(" personas*.\n\n");
                status.append("📝 Detecté:\n");
                if (dataNode.has("customerName") && !dataNode.get("customerName").asText().isBlank()) {
                    status.append("- Nombre: ").append(dataNode.get("customerName").asText()).append("\n");
                }
                status.append("- Personas: ").append(newCount).append("\n");
                if (dataNode.has("parsedDate") && !dataNode.get("parsedDate").isNull()) {
                    status.append("- Fecha: ").append(dataNode.get("parsedDate").asText()).append("\n");
                }
                if (dataNode.has("time") && !dataNode.get("time").isNull()) {
                    status.append("- Hora: ").append(dataNode.get("time").asText()).append("\n");
                }
                if (dataNode.has("motive") && !dataNode.get("motive").isNull()) {
                    status.append("- Motivo: ").append(dataNode.get("motive").asText()).append("\n");
                }
                status.append("\n⏳ Falta:\n");
                if (!dataNode.has("parsedDate") || dataNode.get("parsedDate").isNull()) {
                    status.append("- *Fecha* (solo sábados)\n");
                }
                if (!dataNode.has("time") || dataNode.get("time").isNull()) {
                    status.append("- *Hora* (11:30, 12:00, 12:30 o 1:00 PM)\n");
                }
                status.append("\nEnvíame los datos faltantes o pregunta lo que necesites.");
                return Optional.of(status.toString());
            }
        }

        // Use smartParse for better extraction (regex + LLM fallback)
        try {
            ParsedReservation smartParsed = smartParse(content, displayName);

            // Check for day name issues first
            if (smartParsed.dayNameMentioned != null) {
                return Optional.of(buildDayNotAvailableMessage(smartParsed.dayNameMentioned));
            }

            // Read existing data and merge
            ObjectNode dataNode = readData(conversation);
            String storedPhone = dataNode.has("phone") ? dataNode.get("phone").asText() : phone;

            // Merge: new data overwrites, existing preserved
            String customerName = smartParsed.customerName;
            if ((customerName == null || customerName.isBlank()) && dataNode.has("customerName")) {
                customerName = dataNode.get("customerName").asText();
            }
            if (customerName == null && displayName != null && !displayName.isBlank()) {
                customerName = displayName;
            }

            LocalDate reservationDate = smartParsed.parsedDate;
            if (reservationDate == null && dataNode.has("parsedDate") && !dataNode.get("parsedDate").isNull()) {
                try { reservationDate = LocalDate.parse(dataNode.get("parsedDate").asText()); } catch (Exception ignored) {}
            }

            Integer peopleCount = smartParsed.peopleCount > 0 ? smartParsed.peopleCount : null;
            if (peopleCount == null && dataNode.has("peopleCount") && dataNode.get("peopleCount").asInt() > 0) {
                peopleCount = dataNode.get("peopleCount").asInt();
            }

            String time = smartParsed.time;
            if (time == null && dataNode.has("time") && !dataNode.get("time").isNull()) {
                time = dataNode.get("time").asText();
            }

            String motive = smartParsed.motive;
            if (motive == null && dataNode.has("motive") && !dataNode.get("motive").isNull()) {
                motive = dataNode.get("motive").asText();
            }

            String honoree = smartParsed.honoree;
            if (honoree == null && dataNode.has("honoree") && !dataNode.get("honoree").isNull()) {
                honoree = dataNode.get("honoree").asText();
            }

            // Validate time if provided
            if (time != null && !isValidTime(time)) {
                return Optional.of(
                    "Los horarios disponibles son:\n\n" +
                    "11:30 a.m.\n12:00 p.m.\n12:30 p.m.\n1:00 p.m.\n\n" +
                    "Por favor elige uno de estos horarios."
                );
            }

            // Validate date if provided
            if (reservationDate != null && reservationDate.getDayOfWeek() != DayOfWeek.SATURDAY) {
                LocalDate nextSat = findNextSaturday();
                String day = getDayName(reservationDate.getDayOfWeek());
                return Optional.of(String.format(
                    "Solo hacemos reservas los sábados.\n\n" +
                    "La fecha que indicaste (%s %s) no aplica.\n\n" +
                    "El próximo sábado es: *%s*\n\n" +
                    "Por favor envía la fecha correcta.",
                    day,
                    reservationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                ));
            }

            // Save merged data
            ObjectNode updatedData = objectMapper.createObjectNode();
            updatedData.put("phone", storedPhone);
            if (customerName != null && !customerName.isBlank()) updatedData.put("customerName", customerName);
            if (reservationDate != null) updatedData.put("parsedDate", reservationDate.toString());
            if (peopleCount != null && peopleCount > 0) updatedData.put("peopleCount", peopleCount);
            if (time != null) updatedData.put("time", time);
            if (motive != null) updatedData.put("motive", motive);
            if (honoree != null) updatedData.put("honoree", honoree);
            conversation.setPendingActionData(updatedData.toString());
            conversationRepository.save(conversation);

            // Check completeness
            boolean hasName = customerName != null && !customerName.isBlank();
            boolean hasDate = reservationDate != null;
            boolean hasPeople = peopleCount != null && peopleCount > 0;
            boolean hasTime = time != null;

            // If all required fields present, show confirmation summary
            if (hasName && hasDate && hasPeople && hasTime) {
                return Optional.of(buildConfirmationSummary(updatedData, phone));
            }

            // Show what was detected and what's missing
            ParsedReservation parsedResponse = new ParsedReservation();
            parsedResponse.customerName = customerName;
            parsedResponse.parsedDate = reservationDate;
            parsedResponse.peopleCount = peopleCount != null ? peopleCount : 0;
            parsedResponse.motive = motive;
            parsedResponse.honoree = honoree;
            parsedResponse.time = time;

            return Optional.of(buildCollectingMessage(parsedResponse));

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
                        "Reserva eliminada\n\n%s\n%s\n\nSi necesitas hacer una nueva reserva, escribe *reservar*.",
                        r.getCustomerName(), dateStr
                    ));
                } else {
                    return Optional.of("No encontre ninguna reserva activa a tu nombre. Si necesitas hacer una nueva reserva, escribe *reservar*.");
                }
            } catch (Exception e) {
                log.error("[ReservationFlow] Error cancelling reservation: {}", e.getMessage());
                clearPendingAction(conversation);
                return Optional.of("Hubo un error al cancelar tu reserva. Por favor intenta de nuevo.");
            }
        } else if (normalized.equals("no")) {
            log.info("[ReservationFlow] User declined cancellation");
            clearPendingAction(conversation);
            return Optional.of("¡Perfecto! Tu reserva sigue activa.");
        } else {
            return Optional.of("Por favor responde *SI* o *NO*");
        }
    }

    // ── Step: Modify existing reservation ──────────────────────────────

    public String handleModification(ConversationEntity conversation, String content, String phone) {
        String normalized = content != null ? content.trim().toLowerCase() : "";

        // Detectar si es adición ("llevar 2 más") o reemplazo ("cambiar a 5 personas")
        boolean isAdditive = (normalized.contains("llevar") || normalized.contains("agregar") ||
                normalized.contains("adicionar") || normalized.contains("sumar")) &&
                (normalized.contains("más") || normalized.contains("mas"));

        // Parsear cantidad de personas del mensaje
        Integer parsedCount = parsePeopleCount(normalized, normalized);

        if (parsedCount != null && parsedCount > 0) {
            try {
                if (isAdditive) {
                    // Sumar al conteo existente
                    var updated = reservationService.addPeopleToLatest(phone, parsedCount);
                    if (updated.isPresent()) {
                        var r = updated.get();
                        String dateStr = r.getReservationDate() != null
                            ? r.getReservationDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                            : "N/A";
                        return String.format(
                            "¡Listo! Actualicé tu reserva:\n\n" +
                            "👤 %s\n📅 %s\n👥 %d personas (agregué %d)\n\n" +
                            "¡Confirmado!",
                            r.getCustomerName(), dateStr, r.getPeopleCount(), parsedCount
                        );
                    } else {
                        return "No encontré ninguna reserva activa a tu nombre. Si deseas hacer una nueva reserva, escribe *reservar*.";
                    }
                } else {
                    // Reemplazo directo
                    var updated = reservationService.updateLatestPeopleCount(phone, parsedCount);
                    if (updated.isPresent()) {
                        var r = updated.get();
                        String dateStr = r.getReservationDate() != null
                            ? r.getReservationDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                            : "N/A";
                        return String.format(
                            "Reserva actualizada\n\n%s\n%s\nPersonas: %d\n\n¡Confirmado!",
                            r.getCustomerName(), dateStr, r.getPeopleCount() != null ? r.getPeopleCount() : parsedCount
                        );
                    } else {
                        return "No encontré ninguna reserva activa a tu nombre. Si deseas hacer una nueva reserva, escribe *reservar*.";
                    }
                }
            } catch (Exception e) {
                log.error("[ReservationFlow] Error updating reservation: {}", e.getMessage());
                return "Hubo un error al actualizar tu reserva. Por favor intenta de nuevo.";
            }
        }

        return "¿Cuántas personas serán? Por ejemplo: *somos 10 personas*";
    }

    // ── Helper: Create reservation from stored data ────────────────────

    private Optional<String> createReservationFromData(ConversationEntity conversation, String phone) {
        try {
            ObjectNode data = readData(conversation);

            String customerName = data.has("customerName") ? data.get("customerName").asText() : null;
            String dateStr = data.has("parsedDate") ? data.get("parsedDate").asText() : null;
            int peopleCount = data.has("peopleCount") ? data.get("peopleCount").asInt() : 0;
            String motive = data.has("motive") && !data.get("motive").isNull() ? data.get("motive").asText() : null;
            String honoree = data.has("honoree") && !data.get("honoree").isNull() ? data.get("honoree").asText() : null;
            String time = data.has("time") && !data.get("time").isNull() ? data.get("time").asText() : null;
            String storedPhone = data.has("phone") ? data.get("phone").asText() : phone;

            if (customerName == null || customerName.isBlank()) {
                return Optional.of("Necesito tu *nombre completo*. Por favor envialo.");
            }
            if (dateStr == null) {
                LocalDate nextSat = findNextSaturday();
                return Optional.of(String.format(
                    "Necesito la *fecha* de la reserva.\n\nSolo hacemos reservas los sabados. El proximo es: *%s*\n\nPor favor enviala.",
                    nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                ));
            }

            LocalDate reservationDate;
            try {
                reservationDate = LocalDate.parse(dateStr);
            } catch (Exception e) {
                return Optional.of("La fecha no es valida. Por favor usa el formato dd/mm/aaaa.");
            }

            if (reservationDate.getDayOfWeek() != DayOfWeek.SATURDAY) {
                LocalDate nextSat = findNextSaturday();
                return Optional.of(String.format(
                    "Solo hacemos reservas los sabados.\n\nLa fecha (%s) no aplica.\nEl proximo sabado es: *%s*",
                    reservationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                ));
            }

            ReservationRequest req = new ReservationRequest();
            req.setCustomerName(customerName);
            req.setPhoneNumber(storedPhone);
            req.setReservationDate(reservationDate.atTime(time != null ? parseTimeToLocalTime(time) : LocalTime.of(12, 0)));
            req.setAmount(new BigDecimal("40000.00"));
            req.setStatus(ReservationStatus.CONFIRMADA);
            req.setPeopleCount(peopleCount > 0 ? peopleCount : null);

            StringBuilder notes = new StringBuilder();
            if (motive != null) notes.append("Motivo: ").append(motive).append(". ");
            if (honoree != null) notes.append("Homenajeado: ").append(honoree).append(". ");
            if (time != null) notes.append("Hora: ").append(time).append(".");
            req.setNotes(notes.length() > 0 ? notes.toString() : null);

            reservationService.createReservation(req);
            clearPendingAction(conversation);

            String timeDisplay = time != null ? formatTimeDisplay(time) : "";
            String dateDisplay = reservationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

            String response = String.format(
                "¡Reserva confirmada! 🎉\n\n" +
                "👤 %s\n" +
                "📅 Sábado %s\n" +
                "%s%s\n" +
                "💰 Depósito: $40.000 COP\n\n" +
                "¡Te esperamos en La Montaña del Bendito Chicharrón!\n" +
                "Recuerda: el depósito de $40.000 se genera automáticamente.",
                customerName,
                dateDisplay,
                timeDisplay.isEmpty() ? "" : "🕐 " + timeDisplay + "\n",
                peopleCount > 0 ? "👥 " + peopleCount + " personas" : "",
                motive != null ? "\n🎂 " + motive : ""
            );

            log.info("[ReservationFlow] Reservation created for {} on Saturday {}", customerName, reservationDate);
            return Optional.of(response);

        } catch (Exception e) {
            log.error("[ReservationFlow] Error creating reservation: {}", e.getMessage());
            clearPendingAction(conversation);
            return Optional.of("Hubo un error procesando tu reserva. Por favor intenta de nuevo escribiendo *reservar*.");
        }
    }

    // ── Smart parsing (regex + LLM fallback) ──────────────────────────────

    /**
     * Intenta extraer datos de reserva usando regex primero (rápido, sin costo).
     * Si falta algún campo, usa LLM como fallback.
     * Retorna un ParsedReservation con todos los campos disponibles.
     */
    private ParsedReservation smartParse(String message, String displayName) {
        ParsedReservation parsed = new ParsedReservation();
        parsed.customerName = displayName;

        String normalized = message.trim().toLowerCase();

        // 1. Intentar regex primero (rápido, sin costo de API)
        parsed.dayNameMentioned = detectDayName(normalized);
        parsed.mananaNotSaturday = isMananaMentioned(normalized) && !isMananaSaturday();
        parsed.parsedDate = parseDateFromText(normalized);

        Integer parsedPeople = parsePeopleCount(normalized, normalized);
        parsed.peopleCount = parsedPeople != null ? parsedPeople : 0;
        parsed.time = parseTime(normalized, normalized);
        parsed.motive = parseMotive(normalized);

        // 2. Detectar nombre del mensaje
        String detectedName = detectNameFromText(normalized, message);
        if (detectedName != null) {
            parsed.customerName = detectedName;
        }

        // 3. Verificar si regex extrajo todo lo necesario
        boolean hasDate = parsed.parsedDate != null;
        boolean hasPeople = parsed.peopleCount > 0;
        boolean hasTime = parsed.time != null;
        boolean hasName = parsed.customerName != null && !parsed.customerName.isBlank();

        // Si regex extrajo todo, retornar sin llamar LLM
        if (hasDate && hasPeople && hasTime && hasName) {
            log.info("[ReservationFlow] Regex extracted all fields, skipping LLM");
            return parsed;
        }

        // 4. Si falta algún campo, usar LLM como fallback
        log.info("[ReservationFlow] Regex missing fields (date={}, people={}, time={}, name={}), trying LLM",
                hasDate, hasPeople, hasTime, hasName);

        try {
            Map<String, Object> llmData = aiService.extractReservationData(message);

            // 5. Merge: regex tiene prioridad sobre LLM (más confiable)
            if (!hasDate && llmData.containsKey("date")) {
                String dateStr = (String) llmData.get("date");
                if (dateStr != null && !dateStr.isBlank()) {
                    try {
                        LocalDate llmDate = LocalDate.parse(dateStr);
                        if (llmDate.getDayOfWeek() == DayOfWeek.SATURDAY) {
                            parsed.parsedDate = llmDate;
                        }
                    } catch (Exception e) {
                        log.warn("[ReservationFlow] LLM returned invalid date: {}", dateStr);
                    }
                }
            }

            if (parsed.peopleCount == 0 && llmData.containsKey("peopleCount")) {
                Object countObj = llmData.get("peopleCount");
                if (countObj instanceof Number num && num.intValue() > 0) {
                    parsed.peopleCount = num.intValue();
                }
            }

            if (parsed.time == null && llmData.containsKey("time")) {
                String timeStr = (String) llmData.get("time");
                if (timeStr != null && isValidTime(timeStr)) {
                    parsed.time = timeStr;
                }
            }

            if ((parsed.customerName == null || parsed.customerName.isBlank()) && llmData.containsKey("customerName")) {
                String name = (String) llmData.get("customerName");
                if (name != null && !name.isBlank()) {
                    parsed.customerName = name;
                }
            }

            if (parsed.motive == null && llmData.containsKey("motive")) {
                String motive = (String) llmData.get("motive");
                if (motive != null && !motive.isBlank()) {
                    parsed.motive = motive;
                }
            }

            if (parsed.honoree == null && llmData.containsKey("honoree")) {
                String honoree = (String) llmData.get("honoree");
                if (honoree != null && !honoree.isBlank()) {
                    parsed.honoree = honoree;
                }
            }

        } catch (Exception e) {
            log.error("[ReservationFlow] LLM extraction failed: {}", e.getMessage());
        }

        return parsed;
    }

    // ── Interruption handling (questions during reservation) ──────────────

    /**
     * Detecta si el mensaje es una pregunta durante el flujo de reserva.
     * Responde la pregunta y pide confirmar si continuar con la reserva.
     * NO limpia el pendingAction.
     */
    private Optional<String> handleInterruption(ConversationEntity conversation, String message, String phone, String displayName) {
        String normalized = message.trim().toLowerCase();

        // Detectar si es una pregunta clara
        boolean isQuestion = normalized.contains("?") || normalized.contains("¿");

        // Detectar si es un saludo o mensaje vacío
        boolean isGreeting = normalized.equals("hola") || normalized.equals("buenos dias") ||
                normalized.equals("buenas tardes") || normalized.equals("buenas noches") ||
                normalized.equals("buen dia") || normalized.equals("ok") || normalized.equals("gracias") ||
                normalized.equals("entendido") || normalized.equals("va") || normalized.equals("dale");

        // Detectar si es una confirmación/cancelación explícita
        boolean isExplicitConfirm = normalized.equals("si") || normalized.equals("sí");
        boolean isExplicitCancel = normalized.equals("no") || normalized.contains("cancelar") ||
                normalized.contains("eliminar") || normalized.contains("olvídelo") || normalized.contains("olvídalo");

        // Si es confirmación o cancelación, dejar que handleCollecting lo procese
        if (isExplicitConfirm || isExplicitCancel) {
            return Optional.empty();
        }

        // Si es un saludo o mensaje vacío, mantener el flujo y pedir datos faltantes
        if (isGreeting || normalized.isBlank()) {
            ObjectNode data = readData(conversation);
            ParsedReservation current = dataToParsed(data, displayName);
            return Optional.of(buildCollectingMessageWithNote(current,
                    "¿Sigo con tu reserva? Envíame los datos que faltan."));
        }

        // Si es una pregunta, responder y pedir que confirme si sigue
        if (isQuestion) {
            log.info("[ReservationFlow] Question detected during reservation flow - answering and preserving flow");

            // Leer datos actuales
            ObjectNode data = readData(conversation);
            ParsedReservation current = dataToParsed(data, displayName);

            // Construir resumen de datos actuales
            StringBuilder statusNote = new StringBuilder();
            statusNote.append("¿Sigo con tu reserva?\n\n");

            if (current.parsedDate != null || current.peopleCount > 0 || current.time != null || current.customerName != null) {
                statusNote.append("📝 Detecté:\n");
                if (current.parsedDate != null) {
                    statusNote.append("- Fecha: Sábado ").append(current.parsedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("\n");
                }
                if (current.peopleCount > 0) {
                    statusNote.append("- Personas: ").append(current.peopleCount).append("\n");
                }
                if (current.time != null) {
                    statusNote.append("- Hora: ").append(current.time).append("\n");
                }
                if (current.customerName != null && !current.customerName.isBlank()) {
                    statusNote.append("- Nombre: ").append(current.customerName).append("\n");
                }
                if (current.motive != null) {
                    statusNote.append("- Motivo: ").append(current.motive).append("\n");
                }
                if (current.honoree != null) {
                    statusNote.append("- Homenajeado: ").append(current.honoree).append("\n");
                }
            }

            statusNote.append("\n⏳ Falta:\n");
            if (current.parsedDate == null) statusNote.append("- *Fecha* (solo sábados)\n");
            if (current.peopleCount == 0) statusNote.append("- *Número de personas*\n");
            if (current.time == null) statusNote.append("- *Hora* (11:30, 12:00, 12:30 o 1:00 PM)\n");
            if (current.customerName == null || current.customerName.isBlank()) statusNote.append("- *Tu nombre*\n");

            statusNote.append("\nRespondo tu pregunta y luego continuamos con la reserva.");

            // Retornar marcador especial para que el pipeline responda la pregunta
            // y luego el handleCollecting continúe
            return Optional.of("__INTERRUPTION__" + statusNote.toString());
        }

        // Si no es pregunta ni confirmación ni cancelación, podría ser datos de reserva
        return Optional.empty();
    }

    /**
     * Convierte los datos JSON del conversation a un ParsedReservation.
     */
    private ParsedReservation dataToParsed(ObjectNode data, String displayName) {
        ParsedReservation parsed = new ParsedReservation();
        parsed.customerName = data.has("customerName") && !data.get("customerName").asText().isBlank()
                ? data.get("customerName").asText() : displayName;
        if (data.has("parsedDate") && !data.get("parsedDate").isNull()) {
            try { parsed.parsedDate = LocalDate.parse(data.get("parsedDate").asText()); } catch (Exception ignored) {}
        }
        parsed.peopleCount = data.has("peopleCount") ? data.get("peopleCount").asInt() : 0;
        parsed.time = data.has("time") && !data.get("time").isNull() ? data.get("time").asText() : null;
        parsed.motive = data.has("motive") && !data.get("motive").isNull() ? data.get("motive").asText() : null;
        parsed.honoree = data.has("honoree") && !data.get("honoree").isNull() ? data.get("honoree").asText() : null;
        return parsed;
    }

    // ── Confirmation summary ─────────────────────────────────────────────

    /**
     * Construye un resumen completo de la reserva para confirmación.
     */
    private String buildConfirmationSummary(ObjectNode data, String phone) {
        String customerName = data.has("customerName") ? data.get("customerName").asText() : "N/A";
        String dateStr = data.has("parsedDate") ? data.get("parsedDate").asText() : null;
        int peopleCount = data.has("peopleCount") ? data.get("peopleCount").asInt() : 0;
        String time = data.has("time") && !data.get("time").isNull() ? data.get("time").asText() : null;
        String motive = data.has("motive") && !data.get("motive").isNull() ? data.get("motive").asText() : null;
        String honoree = data.has("honoree") && !data.get("honoree").isNull() ? data.get("honoree").asText() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("¡Perfecto! Resumen de tu reserva:\n\n");

        if (dateStr != null) {
            try {
                LocalDate date = LocalDate.parse(dateStr);
                sb.append("📅 Fecha: Sábado ").append(date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("\n");
            } catch (Exception e) {
                sb.append("📅 Fecha: ").append(dateStr).append("\n");
            }
        }

        if (time != null) {
            sb.append("🕐 Hora: ").append(formatTimeDisplay(time)).append("\n");
        }

        if (peopleCount > 0) {
            sb.append("👥 Personas: ").append(peopleCount).append("\n");
        }

        if (customerName != null && !customerName.isBlank()) {
            sb.append("👤 Nombre: ").append(customerName).append("\n");
        }

        if (motive != null) {
            sb.append("🎂 Motivo: ").append(motive).append("\n");
        }

        if (honoree != null && !"N/A".equals(honoree)) {
            sb.append("🎉 Homenajeado: ").append(honoree).append("\n");
        }

        sb.append("\n💰 Depósito: $40.000 COP\n");
        sb.append("\n¿Confirmo esta reserva? Responde *SI* o *NO*");

        return sb.toString();
    }

    /**
     * Formatea la hora para mostrar de forma amigable.
     */
    private String formatTimeDisplay(String time) {
        if (time == null) return "";
        try {
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0]);
            int min = Integer.parseInt(parts[1]);
            String period = hour >= 12 ? "PM" : "AM";
            int displayHour = hour > 12 ? hour - 12 : hour;
            if (displayHour == 0) displayHour = 12;
            return String.format("%d:%02d %s", displayHour, min, period);
        } catch (Exception e) {
            return time;
        }
    }

    /**
     * Construye mensaje de recolección con nota adicional.
     */
    private String buildCollectingMessageWithNote(ParsedReservation parsed, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildCollectingMessage(parsed));
        sb.append("\n\n").append(note);
        return sb.toString();
    }

    /**
     * Construye mensaje cuando el usuario confirma con SI pero faltan datos.
     */
    private String buildConfirmationSummaryWithMissing(ObjectNode data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Falta información para completar la reserva:\n\n");

        boolean hasName = data.has("customerName") && !data.get("customerName").asText().isBlank();
        boolean hasDate = data.has("parsedDate") && !data.get("parsedDate").isNull();
        boolean hasPeople = data.has("peopleCount") && data.get("peopleCount").asInt() > 0;
        boolean hasTime = data.has("time") && !data.get("time").isNull();

        if (!hasName) sb.append("- Tu *nombre completo*\n");
        if (!hasDate) sb.append("- *Fecha* (solo sábados)\n");
        if (!hasPeople) sb.append("- *Número de personas*\n");
        if (!hasTime) sb.append("- *Hora* (11:30, 12:00, 12:30 o 1:00 PM)\n");

        sb.append("\nEnvíame esos datos y confirmo tu reserva.");
        return sb.toString();
    }

    // ── Parsing helpers ────────────────────────────────────────────────

    private ParsedReservation parseInitialMessage(String normalized, String displayName) {
        ParsedReservation parsed = new ParsedReservation();
        parsed.customerName = displayName;

        parsed.dayNameMentioned = detectDayName(normalized);
        parsed.mananaNotSaturday = isMananaMentioned(normalized) && !isMananaSaturday();
        parsed.parsedDate = parseDateFromText(normalized);

        Integer parsedPeople = parsePeopleCount(normalized, normalized);
        parsed.peopleCount = parsedPeople != null ? parsedPeople : 0;
        parsed.time = parseTime(normalized, normalized);
        parsed.motive = parseMotive(normalized);

        return parsed;
    }

    private boolean isMananaMentioned(String text) {
        String lower = text.toLowerCase();
        return lower.contains("mañana") || lower.contains("manana");
    }

    private boolean isMananaSaturday() {
        return nowColombia().plusDays(1).getDayOfWeek() == DayOfWeek.SATURDAY;
    }

    private String detectDayName(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("domingo")) return "domingo";
        if (lower.contains("lunes")) return "lunes";
        if (lower.contains("martes")) return "martes";
        if (lower.contains("miércoles") || lower.contains("miercoles")) return "miércoles";
        if (lower.contains("jueves")) return "jueves";
        if (lower.contains("viernes")) return "viernes";
        return null;
    }

    private String buildDayNotAvailableMessage(String dayName) {
        LocalDate nextSat = findNextSaturday();
        return String.format(
            "Los *%s* no hacemos reservas.\n\n" +
            "Solo hacemos reservas los sábados.\n\n" +
            "El próximo sábado es: *%s*\n\n" +
            "¿Deseas reservar para ese día? Responde *SI* o *NO*, o envía los datos de tu reserva.",
            dayName + "s",
            nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        );
    }

    private String buildCollectingMessage(ParsedReservation parsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("📝 Reserva en progreso:\n\n");

        // Show what was detected
        sb.append("✅ Detectado:\n");
        if (parsed.customerName != null && !parsed.customerName.isBlank()) {
            sb.append("- Nombre: ").append(parsed.customerName).append("\n");
        }
        if (parsed.parsedDate != null) {
            sb.append("- Fecha: Sábado ")
              .append(parsed.parsedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("\n");
        }
        if (parsed.peopleCount > 0) sb.append("- Personas: ").append(parsed.peopleCount).append("\n");
        if (parsed.time != null) sb.append("- Hora: ").append(formatTimeDisplay(parsed.time)).append("\n");
        if (parsed.motive != null) sb.append("- Motivo: ").append(parsed.motive).append("\n");
        if (parsed.honoree != null) sb.append("- Homenajeado: ").append(parsed.honoree).append("\n");

        // Show what's missing
        sb.append("\n⏳ Falta:\n");
        boolean hasName = parsed.customerName != null && !parsed.customerName.isBlank();
        boolean hasDate = parsed.parsedDate != null;
        boolean hasPeople = parsed.peopleCount > 0;
        boolean hasTime = parsed.time != null;

        if (!hasName) sb.append("- *Tu nombre completo*\n");
        if (!hasDate) sb.append("- *Fecha* (solo sábados)\n");
        if (!hasPeople) sb.append("- *Número de personas*\n");
        if (!hasTime) sb.append("- *Hora* (11:30, 12:00, 12:30 o 1:00 PM)\n");

        sb.append("\nEnvíame los datos faltantes o pregunta lo que necesites.");
        return sb.toString();
    }

    private String buildMissingDataMessage(ObjectNode data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Falta información para completar la reserva:\n\n");

        boolean hasName = data.has("customerName") && !data.get("customerName").asText().isBlank();
        boolean hasDate = data.has("parsedDate") && !data.get("parsedDate").isNull();
        boolean hasPeople = data.has("peopleCount") && data.get("peopleCount").asInt() > 0;

        if (!hasName) sb.append("- Tu *nombre completo*\n");
        if (!hasDate) sb.append("- *Fecha* (solo sabados)\n");
        if (!hasPeople) sb.append("- *Numero de personas*\n");

        sb.append("\nEnvíalos por favor.");
        return sb.toString();
    }

    private String detectNameFromText(String lower, String value) {
        Pattern p1 = Pattern.compile("(?:mi nombre es|soy|me llamo|nombre:?)\\s+(.+)", Pattern.CASE_INSENSITIVE);
        Matcher m1 = p1.matcher(value);
        if (m1.find()) {
            String name = m1.group(1).trim();
            if (!name.isBlank() && name.length() > 1) return name;
        }
        return null;
    }

    private LocalDate parseDateFromText(String text) {
        String lower = text.toLowerCase();

        if (lower.contains("mañana") || lower.contains("manana")) {
            LocalDate tomorrow = nowColombia().plusDays(1);
            if (tomorrow.getDayOfWeek() == DayOfWeek.SATURDAY) return tomorrow;
            return null;
        }

        if (lower.contains("sábado") || lower.contains("sabado")) {
            return findNextSaturday();
        }

        Pattern datePattern = Pattern.compile("(\\d{1,2})[/\\-](\\d{1,2})[/\\-](\\d{4})");
        Matcher m = datePattern.matcher(text);
        if (m.matches()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = Integer.parseInt(m.group(3));
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) {}
        }

        Pattern datePattern2 = Pattern.compile("(\\d{1,2})\\s+de\\s+(\\w+)");
        Matcher m2 = datePattern2.matcher(lower);
        if (m2.matches()) {
            try {
                int day = Integer.parseInt(m2.group(1));
                String monthStr = m2.group(2);
                int month = parseMonth(monthStr);
                if (month > 0) return LocalDate.of(nowColombia().getYear(), month, day);
            } catch (Exception ignored) {}
        }

        return null;
    }

    private LocalDate findNextSaturday() {
        LocalDate today = nowColombia();
        LocalDate nextSaturday = today;
        while (nextSaturday.getDayOfWeek() != DayOfWeek.SATURDAY) {
            nextSaturday = nextSaturday.plusDays(1);
        }
        return nextSaturday;
    }

    private int parseMonth(String monthStr) {
        return switch (monthStr) {
            case "enero" -> 1;
            case "febrero" -> 2;
            case "marzo" -> 3;
            case "abril" -> 4;
            case "mayo" -> 5;
            case "junio" -> 6;
            case "julio" -> 7;
            case "agosto" -> 8;
            case "septiembre" -> 9;
            case "octubre" -> 10;
            case "noviembre" -> 11;
            case "diciembre" -> 12;
            default -> 0;
        };
    }

    private Integer parsePeopleCount(String lower, String value) {
        Pattern p1 = Pattern.compile("somos\\s+(\\d+)");
        Matcher m1 = p1.matcher(lower);
        if (m1.find()) return Integer.parseInt(m1.group(1));

        Pattern p2 = Pattern.compile("(\\d+)\\s*personas?");
        Matcher m2 = p2.matcher(lower);
        if (m2.find()) return Integer.parseInt(m2.group(1));

        Pattern p3 = Pattern.compile("para\\s+(\\d+)");
        Matcher m3 = p3.matcher(lower);
        if (m3.find()) return Integer.parseInt(m3.group(1));

        // "llevar 3 más", "agregar 2", "sumar 4 personas"
        Pattern p4 = Pattern.compile("(?:llevar|agregar|adicionar|sumar)\\s+(\\d+)");
        Matcher m4 = p4.matcher(lower);
        if (m4.find()) return Integer.parseInt(m4.group(1));

        if (lower.matches("\\d+") && !lower.matches("\\d{1,2}:\\d{2}")) {
            int num = Integer.parseInt(lower);
            if (num > 0 && num < 500) return num;
        }

        return null;
    }

    private String parseTime(String lower, String value) {
        Pattern p1 = Pattern.compile("(\\d{1,2})\\s*:\\s*(\\d{2})");
        Matcher m1 = p1.matcher(value);
        if (m1.find()) {
            int hour = Integer.parseInt(m1.group(1));
            int min = Integer.parseInt(m1.group(2));
            if (lower.contains("tarde") || lower.contains("pm")) {
                if (hour < 12) hour += 12;
            }
            return String.format("%02d:%02d", hour, min);
        }

        Pattern p2 = Pattern.compile("(?:a\\s+)?las?\\s+(\\d{1,2})(?:\\s+de\\s+la\\s+tarde)?");
        Matcher m2 = p2.matcher(lower);
        if (m2.find()) {
            int hour = Integer.parseInt(m2.group(1));
            // "a la 1" en colombiano SIEMPRE es PM (13:00), a menos que diga "de la mañana"
            if (lower.contains("tarde") || lower.contains("pm") || (!lower.contains("mañana") && !lower.contains("manana") && !lower.contains("am"))) {
                if (hour >= 1 && hour <= 12) hour += 12;
            }
            return String.format("%02d:00", hour);
        }

        if (lower.contains("una de la tarde")) return "13:00";
        if (lower.contains("una de la mañana") || lower.contains("una de la manana")) return "11:00";

        Pattern p3 = Pattern.compile("(\\d{1,2})\\s+de\\s+la\\s+tarde");
        Matcher m3 = p3.matcher(lower);
        if (m3.find()) {
            int hour = Integer.parseInt(m3.group(1));
            if (hour < 12) hour += 12;
            return String.format("%02d:00", hour);
        }

        Pattern p4 = Pattern.compile("(\\d{1,2})\\s+de\\s+la\\s+(?:mañana|manana)");
        Matcher m4 = p4.matcher(lower);
        if (m4.find()) {
            int hour = Integer.parseInt(m4.group(1));
            return String.format("%02d:00", hour);
        }

        return null;
    }

    private String parseMotive(String lower) {
        // No parsear números ni horas como motivo
        if (lower.matches("\\d{1,2}:\\d{2}")) return null;
        if (lower.matches("\\d+")) return null;
        if (lower.contains("torta") || lower.contains("pastel") || lower.contains("cumpleaños") || lower.contains("cumpleanos")) return "Cumpleaños";
        if (lower.contains("celebración") || lower.contains("celebracion")) return "Celebración";
        if (lower.contains("perrito") || lower.contains("perro") || lower.contains("mascota")) return "Reunión con mascotas";
        if (lower.contains("familia")) return "Reunión familiar";
        if (lower.contains("amigos") || lower.contains("amigo")) return "Reunión de amigos";
        if (lower.contains("negocio") || lower.contains("trabajo")) return "Reunión de negocios";
        return null;
    }

    /**
     * Detecta si un mensaje NO es datos de reserva.
     * Solo retorna true para mensajes claramente NO relacionados con reserva.
     * Conservador: ante la duda, retorna false (asume que SÍ es dato de reserva).
     */
    private boolean isNotReservationData(String normalized) {
        if (normalized == null || normalized.isBlank()) return true;

        // Si contiene keywords de reserva, SÍ es dato de reserva (no cancelar)
        if (normalized.contains("reserv") || normalized.contains("sabado") || normalized.contains("sábado") ||
            normalized.contains("personas") || normalized.contains("somos") || normalized.contains("hora")) {
            return false;
        }

        // Preguntas claras (contienen ? o ¿)
        if (normalized.contains("?") || normalized.contains("¿")) return true;

        // Saludos claros
        if (normalized.equals("hola") || normalized.equals("buenos dias") ||
            normalized.equals("buenas tardes") || normalized.equals("buenas noches") ||
            normalized.equals("buen dia")) return true;

        // Palabras que indican que NO es datos de reserva sino conversación/preguntas
        String[] nonDataPhrases = {
            "puedo", "necesito", "tienen", "tienen?", "hay", "como",
            "donde", "dónde", "cuando", "cuánto", "cuanto", "que", "qué",
            "habla", "dame", "envia", "envíame", "puedo llevar", "se puede",
            "aceptan", "aceptan?", "disponen", "manejan", "trabajan", "atenden",
            "baño", "baños", "parqueadero", "mascota", "mascotas", "wifi",
            "musica", "música", "decoracion", "decoración"
        };
        for (String phrase : nonDataPhrases) {
            if (normalized.equals(phrase) || normalized.startsWith(phrase + " ") ||
                normalized.contains(phrase + " ") || normalized.contains(phrase + "?") ||
                normalized.contains(phrase + "¿")) {
                return true;
            }
        }

        // Tiene datos parseables de reserva → SÍ es reserva
        if (normalized.matches(".*\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{4}.*")) return false;
        if (normalized.matches(".*\\d{1,2}\\s*:\\s*\\d{2}.*")) return false;
        if (normalized.matches(".*las?\\s+\\d{1,2}.*")) return false;
        if (normalized.matches("\\d{1,3}")) return false;
        if (normalized.contains("mañana") || normalized.contains("manana") ||
            normalized.contains("sabado") || normalized.contains("sábado") ||
            normalized.contains("de la tarde") || normalized.contains("de la mañana") ||
            normalized.contains("de la manana")) return false;

        // Tiene "personas" + número → reserva
        if (normalized.contains("personas") && normalized.matches(".*\\d+.*")) return false;
        if (normalized.contains("somos") && normalized.matches(".*\\d+.*")) return false;

        // Por defecto: no es datos de reserva
        return true;
    }

    private LocalTime parseTimeToLocalTime(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return LocalTime.of(hour, min);
        } catch (Exception e) {
            return LocalTime.of(12, 0);
        }
    }

    private boolean isValidTime(String time) {
        if (time == null) return false;
        try {
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0]);
            int min = Integer.parseInt(parts[1]);
            return (hour == 11 && min == 30) ||
                   (hour == 12 && min == 0) ||
                   (hour == 12 && min == 30) ||
                   (hour == 13 && min == 0);
        } catch (Exception e) {
            return false;
        }
    }

    private String getDayName(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "lunes";
            case TUESDAY -> "martes";
            case WEDNESDAY -> "miércoles";
            case THURSDAY -> "jueves";
            case FRIDAY -> "viernes";
            case SATURDAY -> "sábado";
            case SUNDAY -> "domingo";
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ObjectNode readData(ConversationEntity conversation) {
        try {
            String json = conversation.getPendingActionData();
            if (json == null || json.isBlank()) return objectMapper.createObjectNode();
            return (ObjectNode) objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    @Transactional
    public void clearPendingAction(ConversationEntity conversation) {
        conversation.setPendingAction(null);
        conversation.setPendingActionData(null);
        conversationRepository.save(conversation);
    }

    private static class ParsedReservation {
        String customerName;
        LocalDate parsedDate;
        String dateStr;
        Integer peopleCount = 0;
        String motive;
        String honoree;
        String time;
        String dayNameMentioned;
        boolean mananaNotSaturday;
    }
}
