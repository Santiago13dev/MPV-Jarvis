package com.whatsappmvp.application.reservation;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resuelve expresiones de hora en español: "a las 2", "2pm", "14:00", "12 y media",
 * "a las dos de la tarde", "una y media", "mediodía", "en la tarde".
 *
 * Sin indicador am/pm, las horas menores a {@code pmPivotHour} se asumen de la tarde
 * (el restaurante recibe reservas desde las 11:30, así que "a las 2" es 2:00 PM).
 * Una franja ("en la tarde") no es una hora concreta: se devuelve como {@link Period}.
 */
@Component
public class TimeExpressionResolver {

    public enum Period { MORNING, AFTERNOON, EVENING }

    public record Resolution(LocalTime time, Period period) {
        public static Resolution none() { return new Resolution(null, null); }
        public boolean hasTime() { return time != null; }
        public boolean hasPeriod() { return period != null; }
    }

    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("una", 1), Map.entry("un", 1), Map.entry("dos", 2), Map.entry("tres", 3),
            Map.entry("cuatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("siete", 7),
            Map.entry("ocho", 8), Map.entry("nueve", 9), Map.entry("diez", 10), Map.entry("once", 11),
            Map.entry("doce", 12));

    private static final String HOUR = "(\\d{1,2}|una|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)";
    private static final String MER = "(am|pm|de la manana|de la tarde|de la noche|del dia|del mediodia)";

    private static final Pattern CLOCK = Pattern.compile("\\b(\\d{1,2})\\s*:\\s*(\\d{2})\\s*(am|pm)?\\b");
    // "1-00pm", "1.00pm", "1 00pm": el guion/punto/espacio también se usa como separador de hora.
    private static final Pattern SEP_CLOCK_MER = Pattern.compile(
            "\\b(\\d{1,2})\\s*[-.\\s]\\s*(\\d{2})\\s*(am|pm)\\b");
    // "a las 1-00", "las 12.30": separador dentro de una frase (sin am/pm).
    private static final Pattern LAS_SEP = Pattern.compile(
            "\\b(?:a\\s+)?las?\\s+(\\d{1,2})\\s*[-.]\\s*(\\d{2})\\b");
    // "100pm" = 1:00 PM, "1230pm" = 12:30 PM (formato sin separador que usa mucha gente).
    private static final Pattern COMPACT_MER = Pattern.compile("\\b(\\d{3,4})\\s*(am|pm)\\b");
    // "a las 1230", "para las 100": hora compacta dentro de una frase, sin am/pm.
    private static final Pattern LAS_COMPACT = Pattern.compile(
            "\\b(?:a\\s+)?las?\\s+(\\d{3,4})\\b(?!\\s*(?:personas?|pax|invitados|comensales|mesas?))");
    private static final Pattern LAS = Pattern.compile(
            "\\b(?:a\\s+)?las?\\s+" + HOUR + "(?!\\s*(?:personas?|pax|invitados|mesas?))"
                    + "(?:\\s+(?:y|con)\\s+(media|cuarto|\\d{1,2}))?"
                    + "(?:\\s+menos\\s+(cuarto|\\d{1,2}))?"
                    + "(?:\\s*" + MER + ")?\\b");
    private static final Pattern H_MER = Pattern.compile("\\b(\\d{1,2})\\s*(am|pm)\\b");
    private static final Pattern MEDIODIA = Pattern.compile(
            "\\b(despues del |pasado el |luego del )?medio ?dia\\b");
    private static final Pattern PERIOD = Pattern.compile("\\b(?:en la|por la|de la|a la)\\s+(manana|tarde|noche)\\b");
    private static final Pattern BARE = Pattern.compile(
            "^(?:a\\s+)?(?:las?\\s+)?" + HOUR + "(?:\\s+(?:y|con)\\s+(media|cuarto|\\d{1,2}))?(?:\\s*" + MER + ")?$");
    // Respuestas cortas cuando el bot acaba de pedir la hora: "1-00", "1.00", "100", "1230".
    private static final Pattern BARE_SEP = Pattern.compile(
            "^(?:a\\s+)?(?:las?\\s+)?(\\d{1,2})\\s*[-.]\\s*(\\d{2})$");
    private static final Pattern BARE_COMPACT = Pattern.compile(
            "^(?:a\\s+)?(?:las?\\s+)?(\\d{3,4})$");

