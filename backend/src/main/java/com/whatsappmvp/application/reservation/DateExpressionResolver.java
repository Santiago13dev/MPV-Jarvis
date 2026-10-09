package com.whatsappmvp.application.reservation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resuelve expresiones de fecha en español a una fecha real, usando el reloj del servidor.
 * La lógica de calendario pertenece al backend: el LLM solo copia el texto que dijo el cliente.
 *
 * Convenciones:
 *  - "hoy", "mañana", "pasado mañana"
 *  - "este sábado" / "el sábado" / "sábado" → próxima ocurrencia desde hoy (si hoy es sábado, hoy)
 *  - "el próximo sábado" / "sábado que viene" → próxima ocurrencia estrictamente posterior a hoy
 *  - "dd/mm", "dd/mm/yyyy", "yyyy-mm-dd", "10 de octubre", "el 10"
 *  - "fin de semana" o varios días distintos → AMBIGUOUS (hay que preguntar)
 */
@Component
@RequiredArgsConstructor
public class DateExpressionResolver {

    public enum Status { RESOLVED, NONE, AMBIGUOUS }

    public record Resolution(Status status, LocalDate date, String note) {
        public static Resolution none() { return new Resolution(Status.NONE, null, null); }
        public static Resolution resolved(LocalDate d) { return new Resolution(Status.RESOLVED, d, null); }
        public static Resolution ambiguous(String note) { return new Resolution(Status.AMBIGUOUS, null, note); }
        public boolean isResolved() { return status == Status.RESOLVED; }
    }

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "lunes", DayOfWeek.MONDAY, "martes", DayOfWeek.TUESDAY, "miercoles", DayOfWeek.WEDNESDAY,
            "jueves", DayOfWeek.THURSDAY, "viernes", DayOfWeek.FRIDAY, "sabado", DayOfWeek.SATURDAY,
            "domingo", DayOfWeek.SUNDAY);

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3), Map.entry("abril", 4),
            Map.entry("mayo", 5), Map.entry("junio", 6), Map.entry("julio", 7), Map.entry("agosto", 8),
            Map.entry("septiembre", 9), Map.entry("setiembre", 9), Map.entry("octubre", 10),
            Map.entry("noviembre", 11), Map.entry("diciembre", 12));

    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern SLASH = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b");
    private static final Pattern DE_MES = Pattern.compile(
            "\\b(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|"
                    + "octubre|noviembre|diciembre)(?:\\s+(?:de|del)\\s+(\\d{4}))?\\b");
    private static final Pattern PASADO_MANANA = Pattern.compile("\\bpasado manana\\b");
    // "mañana" = día siguiente; "la mañana"/"esta mañana" = franja del día (no es una fecha)
    private static final Pattern MANANA = Pattern.compile("(?<!la )(?<!esta )\\bmanana\\b");
    private static final Pattern HOY = Pattern.compile("\\bhoy\\b");
    private static final Pattern WEEKDAY = Pattern.compile(
            "\\b(?:(este|el|ese|proximo|siguiente)\\s+)?(lunes|martes|miercoles|jueves|viernes|sabado|domingo)s?"
                    + "(?:\\s+(proximo|siguiente|que viene))?\\b");
    private static final Pattern WEEKEND = Pattern.compile("\\b(fin de semana|finde)\\b");
    private static final Pattern DAY_OF_MONTH = Pattern.compile(
            "\\bel\\s+(\\d{1,2})\\b(?!\\s*(?:personas?|:|y media|\\d))");

    private final Clock clock;

    public Resolution resolve(String raw) {
        if (raw == null || raw.isBlank()) return Resolution.none();
        LocalDate today = LocalDate.now(clock);
        StringBuilder text = new StringBuilder(" " + TextNormalizer.normalize(raw) + " ");
        Set<LocalDate> found = new LinkedHashSet<>();

        scan(text, ISO, m -> safeDate(int4(m, 1), int2(m, 2), int2(m, 3)), found);
        scan(text, DE_MES, m -> inferYear(MONTHS.get(m.group(2)), Integer.parseInt(m.group(1)),
                m.group(3) != null ? Integer.parseInt(m.group(3)) : null, today), found);
        scan(text, SLASH, m -> inferYear(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)),
                m.group(3) != null ? normalizeYear(m.group(3)) : null, today), found);
        scan(text, PASADO_MANANA, m -> today.plusDays(2), found);
        scan(text, MANANA, m -> today.plusDays(1), found);
        scan(text, HOY, m -> today, found);
        scan(text, WEEKDAY, m -> weekday(m, today), found);

        if (found.isEmpty()) {
            Matcher dm = DAY_OF_MONTH.matcher(text);
            if (dm.find()) {
                LocalDate d = dayOfMonth(Integer.parseInt(dm.group(1)), today);
                if (d != null) found.add(d);
            }
        }

        if (found.size() > 1) return Resolution.ambiguous("MULTIPLE");
        if (found.size() == 1) return Resolution.resolved(found.iterator().next());
        if (WEEKEND.matcher(text).find()) return Resolution.ambiguous("WEEKEND");
        return Resolution.none();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Busca el patrón, acumula fechas válidas y enmascara lo consumido para que no lo reuse otro patrón. */
    private void scan(StringBuilder text, Pattern p, Function<Matcher, LocalDate> f, Set<LocalDate> found) {
        Matcher m = p.matcher(text.toString());
        StringBuilder mask = new StringBuilder(text);
        boolean any = false;
        while (m.find()) {
            any = true;
            LocalDate d = f.apply(m);
            if (d != null) found.add(d);
            for (int i = m.start(); i < m.end(); i++) mask.setCharAt(i, ' ');
        }
        if (any) {
            text.setLength(0);
            text.append(mask);
        }
    }

    private LocalDate weekday(Matcher m, LocalDate today) {
        DayOfWeek dow = DAYS.get(m.group(2));
        String lead = m.group(1);
        String trail = m.group(3);
        boolean strictNext = "proximo".equals(lead) || "siguiente".equals(lead) || trail != null;
        return strictNext
                ? today.plusDays(1).with(TemporalAdjusters.nextOrSame(dow))
                : today.with(TemporalAdjusters.nextOrSame(dow));
    }

    private LocalDate dayOfMonth(int day, LocalDate today) {
        LocalDate cursor = today.withDayOfMonth(1);
        for (int i = 0; i < 12; i++, cursor = cursor.plusMonths(1)) {
            if (day >= 1 && day <= cursor.lengthOfMonth()) {
                LocalDate candidate = cursor.withDayOfMonth(day);
                if (!candidate.isBefore(today)) return candidate;
            }
        }
        return null;
    }

    private LocalDate inferYear(int month, int day, Integer year, LocalDate today) {
        if (year != null) return safeDate(year, month, day);
        LocalDate candidate = safeDate(today.getYear(), month, day);
        if (candidate != null && candidate.isBefore(today)) candidate = safeDate(today.getYear() + 1, month, day);
        return candidate;
    }

    private int normalizeYear(String y) {
        int v = Integer.parseInt(y);
        return y.length() == 2 ? 2000 + v : v;
    }

    private LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    private int int4(Matcher m, int g) { return Integer.parseInt(m.group(g)); }

    private int int2(Matcher m, int g) { return Integer.parseInt(m.group(g)); }
}
