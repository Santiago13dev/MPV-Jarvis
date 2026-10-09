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
