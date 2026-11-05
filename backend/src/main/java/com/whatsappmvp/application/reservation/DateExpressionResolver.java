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

    /** Abreviaturas que la gente escribe por WhatsApp: "24 oct", "05 dic", "2 nov". */
    private static final Map<String, Integer> MONTHS_ABBR = Map.ofEntries(
            Map.entry("ene", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("abr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("ago", 8),
            Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("oct", 10), Map.entry("nov", 11),
            Map.entry("dic", 12));

    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern SLASH = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b");
    private static final Pattern DE_MES = Pattern.compile(
            "\\b(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|"
                    + "octubre|noviembre|diciembre)(?:\\s+(?:de|del)\\s+(\\d{4}))?\\b");
    private static final Pattern DE_MES_ABBR = Pattern.compile(
            "\\b(\\d{1,2})\\s*(?:de\\s+)?(ene|feb|mar|abr|may|jun|jul|ago|sept?|oct|nov|dic)\\b"
                    + "(?:\\s+(?:de\\s+|del\\s+)?(\\d{4}))?");
    // "sábado 31", "domingo 2": el número es el día del mes, no el próximo sábado cualquiera.
    private static final Pattern WEEKDAY_DAY = Pattern.compile(
            "\\b(?:(?:este|el|ese)\\s+)?(lunes|martes|miercoles|jueves|viernes|sabado|domingo)s?\\s+(\\d{1,2})\\b");
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
        String original = text.toString();
        Set<LocalDate> found = new LinkedHashSet<>();

        scan(text, ISO, m -> safeDate(int4(m, 1), int2(m, 2), int2(m, 3)), found);
        scan(text, DE_MES, m -> inferYear(MONTHS.get(m.group(2)), Integer.parseInt(m.group(1)),
                m.group(3) != null ? Integer.parseInt(m.group(3)) : null, today), found);
        scan(text, DE_MES_ABBR, m -> inferYear(MONTHS_ABBR.get(m.group(2)), Integer.parseInt(m.group(1)),
                m.group(3) != null ? Integer.parseInt(m.group(3)) : null, today), found);
        scan(text, SLASH, m -> inferYear(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)),
                m.group(3) != null ? normalizeYear(m.group(3)) : null, today), found);
        scan(text, PASADO_MANANA, m -> today.plusDays(2), found);
        scan(text, MANANA, m -> today.plusDays(1), found);
        scan(text, HOY, m -> today, found);

        // Si ya hay una fecha explícita ("lunes 2 de noviembre"), esa fecha manda. Antes se sumaba
        // también el día de la semana y el bot respondía "me mencionaste varias fechas".
        if (found.isEmpty()) {
            scan(text, WEEKDAY_DAY, m -> weekdayWithDay(m, today), found);
        }
        if (found.isEmpty()) {
            scan(text, WEEKDAY, m -> weekday(m, today), found);
        }

        if (found.isEmpty()) {
            Matcher dm = DAY_OF_MONTH.matcher(text);
            if (dm.find()) {
                LocalDate d = dayOfMonth(Integer.parseInt(dm.group(1)), today);
                if (d != null) found.add(d);
            }
        }

        if (found.size() > 1) return Resolution.ambiguous("MULTIPLE");
        if (found.size() == 1) {
            Resolution mismatch = weekdayMismatch(original, found.iterator().next(), today);
            if (mismatch != null) return mismatch;
            return Resolution.resolved(found.iterator().next());
        }
        if (WEEKEND.matcher(text).find()) return Resolution.ambiguous("WEEKEND");
        return Resolution.none();
    }

    /**
     * "sábado 30" cuando el 30 cae viernes: en vez de elegir un día distinto en silencio
     * (el cliente pidió sábado), se pregunta cuál de los dos quiere.
     */
    private Resolution weekdayMismatch(String original, LocalDate resolved, LocalDate today) {
        Matcher m = WEEKDAY_DAY.matcher(original);
        if (!m.find()) return null;
        DayOfWeek dow = DAYS.get(m.group(1));
        if (dow == null || resolved.getDayOfWeek() == dow) return null;
        LocalDate alternative = resolved.plusDays(1).with(TemporalAdjusters.nextOrSame(dow));
        if (alternative.equals(resolved)) return null;
        return Resolution.ambiguous("WEEKDAY|" + resolved + "|" + alternative);
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

    /**
     * "sábado 31" / "domingo 2": busca el próximo día del mes que caiga en ese día de la semana.
     * Si en los próximos 2 meses no coincide (p. ej. "sábado 30" cuando el 30 es viernes),
     * gana el número de día para no mandar al cliente a una fecha lejana.
     */
    private LocalDate weekdayWithDay(Matcher m, LocalDate today) {
        DayOfWeek dow = DAYS.get(m.group(1));
        int day = Integer.parseInt(m.group(2));
        LocalDate cursor = today.withDayOfMonth(1);
        for (int i = 0; i < 2; i++, cursor = cursor.plusMonths(1)) {
            if (day > cursor.lengthOfMonth()) continue;
            LocalDate candidate = cursor.withDayOfMonth(day);
            if (!candidate.isBefore(today) && candidate.getDayOfWeek() == dow) return candidate;
        }
        return dayOfMonth(day, today);
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
        // Solo se pasa al año siguiente si la fecha quedó claramente atrás ("5 de enero" dicho en
        // octubre). Si fue hace pocos días ("24 oct" dicho el 26) se devuelve tal cual: el motor
        // de reservas avisa que ya pasó, en vez de agendar sin querer para dentro de un año.
        if (candidate != null && candidate.isBefore(today.minusDays(45))) {
            candidate = safeDate(today.getYear() + 1, month, day);
        }
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
