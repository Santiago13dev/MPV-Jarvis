package com.whatsappmvp.application.reservation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calendario de festivos colombianos.
 *
 * Reglas:
 * - Festivos de fecha fija (1-ene, 1-may, 20-jul, 7-ago, 8-dic, 25-dic).
 * - Festivos trasladados al lunes siguiente (Ley Emiliani): Reyes, San José, San Pedro y San Pablo,
 *   Asunción, Día de la Raza, Todos los Santos e Independencia de Cartagena.
 * - Festivos de Semana Santa: Jueves y Viernes Santo, Ascensión, Corpus Christi y Sagrado Corazón.
 *
 * El restaurante solo abre los lunes que sean festivos; este servicio NO decide si un lunes cualquiera
 * abre. Esa regla vive en {@link ReservationCalendarService} y en la configuración del restaurante.
 */
public final class ColombianHolidays {

    private static final Map<Integer, Set<LocalDate>> CACHE = new ConcurrentHashMap<>();

    private ColombianHolidays() {
        // utilidad
    }

    public static boolean isHoliday(LocalDate date) {
        return date != null && holidays(date.getYear()).contains(date);
    }

    public static boolean isHolidayMonday(LocalDate date) {
        return date != null && date.getDayOfWeek() == DayOfWeek.MONDAY && isHoliday(date);
    }

    /** Festivos del año, calculados con las reglas colombianas. */
    public static Set<LocalDate> holidays(int year) {
        return CACHE.computeIfAbsent(year, ColombianHolidays::calculate);
    }

    /** Siguiente lunes festivo a partir de {@code from} (inclusive). */
    public static LocalDate nextHolidayMonday(LocalDate from) {
        LocalDate d = from;
        for (int i = 0; i < 370; i++, d = d.plusDays(1)) {
            if (isHolidayMonday(d)) return d;
        }
        throw new IllegalStateException("No se encontró un lunes festivo en el rango esperado");
    }

    private static Set<LocalDate> calculate(int year) {
        Set<LocalDate> days = new HashSet<>();

        // Fecha fija (pueden caer en lunes o no).
        days.add(LocalDate.of(year, Month.JANUARY, 1));   // Año Nuevo
        days.add(LocalDate.of(year, Month.MAY, 1));       // Día del Trabajo
        days.add(LocalDate.of(year, Month.JULY, 20));     // Independencia
        days.add(LocalDate.of(year, Month.AUGUST, 7));    // Batalla de Boyacá
        days.add(LocalDate.of(year, Month.DECEMBER, 8));  // Inmaculada Concepción
        days.add(LocalDate.of(year, Month.DECEMBER, 25)); // Navidad

        // Ley Emiliani: se trasladan al lunes siguiente.
        days.add(nextMonday(LocalDate.of(year, Month.JANUARY, 6)));   // Reyes Magos
        days.add(nextMonday(LocalDate.of(year, Month.MARCH, 19)));    // San José
        days.add(nextMonday(LocalDate.of(year, Month.JUNE, 29)));     // San Pedro y San Pablo
        days.add(nextMonday(LocalDate.of(year, Month.AUGUST, 15)));   // Asunción
        days.add(nextMonday(LocalDate.of(year, Month.OCTOBER, 12)));  // Día de la Raza
        days.add(nextMonday(LocalDate.of(year, Month.NOVEMBER, 1)));  // Todos los Santos
        days.add(nextMonday(LocalDate.of(year, Month.NOVEMBER, 11))); // Independencia de Cartagena

        // Semana Santa y festivos móviles.
        LocalDate easter = easterSunday(year);
        days.add(easter.minusDays(3)); // Jueves Santo
        days.add(easter.minusDays(2)); // Viernes Santo
        days.add(easter.plusDays(43)); // Ascensión (lunes)
        days.add(easter.plusDays(64)); // Corpus Christi (lunes)
        days.add(easter.plusDays(71)); // Sagrado Corazón (lunes)

        return Collections.unmodifiableSet(days);
    }

    private static LocalDate nextMonday(LocalDate date) {
        int days = (DayOfWeek.MONDAY.getValue() - date.getDayOfWeek().getValue() + 7) % 7;
        return date.plusDays(days);
    }

    /** Algoritmo gregoriano de Meeus/Jones/Butcher. */
    private static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
