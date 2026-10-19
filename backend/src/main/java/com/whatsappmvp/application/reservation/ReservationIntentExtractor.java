package com.whatsappmvp.application.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.client.OpenAIServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte el texto libre del cliente en DATOS ESTRUCTURADOS (intención + campos).
 *
 * Principio: la IA/regex SOLO interpreta lenguaje. No decide disponibilidad, precio ni existencia de reservas.
 * Las fechas y horas se resuelven con el reloj del servidor (DateExpressionResolver / TimeExpressionResolver):
 * el LLM únicamente copia lo que dijo el cliente ("este sábado", "a las dos").
 *
 * Estrategia: 1) reglas locales (rápidas, gratuitas, deterministas); 2) el LLM se usa solo como respaldo
 * cuando hay un borrador activo y las reglas no entendieron nada (p. ej. "somos mis papás, mi esposa y yo").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationIntentExtractor {

    public enum Intent {
        CREATE_RESERVATION, CHECK_AVAILABILITY, MODIFY_RESERVATION, CANCEL_RESERVATION,
        GET_RESERVATION, PAYMENT_RECEIPT, GENERAL_QUESTION, NONE
    }

    /** Estado conversacional que ayuda a interpretar respuestas cortas ("5", "a las 2", "sí"). */
    public record Context(String awaiting, int pmPivotHour, boolean modifyMode, boolean hasDraft) {
        /** Sin borrador activo (por defecto): "cambiar"/"cancelar" bastan para cambiar de flujo. */
        public Context(String awaiting, int pmPivotHour, boolean modifyMode) {
            this(awaiting, pmPivotHour, modifyMode, false);
        }

        boolean awaiting(String a) {
            return a != null && a.equals(awaiting);
        }
    }

    public record Extraction(Intent intent, ReservationType type,
                             DateExpressionResolver.Resolution date,
                             TimeExpressionResolver.Resolution time,
                             Integer partySize, String customerName, String receiptPhone,
                             Long reservationNumber, int ordinal,
                             boolean yes, boolean no, boolean discard, boolean wantsAvailability) {

        public boolean hasBookingData() {
            return type != null || date.status() != DateExpressionResolver.Status.NONE
                    || time.hasTime() || time.hasPeriod() || partySize != null
                    || customerName != null || receiptPhone != null;
        }

        /** Nada útil: ni intención de reserva, ni datos, ni respuesta sí/no, ni selección. */
        public boolean isEmpty() {
            boolean noIntent = intent == Intent.NONE || intent == Intent.GENERAL_QUESTION;
            return noIntent && !hasBookingData() && !yes && !no && !discard
                    && ordinal == 0 && reservationNumber == null;
        }
    }

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("dos", 2), Map.entry("tres", 3), Map.entry("cuatro", 4), Map.entry("cinco", 5),
            Map.entry("seis", 6), Map.entry("siete", 7), Map.entry("ocho", 8), Map.entry("nueve", 9),
            Map.entry("diez", 10), Map.entry("once", 11), Map.entry("doce", 12), Map.entry("trece", 13),
            Map.entry("catorce", 14), Map.entry("quince", 15), Map.entry("dieciseis", 16),
            Map.entry("diecisiete", 17), Map.entry("dieciocho", 18), Map.entry("diecinueve", 19),
            Map.entry("veinte", 20), Map.entry("treinta", 30), Map.entry("cuarenta", 40));

    private static final String NUM = "(\\d{1,3}|" + String.join("|", NUMBER_WORDS.keySet()) + ")";
    private static final String MONTHS = "enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|"
            + "octubre|noviembre|diciembre";

    private static final Pattern PARTY_A = Pattern.compile(
            "\\b" + NUM + "\\s*(?:personas?|pax|invitados|adultos|comensales|amigos|familiares)\\b");
    private static final Pattern PARTY_B = Pattern.compile(
            "\\b(?:para|somos|seremos|vamos|seriamos|seria|grupo de|mesa de)\\s+(?:unas\\s+)?" + NUM
                    + "\\b(?!\\s*(?:am|pm|:|/|de\\s+(?:la|el|" + MONTHS + ")))");
    private static final Pattern PARTY_ONE = Pattern.compile("\\b(?:una|1)\\s+persona\\b|\\bsolo\\s+yo\\b");
    private static final Pattern BARE_PARTY = Pattern.compile(
            "^(?:somos |son |seremos |para )?" + NUM + "(?:\\s*personas?)?$");

    private static final Pattern RESERVATION_NUMBER = Pattern.compile(
            "#\\s?(\\d{3,7})|\\breserva(?:cion)?\\s*(?:numero|no|n)?\\s*#?(\\d{4,7})\\b");
    private static final Pattern BARE_NUMBER = Pattern.compile("^#?(\\d{3,7})$");

    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(?iu)(?:a nombre de|mi nombre es|me llamo|nombre\\s*:?)\\s+(\\p{L}+(?:\\s+\\p{L}+){0,3})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\+?\\d[\\d\\s-]{8,14}\\d");
    private static final Pattern TAIL = Pattern.compile(
            "\\b(?:del|desde el|de el)\\b.*\\b(?:al|para el|hacia el)\\b(.*)$");

    private static final Set<String> NAME_STOP = Set.of(
            "y", "e", "para", "el", "la", "quiero", "somos", "a", "con", "por", "en", "los", "las", "un", "una",
            "reserva", "mesa", "vip", "hoy", "manana", "sabado", "domingo");

    private static final Set<String> YES = Set.of(
            "si", "sii", "siii", "sip", "dale", "ok", "okay", "okey", "listo", "claro", "confirmo", "confirmar",
            "confirmado", "correcto", "perfecto", "de una", "adelante", "hagale", "vale", "exacto",
            "si por favor", "si claro", "si confirmo", "si dale", "si quiero", "si gracias", "por favor",
            "esta bien", "de acuerdo", "bueno", "si senor", "si señor", "ok gracias", "dale gracias");
    private static final Set<String> NO = Set.of(
            "no", "nop", "nel", "no gracias", "mejor no", "nada", "olvidalo", "dejalo asi", "ya no", "no no",
            "no por ahora", "no quiero");

    private final DateExpressionResolver dateResolver;
    private final TimeExpressionResolver timeResolver;
    private final OpenAIServiceClient llm;
    private final ObjectMapper mapper;

    // ══════════════════════════════════════════════════════════════════════
    // Reglas locales
    // ══════════════════════════════════════════════════════════════════════

    public Extraction extract(String text, Context ctx) {
        String raw = text == null ? "" : text.trim();
        String n = TextNormalizer.normalize(raw);

        DateExpressionResolver.Resolution date = dateResolver.resolve(raw);
        TimeExpressionResolver.Resolution time = timeResolver.resolve(raw, ctx.pmPivotHour(), ctx.awaiting("TIME"));

        // "pasarla del sábado a las 2 al domingo a las 3": el destino es lo que sigue a "al / para el"
        if (ctx.modifyMode()) {
            Matcher tm = TAIL.matcher(n);
            if (tm.find()) {
                String tail = tm.group(1);
                var d2 = dateResolver.resolve(tail);
                var t2 = timeResolver.resolve(tail, ctx.pmPivotHour(), false);
                if (d2.status() != DateExpressionResolver.Status.NONE) date = d2;
                if (t2.hasTime() || t2.hasPeriod()) time = t2;
            }
        }
        if (ctx.awaiting("DATE") && n.matches("\\d{1,2}")) {
            date = dateResolver.resolve("el " + n);
        }

        Integer party = partyFrom(n, ctx.awaiting("PARTY_SIZE"));
        ReservationType type = typeFrom(n);
        String name = nameFrom(raw, n, ctx.awaiting("NAME"));
        String receiptPhone = receiptPhoneFrom(raw, n);
        Long number = reservationNumberFrom(n, ctx.awaiting("RESERVATION"));
        int ordinal = ordinalFrom(n);

        boolean wantsAvailability = containsAny(n, "disponibilidad", "hay cupo", "cupos", "hay espacio",
                "tienen espacio", "tienen mesa", "hay mesa", "queda cupo", "quedan cupos", "hay lugar",
                "tienen lugar", "quedan lugares", "lugar para", "sitio para", "consigo mesa");

        boolean hasData = type != null || date.status() != DateExpressionResolver.Status.NONE
                || time.hasTime() || time.hasPeriod() || party != null || name != null || receiptPhone != null;

        boolean yes = !hasData && isYes(n);
        boolean no = !hasData && isNo(n);
        boolean discard = containsAny(n, "olvidalo", "mejor no", "ya no quiero", "dejalo asi", "no quiero reservar")
                || (n.contains("cancel") && !n.contains("reserv"));

        // normalize() elimina los signos, así que el interrogante se detecta sobre el texto crudo
        boolean questionMark = raw.indexOf('?') >= 0;
        Intent intent = intentFrom(n, hasData, date, time, party, wantsAvailability, questionMark, ctx.hasDraft());

        return new Extraction(intent, type, date, time, party, name, receiptPhone, number, ordinal,
                yes, no, discard, wantsAvailability);
    }

    private Intent intentFrom(String n, boolean hasData, DateExpressionResolver.Resolution date,
                              TimeExpressionResolver.Resolution time, Integer party, boolean wantsAvailability,
                              boolean questionMark, boolean hasDraft) {
        boolean mentionsReservation = n.contains("reserv");
        boolean cancelWord = containsAny(n, "cancel", "anul", "elimin");
        // "cancelar"/"quiero cancelar" sin repetir la palabra reserva también cuentan cuando no hay
        // borrador en curso: antes caía en GENERAL_QUESTION y una FAQ de política de cancelación
        // secuestraba la conversación en vez de cancelar de verdad.
        if (cancelWord && (mentionsReservation || (!hasDraft && !questionMark))) return Intent.CANCEL_RESERVATION;

        boolean modifyWord = containsAny(n, "cambiar", "modificar", "mover", "pasar", "reprogramar", "aumentar",
                "agregar", "sumar", "adelantar", "aplazar", "actualizar", "cambio");
        // Con borrador activo "cambiar" se refiere al dato que se está pidiendo ("cambiar a 6"),
        // no a otra reserva: sin la palabra "reserva" el flujo de creación sigue su curso.
        if (modifyWord && (mentionsReservation || !hasDraft)) return Intent.MODIFY_RESERVATION;

        if (containsAny(n, "mi reserva", "mis reservas", "estado de mi reserva", "tengo una reserva",
                "tengo reserva", "tengo reservas", "ya reserve", "ya hice la reserva", "ya aparte",
                "numero de reserva", "consultar reserva", "consultar mi reserva", "ver mi reserva")) {
            return Intent.GET_RESERVATION;
        }
        if (containsAny(n, "comprobante", "ya pague", "ya hice el pago", "transferi", "consigne")) {
            return Intent.PAYMENT_RECEIPT;
        }

        boolean isQuestion = isQuestion(n, questionMark);
        boolean concrete = party != null || date.status() != DateExpressionResolver.Status.NONE
                || time.hasTime() || time.hasPeriod();
        boolean wantsToBook = mentionsReservation || containsAny(n, "apartar", "agendar", "una mesa", "mesa para",
                "vip", "espacio privado", "sala privada", "zona privada", "salon privado", "lugar privado",
                "quiero ir", "queremos ir", "vamos a ir", "me gustaria ir");

        if (wantsAvailability && (concrete || wantsToBook)) return Intent.CHECK_AVAILABILITY;
        // Petición de información ("información de las reservas", "precio del VIP") sin verbo claro de reservar
        boolean informational = containsAny(n, "informacion", "como funciona", "como funcionan", "cuanto cuesta",
                "cuanto vale", "cuanto cobran", "precio", "valor de", "politica", "condiciones", "requisitos");
        boolean bookingVerb = containsAny(n, "quiero reservar", "queremos reservar", "quisiera reservar",
                "necesito reservar", "deseo reservar", "me gustaria reservar", "quiero apartar", "quiero agendar",
                "quiero separar", "quiero una mesa", "quiero el vip", "reservame", "reservarme");

        if (wantsToBook) {
            // Pregunta o petición de información ("¿cómo funciona la reserva?", "información de las reservas")
            // sin datos concretos → FAQ / IA. Con verbo claro de reservar y sin pregunta, es una reserva.
            if ((isQuestion || (informational && !bookingVerb)) && !concrete) return Intent.GENERAL_QUESTION;
            return Intent.CREATE_RESERVATION;
        }
        if (party != null && (date.status() != DateExpressionResolver.Status.NONE || time.hasTime())) {
            return Intent.CREATE_RESERVATION;
        }
        return (isQuestion || informational) ? Intent.GENERAL_QUESTION : Intent.NONE;
    }

    private boolean isQuestion(String n, boolean questionMark) {
        if (questionMark) return true;
        return containsAny(n, "como ", "cuanto", "cuantos", "que ", "donde", "cual ",
                "cuales", "cuando", "hasta que hora", "puedo ", "se puede", "aceptan", "tienen ");
    }

    // ── Campos ────────────────────────────────────────────────────────────

    private Integer partyFrom(String n, boolean awaitingParty) {
        Matcher m = PARTY_A.matcher(n);
        if (m.find()) return number(m.group(1));
        m = PARTY_B.matcher(n);
        if (m.find()) return number(m.group(1));
        if (PARTY_ONE.matcher(n).find()) return 1;
        if (awaitingParty) {
            m = BARE_PARTY.matcher(n);
            if (m.matches()) return number(m.group(1));
        }
        return null;
    }

    private Integer number(String token) {
        if (token.chars().allMatch(Character::isDigit)) return Integer.parseInt(token);
        return NUMBER_WORDS.get(token);
    }

    private ReservationType typeFrom(String n) {
        boolean noVip = containsAny(n, "no quiero vip", "sin vip", "no vip", "no es vip", "reserva normal",
                "mesa normal", "una normal", "solo normal") || n.matches(".*\\bnormal\\b.*");
        if (noVip) return ReservationType.NORMAL;
        boolean vip = containsAny(n, "vip", "espacio privado", "sala privada", "zona privada", "salon privado",
                "lugar privado", "area privada");
        return vip ? ReservationType.VIP : null;
    }

    private String nameFrom(String raw, String n, boolean awaitingName) {
        Matcher m = NAME_PATTERN.matcher(raw);
        if (m.find()) {
            String cleaned = cutAtStopWord(m.group(1));
            if (cleaned != null) return titleCase(cleaned);
        }
        if (awaitingName) {
            String t = raw.replaceAll("[.,;!¡¿?]+$", "").trim();
            String[] words = t.split("\\s+");
            boolean looksLikeName = !t.isEmpty() && words.length <= 4 && t.length() <= 60
                    && t.matches("(?iu)[\\p{L}]+(?:\\s+[\\p{L}]+){0,3}")
                    && !isYes(n) && !isNo(n);
            if (looksLikeName) return titleCase(t);
        }
        return null;
    }

    private String cutAtStopWord(String captured) {
        StringBuilder sb = new StringBuilder();
        for (String w : captured.trim().split("\\s+")) {
            String norm = TextNormalizer.normalize(w);
            if (NAME_STOP.contains(norm) && sb.length() > 0) break;
            if (NAME_STOP.contains(norm)) return null;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        String out = sb.toString().trim();
        return out.length() >= 2 ? out : null;
    }

    private String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private String receiptPhoneFrom(String raw, String n) {
        if (!containsAny(n, "comprobante", "confirmacion", "resumen")) return null;
        Matcher m = PHONE_PATTERN.matcher(raw);
        while (m.find()) {
            String digits = m.group().replaceAll("[^0-9]", "");
            if (digits.length() >= 10 && digits.length() <= 13) return digits;
        }
        return null;
    }

    private Long reservationNumberFrom(String n, boolean awaitingReservation) {
        Matcher m = RESERVATION_NUMBER.matcher(n);
        if (m.find()) return Long.parseLong(m.group(1) != null ? m.group(1) : m.group(2));
        if (awaitingReservation) {
            m = BARE_NUMBER.matcher(n);
            if (m.matches()) return Long.parseLong(m.group(1));
        }
        return null;
    }

    /** 1, 2, 3 = primera, segunda, tercera; -1 = última. */
    private int ordinalFrom(String n) {
        if (containsAny(n, "ultima", "ultimo")) return -1;
        if (containsAny(n, "primera", "primero")) return 1;
        if (containsAny(n, "segunda", "segundo")) return 2;
        if (containsAny(n, "tercera", "tercero")) return 3;
        return 0;
    }

    private boolean isYes(String n) {
        int words = n.isEmpty() ? 0 : n.split(" ").length;
        return YES.contains(n) || (n.startsWith("si ") && words <= 4)
                || ((n.startsWith("dale ") || n.startsWith("ok ")) && words <= 3);
    }

    private boolean isNo(String n) {
        if (n.equals("no se") || n.startsWith("no se ")) return false;
        int words = n.isEmpty() ? 0 : n.split(" ").length;
        return NO.contains(n) || (n.startsWith("no ") && words <= 4);
    }

    private boolean containsAny(String n, String... needles) {
        for (String s : needles) if (n.contains(s)) return true;
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Respaldo LLM (solo interpreta; los datos vuelven a pasar por los resolvers del backend)
    // ══════════════════════════════════════════════════════════════════════

    private static final String LLM_SYSTEM = """
            Eres un extractor de datos para un sistema de reservas de restaurante. NO conversas con el cliente.
            Lees UN mensaje (en español) y devuelves SOLO un JSON válido, sin texto adicional ni markdown:
            {
              "intent": "CREATE_RESERVATION|MODIFY_RESERVATION|CANCEL_RESERVATION|GET_RESERVATION|PAYMENT_RECEIPT|GENERAL_QUESTION|NONE",
              "reservationType": "NORMAL|VIP|null",
              "dateText": null,
              "timeText": null,
              "partySize": null,
              "customerName": null,
              "confirmation": "YES|NO|null"
            }
            Reglas:
            - dateText y timeText: copia TEXTUALMENTE la expresión del cliente ("este sábado", "mañana", "a las dos y media"). NO calcules fechas ni horas.
            - partySize: número entero de personas. Si el cliente describe el grupo ("mis papás, mi esposa y yo"), cuenta las personas.
            - reservationType: VIP solo si pide el espacio VIP/privado; NORMAL si dice que no quiere VIP; si no se menciona, null.
            - Si un dato no aparece, usa null. NUNCA inventes datos.
            - NO decides disponibilidad, precios ni si existe una reserva.
            """;

    /** Completa lo que las reglas locales no entendieron. Ante cualquier fallo devuelve {@code local} intacto. */
    public Extraction enrich(String text, Context ctx, Extraction local) {
        try {
            String system = LLM_SYSTEM + (ctx.awaiting() != null
                    ? "\nContexto: el asistente acaba de preguntarle al cliente por: " + ctx.awaiting() + "." : "");
            OpenAIServiceClient.OpenAIResult result = llm.complete(system, List.of(), text);
            if (result == null || result.getText() == null || !result.getText().contains("{")) return local;

            String json = result.getText().trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) return local;
            JsonNode j = mapper.readTree(json.substring(start, end + 1));

            Intent intent = parseIntent(str(j, "intent"));
            ReservationType type = "VIP".equalsIgnoreCase(str(j, "reservationType")) ? ReservationType.VIP
                    : "NORMAL".equalsIgnoreCase(str(j, "reservationType")) ? ReservationType.NORMAL : null;
            String dateText = str(j, "dateText");
            String timeText = str(j, "timeText");
            Integer party = j.hasNonNull("partySize") && j.get("partySize").canConvertToInt()
                    ? j.get("partySize").asInt() : null;
            if (party != null && (party < 1 || party > 999)) party = null;
            String name = sanitizeName(str(j, "customerName"));
            String confirmation = str(j, "confirmation");

            var llmDate = dateText != null ? dateResolver.resolve(dateText) : DateExpressionResolver.Resolution.none();
            var llmTime = timeText != null ? timeResolver.resolve(timeText, ctx.pmPivotHour(), true)
                    : TimeExpressionResolver.Resolution.none();

            Extraction merged = new Extraction(
                    local.intent() == Intent.NONE || local.intent() == Intent.GENERAL_QUESTION ? intent : local.intent(),
                    local.type() != null ? local.type() : type,
                    local.date().status() != DateExpressionResolver.Status.NONE ? local.date() : llmDate,
                    local.time().hasTime() || local.time().hasPeriod() ? local.time() : llmTime,
                    local.partySize() != null ? local.partySize() : party,
                    local.customerName() != null ? local.customerName() : name,
                    local.receiptPhone(), local.reservationNumber(), local.ordinal(),
                    local.yes() || ("YES".equalsIgnoreCase(confirmation) && !hasLlmData(type, llmDate, llmTime, party, name)),
                    local.no() || ("NO".equalsIgnoreCase(confirmation) && !hasLlmData(type, llmDate, llmTime, party, name)),
                    local.discard(), local.wantsAvailability());
            log.info("[RESERVATION] LLM enrichment applied intent={} party={} dateText={} timeText={}",
                    merged.intent(), merged.partySize(), dateText, timeText);
            return merged;
        } catch (Exception e) {
            log.warn("[RESERVATION] LLM extraction unavailable, using local rules only: {}", e.getMessage());
            return local;
        }
    }

    private boolean hasLlmData(ReservationType type, DateExpressionResolver.Resolution d,
                               TimeExpressionResolver.Resolution t, Integer party, String name) {
        return type != null || d.status() != DateExpressionResolver.Status.NONE || t.hasTime() || t.hasPeriod()
                || party != null || name != null;
    }

    private Intent parseIntent(String s) {
        if (s == null) return Intent.NONE;
        try {
            return Intent.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Intent.NONE;
        }
    }

    private String str(JsonNode j, String field) {
        if (!j.hasNonNull(field)) return null;
        String v = j.get(field).asText();
        return v == null || v.isBlank() || v.equalsIgnoreCase("null") ? null : v.trim();
    }

    private String sanitizeName(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.matches("(?iu)[\\p{L}]+(?:\\s+[\\p{L}]+){0,3}") && t.length() <= 60 ? titleCase(t) : null;
    }
}