    /**
     * @param raw              texto del cliente
     * @param pmPivotHour      horas en [1, pivot) sin am/pm se asumen PM (típicamente la hora de apertura, 11)
     * @param bareNumberAllowed true si el bot acaba de preguntar la hora ("2" → 2:00 PM)
     */
    public Resolution resolve(String raw, int pmPivotHour, boolean bareNumberAllowed) {
        if (raw == null || raw.isBlank()) return Resolution.none();
        String t = TextNormalizer.normalize(raw);
        Period hint = periodOf(t);

        Matcher m = CLOCK.matcher(t);
        if (m.find()) {
            return build(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 0, m.group(3), hint, pmPivotHour);
        }

        // "1-00pm", "1.00pm", "1 00pm"
        m = SEP_CLOCK_MER.matcher(t);
        if (m.find()) {
            return build(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 0, m.group(3), hint, pmPivotHour);
        }

        // "a las 1-00", "las 12.30" (sin am/pm)
        m = LAS_SEP.matcher(t);
        if (m.find()) {
            return build(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 0, null, hint, pmPivotHour);
        }

        // "100pm" → 1:00 PM, "1230pm" → 12:30 PM
        m = COMPACT_MER.matcher(t);
        if (m.find()) {
            Resolution compact = compactTime(m.group(1), m.group(2), hint, pmPivotHour);
            if (compact.hasTime()) return compact;
        }

        // "a las 1230" → 12:30, "para las 100" → 1:00 PM (dentro de una frase completa)
        m = LAS_COMPACT.matcher(t);
        if (m.find()) {
            Resolution compact = compactTime(m.group(1), null, hint, pmPivotHour);
            if (compact.hasTime()) return compact;
        }

        m = LAS.matcher(t);
        if (m.find()) {
            int hour = hourOf(m.group(1));
            int minute = 0;
            String plus = m.group(2);
            if (plus != null) minute = "media".equals(plus) ? 30 : "cuarto".equals(plus) ? 15 : Integer.parseInt(plus);
            int minus = 0;
            String less = m.group(3);
            if (less != null) minus = "cuarto".equals(less) ? 15 : Integer.parseInt(less);
            return build(hour, minute, minus, m.group(4), hint, pmPivotHour);
        }

        m = H_MER.matcher(t);
        if (m.find()) {
            return build(Integer.parseInt(m.group(1)), 0, 0, m.group(2), hint, pmPivotHour);
        }

        m = MEDIODIA.matcher(t);
        if (m.find()) {
            if (m.group(1) != null) return new Resolution(null, Period.AFTERNOON);
            return new Resolution(LocalTime.NOON, null);
        }

        if (bareNumberAllowed) {
            m = BARE_SEP.matcher(t);
            if (m.find()) {
                return build(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 0, null, hint, pmPivotHour);
            }
            m = BARE_COMPACT.matcher(t);
            if (m.find()) {
                Resolution compact = compactTime(m.group(1), null, hint, pmPivotHour);
                if (compact.hasTime()) return compact;
            }
            m = BARE.matcher(t);
            if (m.find()) {
                int hour = hourOf(m.group(1));
                int minute = 0;
                String plus = m.group(2);
                if (plus != null) minute = "media".equals(plus) ? 30 : "cuarto".equals(plus) ? 15 : Integer.parseInt(plus);
                return build(hour, minute, 0, m.group(3), hint, pmPivotHour);
            }
        }

        return hint != null ? new Resolution(null, hint) : Resolution.none();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Period periodOf(String t) {
        Matcher p = PERIOD.matcher(t);
        if (p.find()) {
            return switch (p.group(1)) {
                case "manana" -> Period.MORNING;
                case "tarde" -> Period.AFTERNOON;
                default -> Period.EVENING;
            };
        }
        return null;
    }

    private int hourOf(String token) {
        Integer w = WORDS.get(token);
        return w != null ? w : Integer.parseInt(token);
    }

    /** "100" → 1:00, "1230" → 12:30, "130" → 1:30. */
    private Resolution compactTime(String digits, String meridiem, Period hint, int pivot) {
        if (digits == null || digits.length() < 3 || digits.length() > 4) return Resolution.none();
        int hour;
        int minute;
        if (digits.length() == 3) {
            hour = digits.charAt(0) - '0';
            minute = Integer.parseInt(digits.substring(1));
        } else {
            hour = Integer.parseInt(digits.substring(0, 2));
            minute = Integer.parseInt(digits.substring(2));
        }
        if (minute > 59) return Resolution.none();
        return build(hour, minute, 0, meridiem, hint, pivot);
    }

    private Resolution build(int hour, int minute, int minusMinutes, String meridiem, Period hint, int pivot) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return Resolution.none();
        String mer = meridiem;
        if (mer == null && hint != null) {
            mer = hint == Period.MORNING ? "am" : "pm";
        }
        int h = hour;
        boolean pm = mer != null && (mer.equals("pm") || mer.contains("tarde") || mer.contains("noche"));
        boolean am = mer != null && (mer.equals("am") || mer.contains("manana"));
        if (pm) {
            if (h < 12) h += 12;
        } else if (!am && mer == null) {
            if (h >= 1 && h < pivot) h += 12;
        }
        int total = h * 60 + minute - minusMinutes;
        if (total < 0 || total >= 24 * 60) return Resolution.none();
        return new Resolution(LocalTime.of(total / 60, total % 60), null);
    }
}
