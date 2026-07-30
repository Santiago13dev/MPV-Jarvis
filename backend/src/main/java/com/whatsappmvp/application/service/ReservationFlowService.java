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
    public static final String ACTION_CONFIRM_PARSED = "RESERVATION_CONFIRM_PARSED";
    public static final String ACTION_COLLECT_MISSING = "RESERVATION_COLLECT_MISSING";
    public static final String ACTION_CANCEL_CONFIRM = "RESERVATION_CANCEL_CONFIRM";

    // Horarios disponibles para reserva (solo sábados 11AM+)
    // 11:10, 12:00, 12:30, 13:00

    private static final ZoneId COLOMBIA_TZ = ZoneId.of("America/Bogota");

    private LocalDate nowColombia() {
        return LocalDate.now(COLOMBIA_TZ);
    }

    public boolean hasPendingAction(ConversationEntity conversation) {
        return conversation.getPendingAction() != null && !conversation.getPendingAction().isBlank();
    }

    @Transactional
    public Optional<String> handleStep(ConversationEntity conversation, String content, String phone, String displayName) {
        String action = conversation.getPendingAction();
        if (action == null || action.isBlank()) return Optional.empty();

        String normalized = content.trim().toLowerCase();

        return switch (action) {
            case ACTION_ASK_CONFIRMATION -> handleConfirmation(conversation, normalized, phone, displayName);
            case ACTION_CONFIRM_PARSED -> handleConfirmParsed(conversation, normalized, phone, displayName);
            case ACTION_COLLECT_MISSING -> handleCollectMissing(conversation, content.trim(), phone, displayName);
            case ACTION_CANCEL_CONFIRM -> handleCancelConfirmation(conversation, normalized, phone);
            default -> {
                clearPendingAction(conversation);
                yield Optional.empty();
            }
        };
    }

    @Transactional
    public String startFlow(ConversationEntity conversation, String content, String displayName) {
        String normalized = content.trim().toLowerCase();
        ParsedReservation parsed = parseInitialMessage(normalized, displayName);

        ObjectNode data = objectMapper.createObjectNode();
        data.put("phone", conversation.getContact().getPhone());
        if (parsed.customerName != null) data.put("customerName", parsed.customerName);
        if (parsed.parsedDate != null) data.put("parsedDate", parsed.parsedDate.toString());
        if (parsed.peopleCount > 0) data.put("peopleCount", parsed.peopleCount);
        if (parsed.motive != null) data.put("motive", parsed.motive);
        if (parsed.honoree != null) data.put("honoree", parsed.honoree);
        if (parsed.time != null) data.put("time", parsed.time);

        // Si mencionan "mañana" pero mañana no es sábado, informar y ofrecer próximo sábado
        if (parsed.mananaNotSaturday) {
            LocalDate nextSat = findNextSaturday();
            LocalDate tomorrow = nowColombia().plusDays(1);
            ObjectNode mananaData = objectMapper.createObjectNode();
            mananaData.put("phone", conversation.getContact().getPhone());
            mananaData.put("parsedDate", nextSat.toString());
            conversation.setPendingAction(ACTION_CONFIRM_PARSED);
            conversation.setPendingActionData(mananaData.toString());
            conversationRepository.save(conversation);
            return String.format(
                "⚠️ *Mañana (%s %s)* no hacemos reservas.\n\n" +
                "📅 Solo hacemos reservas los *sábados*.\n\n" +
                "El próximo sábado es: *%s*\n\n" +
                "¿Deseas reservar para ese día? Responde *SI* o *NO*",
                getDayName(tomorrow.getDayOfWeek()),
                tomorrow.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            );
        }

        // Si detectó un día de la semana que NO es sábado, informar inmediatamente
        if (parsed.dayNameMentioned != null && parsed.parsedDate == null) {
            return buildDayNotAvailableMessage(parsed.dayNameMentioned);
        }

        // Si parseó una fecha que no es sábado, informar y sugerir próximo sábado
        if (parsed.parsedDate != null && parsed.parsedDate.getDayOfWeek() != DayOfWeek.SATURDAY) {
            LocalDate nextSat = findNextSaturday();
            String dayName = getDayName(parsed.parsedDate.getDayOfWeek());
            return String.format(
                "⚠️ Solo hacemos reservas los *sábados*.\n\n" +
                "La fecha que mencionaste (%s %s) no aplica.\n\n" +
                "📅 El próximo sábado es: *%s*\n\n" +
                "¿Deseas reservar para ese día? Responde *SI* o *NO*",
                dayName,
                parsed.parsedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            );
        }

        boolean hasDate = parsed.parsedDate != null;
        boolean hasPeople = parsed.peopleCount > 0;

        if (hasDate || hasPeople) {
            conversation.setPendingAction(ACTION_CONFIRM_PARSED);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return buildConfirmationSummary(parsed);
        } else {
            conversation.setPendingAction(ACTION_ASK_CONFIRMATION);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return "¿Deseas hacer una reserva? Recuerda que solo hacemos reservas los *sábados* a partir de las 11:00 a.m.\n\nResponde *SI* o *NO*";
        }
    }

    @Transactional
    public String startCancelFlow(ConversationEntity conversation, String phone) {
        conversation.setPendingAction(ACTION_CANCEL_CONFIRM);
        ObjectNode data = objectMapper.createObjectNode();
        data.put("phone", phone);
        conversation.setPendingActionData(data.toString());
        conversationRepository.save(conversation);
        return "¿Deseas cancelar tu reserva? Responde *SI* o *NO*";
    }

    // ── Step: Simple confirmation (SI/NO) ──────────────────────────────

    private Optional<String> handleConfirmation(ConversationEntity conversation, String normalized, String phone, String displayName) {
        if (normalized.equals("si") || normalized.equals("sí")) {
            log.info("[ReservationFlow] User confirmed reservation — requesting data");
            conversation.setPendingAction(ACTION_COLLECT_MISSING);
            ObjectNode data = readData(conversation);
            if (displayName != null && !displayName.isBlank() && !data.has("customerName")) {
                data.put("customerName", displayName);
            }
            data.put("phone", phone);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return Optional.of(
                "Perfecto, por favor envíame los siguientes datos:\n\n" +
                "1. *Nombre completo*\n" +
                "2. *Fecha* (solo sábados)\n" +
                "3. *Número de personas*\n" +
                "4. *Motivo de la reserva* (cumpleaños, reunión, etc.)\n" +
                "5. *Nombre del homenajeado* (si aplica, responde *NA* si no)\n" +
                "6. *Hora preferida* (11:10, 12:00, 12:30 o 13:00)\n\n" +
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

    // ── Step: Confirm parsed data (SI/NO) ──────────────────────────────

    private Optional<String> handleConfirmParsed(ConversationEntity conversation, String normalized, String phone, String displayName) {
        if (normalized.equals("si") || normalized.equals("sí")) {
            log.info("[ReservationFlow] User confirmed — requesting all data");
            conversation.setPendingAction(ACTION_COLLECT_MISSING);
            ObjectNode data = readData(conversation);
            data.put("phone", phone);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return Optional.of(
                "Perfecto, por favor envíame los siguientes datos:\n\n" +
                "1. *Nombre completo*\n" +
                "2. *Fecha* (solo sábados)\n" +
                "3. *Número de personas*\n" +
                "4. *Motivo de la reserva* (cumpleaños, reunión, etc.)\n" +
                "5. *Nombre del homenajeado* (si aplica, responde *NA* si no)\n" +
                "6. *Hora preferida* (11:10, 12:00, 12:30 o 13:00)\n\n" +
                "Envíalos todos juntos o uno por uno."
            );
        } else if (normalized.equals("no")) {
            log.info("[ReservationFlow] User rejected parsed data — requesting all data");
            conversation.setPendingAction(ACTION_COLLECT_MISSING);
            ObjectNode data = readData(conversation);
            data.put("phone", phone);
            conversation.setPendingActionData(data.toString());
            conversationRepository.save(conversation);
            return Optional.of(
                "Entendido. Por favor envíame los datos correctos:\n\n" +
                "1. *Nombre completo*\n" +
                "2. *Fecha* (solo sábados)\n" +
                "3. *Número de personas*\n" +
                "4. *Motivo de la reserva*\n" +
                "5. *Nombre del homenajeado* (si aplica, responde *NA* si no)\n" +
                "6. *Hora preferida* (11:10, 12:00, 12:30 o 13:00)\n\n" +
                "Envíalos todos juntos o uno por uno."
            );
        } else {
            return Optional.of("Por favor responde *SI* o *NO* para confirmar los datos de tu reserva.");
        }
    }

    // ── Step: Collect missing data ───────────────────────────────────────

    private Optional<String> handleCollectMissing(ConversationEntity conversation, String content, String phone, String displayName) {
        try {
            ObjectNode dataNode = readData(conversation);

            String storedPhone = dataNode.has("phone") ? dataNode.get("phone").asText() : phone;
            String customerName = dataNode.has("customerName") ? dataNode.get("customerName").asText() : null;
            if (customerName == null && displayName != null && !displayName.isBlank()) {
                customerName = displayName;
            }

            LocalDate reservationDate = null;
            Integer peopleCount = null;
            String motive = null;
            String honoree = null;
            String time = null;

            // Restore stored values
            if (dataNode.has("parsedDate") && !dataNode.get("parsedDate").isNull()) {
                try { reservationDate = LocalDate.parse(dataNode.get("parsedDate").asText()); } catch (Exception ignored) {}
            }
            if (dataNode.has("peopleCount") && dataNode.get("peopleCount").asInt() > 0) {
                peopleCount = dataNode.get("peopleCount").asInt();
            }
            if (dataNode.has("motive") && !dataNode.get("motive").isNull()) {
                motive = dataNode.get("motive").asText();
            }
            if (dataNode.has("honoree") && !dataNode.get("honoree").isNull()) {
                honoree = dataNode.get("honoree").asText();
            }
            if (dataNode.has("time") && !dataNode.get("time").isNull()) {
                time = dataNode.get("time").asText();
            }

            String lower = content.toLowerCase();

            // Check for day name mentions — inform if not Saturday
            String dayNameMentioned = detectDayName(lower);
            if (dayNameMentioned != null) {
                return Optional.of(buildDayNotAvailableMessage(dayNameMentioned));
            }

            // Parse new message for additional data
            String[] lines = content.split("\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                String value = trimmed;
                Pattern numPattern = Pattern.compile("^\\d+[.\\-)]\\s*(.+)$");
                Matcher m = numPattern.matcher(trimmed);
                if (m.matches()) {
                    value = m.group(1).trim();
                }

                String lineLower = value.toLowerCase();

                // Try date patterns
                if (reservationDate == null) {
                    LocalDate parsed = parseDateFromText(lineLower);
                    if (parsed != null) {
                        // Validate Saturday
                        if (parsed.getDayOfWeek() != DayOfWeek.SATURDAY) {
                            LocalDate nextSat = findNextSaturday();
                            String day = getDayName(parsed.getDayOfWeek());
                            return Optional.of(String.format(
                                "⚠️ Solo hacemos reservas los *sábados*.\n\n" +
                                "La fecha que indicaste (%s %s) no aplica.\n\n" +
                                "📅 El próximo sábado es: *%s*\n\n" +
                                "Por favor envía la fecha correcta.",
                                day,
                                parsed.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                                nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                            ));
                        }
                        reservationDate = parsed;
                        continue;
                    }
                }

                // Time pattern
                if (time == null) {
                    time = parseTime(lineLower, value);
                    if (time != null) {
                        if (!isValidTime(time)) {
                            return Optional.of(
                                "⚠️ Los horarios disponibles son:\n\n" +
                                "• *11:10 a.m.*\n• *12:00 p.m.*\n• *12:30 p.m.*\n• *1:00 p.m.*\n\n" +
                                "Por favor elige uno de estos horarios."
                            );
                        }
                        continue;
                    }
                }

                // People count
                if (peopleCount == null) {
                    peopleCount = parsePeopleCount(lineLower, value);
                    if (peopleCount != null) continue;
                }

                // "NA" for honoree
                if (honoree == null && lineLower.equals("na")) {
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

            // Check what's missing
            StringBuilder missing = new StringBuilder();
            if (reservationDate == null) missing.append("• *Fecha* (solo sábados)\n");
            if (peopleCount == null) missing.append("• *Número de personas*\n");
            if (time == null) missing.append("• *Hora preferida* (11:10, 12:00, 12:30 o 13:00)\n");
            if (motive == null) missing.append("• *Motivo de la reserva*\n");

            if (missing.length() > 0) {
                ObjectNode updatedData = objectMapper.createObjectNode();
                updatedData.put("phone", storedPhone);
                if (customerName != null) updatedData.put("customerName", customerName);
                if (reservationDate != null) updatedData.put("parsedDate", reservationDate.toString());
                if (peopleCount != null) updatedData.put("peopleCount", peopleCount);
                if (motive != null) updatedData.put("motive", motive);
                if (honoree != null) updatedData.put("honoree", honoree);
                if (time != null) updatedData.put("time", time);
                conversation.setPendingActionData(updatedData.toString());
                conversationRepository.save(conversation);

                return Optional.of("Faltan algunos datos:\n\n" + missing + "\nEnvíalos por favor.");
            }

            // All data collected — store and create
            ObjectNode finalData = objectMapper.createObjectNode();
            finalData.put("phone", storedPhone);
            finalData.put("customerName", customerName);
            finalData.put("parsedDate", reservationDate.toString());
            finalData.put("peopleCount", peopleCount);
            if (motive != null) finalData.put("motive", motive);
            if (honoree != null) finalData.put("honoree", honoree);
            if (time != null) finalData.put("time", time);
            conversation.setPendingActionData(finalData.toString());
            conversationRepository.save(conversation);

            return createReservationFromData(conversation, phone);

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
                        "✅ *Reserva cancelada*\n\n👤 %s\n📅 %s\n\nSi necesitas hacer una nueva reserva, escribe *reservar*.",
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
                return Optional.of("Necesito tu *nombre completo*. Por favor envíalo.");
            }
            if (dateStr == null) {
                LocalDate nextSat = findNextSaturday();
                return Optional.of(String.format(
                    "Necesito la *fecha* de la reserva.\n\n📅 Solo hacemos reservas los sábados. El próximo es: *%s*\n\nPor favor envíala.",
                    nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                ));
            }

            LocalDate reservationDate;
            try {
                reservationDate = LocalDate.parse(dateStr);
            } catch (Exception e) {
                return Optional.of("La fecha no es válida. Por favor usa el formato dd/mm/aaaa.");
            }

            if (reservationDate.getDayOfWeek() != DayOfWeek.SATURDAY) {
                LocalDate nextSat = findNextSaturday();
                return Optional.of(String.format(
                    "⚠️ Solo hacemos reservas los *sábados*.\n\nLa fecha (%s) no aplica.\n📅 El próximo sábado es: *%s*",
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

            StringBuilder notes = new StringBuilder();
            if (peopleCount > 0) notes.append("Personas: ").append(peopleCount).append(". ");
            if (motive != null) notes.append("Motivo: ").append(motive).append(". ");
            if (honoree != null) notes.append("Homenajeado: ").append(honoree).append(". ");
            if (time != null) notes.append("Hora: ").append(time).append(".");
            req.setNotes(notes.length() > 0 ? notes.toString() : null);

            reservationService.createReservation(req);
            clearPendingAction(conversation);

            String timeInfo = time != null ? " a las " + time : "";
            String peopleInfo = peopleCount > 0 ? "\n👥 " + peopleCount + " personas" : "";
            String motiveInfo = motive != null ? "\n🎉 " + motive : "";

            String response = String.format(
                "✅ *¡Reserva confirmada!*\n\n👤 %s\n📅 Sábado %s%s%s\n💰 Depósito: $40.000 COP\n\n¡Te esperamos! 🎉",
                customerName,
                reservationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                timeInfo,
                peopleInfo,
                motiveInfo
            );

            log.info("[ReservationFlow] Reservation created for {} on Saturday {}", customerName, reservationDate);
            return Optional.of(response);

        } catch (Exception e) {
            log.error("[ReservationFlow] Error creating reservation: {}", e.getMessage());
            clearPendingAction(conversation);
            return Optional.of("Hubo un error procesando tu reserva. Por favor intenta de nuevo escribiendo *reservar*.");
        }
    }

    // ── Parsing helpers ────────────────────────────────────────────────

    private ParsedReservation parseInitialMessage(String normalized, String displayName) {
        ParsedReservation parsed = new ParsedReservation();
        parsed.customerName = displayName;

        // First check if a day name is mentioned
        parsed.dayNameMentioned = detectDayName(normalized);

        // Check if "mañana" is mentioned but tomorrow is not Saturday
        parsed.mananaNotSaturday = isMananaMentioned(normalized) && !isMananaSaturday();

        // Parse date
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

    /**
     * Detecta si se menciona un nombre de día de la semana.
     * Retorna el nombre del día en minúsculas o null.
     */
    private String detectDayName(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("domingo")) return "domingo";
        if (lower.contains("lunes")) return "lunes";
        if (lower.contains("martes")) return "martes";
        if (lower.contains("miércoles") || lower.contains("miercoles")) return "miércoles";
        if (lower.contains("jueves")) return "jueves";
        if (lower.contains("viernes")) return "viernes";
        // "sábado" / "sabado" se maneja aparte porque SÍ es válido
        return null;
    }

    /**
     * Construye el mensaje cuando el usuario menciona un día que no es sábado.
     */
    private String buildDayNotAvailableMessage(String dayName) {
        LocalDate nextSat = findNextSaturday();
        return String.format(
            "⚠️ Los *%s* no hacemos reservas.\n\n" +
            "📅 Solo hacemos reservas los *sábados*.\n\n" +
            "El próximo sábado es: *%s*\n\n" +
            "¿Deseas reservar para ese día? Responde *SI* o *NO*",
            dayName + "s",
            nextSat.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        );
    }

    private LocalDate parseDateFromText(String text) {
        String lower = text.toLowerCase();

        // "mañana" — solo retorna fecha si mañana es sábado
        if (lower.contains("mañana") || lower.contains("manana")) {
            LocalDate tomorrow = nowColombia().plusDays(1);
            if (tomorrow.getDayOfWeek() == DayOfWeek.SATURDAY) return tomorrow;
            // Mañana no es sábado — retornar null para que el caller informe
            return null;
        }

        // "sábado" / "sabado"
        if (lower.contains("sábado") || lower.contains("sabado")) {
            return findNextSaturday();
        }

        // dd/mm/yyyy or dd-mm-yyyy
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

        // dd de month
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
            if (lower.contains("tarde") || lower.contains("pm")) {
                if (hour < 12) hour += 12;
            }
            return String.format("%02d:00", hour);
        }

        if (lower.contains("una de la tarde")) return "13:00";
        if (lower.contains("una de la mañana") || lower.contains("una de la manana")) return "11:00";

        return null;
    }

    private String parseMotive(String lower) {
        if (lower.contains("torta") || lower.contains("pastel") || lower.contains("cumpleaños") || lower.contains("cumpleanos")) return "Cumpleaños";
        if (lower.contains("celebración") || lower.contains("celebracion")) return "Celebración";
        if (lower.contains("perrito") || lower.contains("perro") || lower.contains("mascota")) return "Reunión con mascotas";
        if (lower.contains("familia")) return "Reunión familiar";
        if (lower.contains("amigos") || lower.contains("amigo")) return "Reunión de amigos";
        if (lower.contains("negocio") || lower.contains("trabajo")) return "Reunión de negocios";
        return null;
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
            return (hour == 11 && min == 10) ||
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

    private String buildConfirmationSummary(ParsedReservation parsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Detecté los siguientes datos:*\n\n");

        if (parsed.customerName != null) sb.append("👤 Nombre: ").append(parsed.customerName).append("\n");
        if (parsed.parsedDate != null) {
            sb.append("📅 Fecha: sábado ")
              .append(parsed.parsedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("\n");
        }
        if (parsed.peopleCount > 0) sb.append("👥 Personas: ").append(parsed.peopleCount).append("\n");
        if (parsed.motive != null) sb.append("🎉 Motivo: ").append(parsed.motive).append("\n");
        if (parsed.honoree != null) sb.append("🎂 Homenajeado: ").append(parsed.honoree).append("\n");
        if (parsed.time != null) sb.append("🕐 Hora: ").append(parsed.time).append("\n");

        sb.append("\n💰 Depósito: $40.000 COP\n");
        sb.append("\n¿Confirmas esta reserva? Responde *SI* o *NO*");

        return sb.toString();
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
