package com.whatsappmvp.application.reservation;

import com.whatsappmvp.infrastructure.persistence.entity.ReservationBlockEntity;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationBlockJpaRepository;
import com.whatsappmvp.domain.enums.ReservationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Calendario del restaurante: qué días opera, qué franjas existen y cuáles están bloqueadas.
 * Usa SIEMPRE el reloj del servidor (America/Bogota por defecto).
 */
@Service
@RequiredArgsConstructor
public class ReservationCalendarService {

    private static final int MAX_LOOKAHEAD_DAYS = 120;

    private final Clock clock;
    private final ReservationBlockJpaRepository blockRepository;

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalTime now() {
        return LocalTime.now(clock);
    }

    public LocalDateTime nowDateTime() {
        return LocalDateTime.now(clock);
    }

    public boolean isOperatingDay(LocalDate date, RestaurantSettingsEntity s) {
        if (s.operatingDaysSet().contains(date.getDayOfWeek())) return true;
        // Los lunes festivos son un día operativo adicional, configurable. No se abren todos los lunes.
        return Boolean.TRUE.equals(s.getOpenOnHolidayMondays())
                && date.getDayOfWeek() == DayOfWeek.MONDAY
                && ColombianHolidays.isHoliday(date);
    }

    /**
     * Día operativo según el tipo de reserva:
     *   NORMAL: sábados y lunes festivos.
     *   VIP: sábados, domingos y lunes festivos.
     * Los festivos que no son lunes no abren el restaurante (la operación es sábados/domingos/festivos lunes).
     */
    public boolean isOperatingDay(LocalDate date, RestaurantSettingsEntity s, ReservationType type) {
        if (type == null) return isOperatingDay(date, s);
        boolean holidayMonday = Boolean.TRUE.equals(s.getOpenOnHolidayMondays())
                && date.getDayOfWeek() == DayOfWeek.MONDAY
                && ColombianHolidays.isHoliday(date);
        if (holidayMonday) return true;
        if (type == ReservationType.VIP) {
            return s.operatingDaysSet().contains(date.getDayOfWeek());
        }
        // NORMAL: solo sábados (los domingos son VIP).
        return date.getDayOfWeek() == DayOfWeek.SATURDAY;
    }

    /** Franjas del día: desde la hora de inicio hasta la hora final (inclusive) cada N minutos. */
    public List<LocalTime> slotTimes(RestaurantSettingsEntity s) {
        List<LocalTime> slots = new ArrayList<>();
        int step = Math.max(5, s.getSlotIntervalMinutes());
        int start = s.getReservationStartTime().toSecondOfDay() / 60;
        int end = s.getReservationEndTime().toSecondOfDay() / 60;
        for (int m = start; m <= end; m += step) {
            slots.add(LocalTime.of(m / 60, m % 60));
        }
        return slots;
    }

    public boolean isDateBlocked(LocalDate date) {
        return blockRepository.findByBlockDate(date).stream().anyMatch(b -> b.getSlotTime() == null);
    }

    public boolean isSlotBlocked(LocalDate date, LocalTime time) {
        for (ReservationBlockEntity b : blockRepository.findByBlockDate(date)) {
            if (b.getSlotTime() == null || b.getSlotTime().equals(time)) return true;
        }
        return false;
    }

    /** ¿Se puede reservar algo ese día? (opera, no bloqueado, y si es hoy aún queda alguna franja). */
    public boolean isDayBookable(LocalDate date, RestaurantSettingsEntity s) {
        if (date.isBefore(today())) return false;
        if (!isOperatingDay(date, s) || isDateBlocked(date)) return false;
        if (date.equals(today())) {
            List<LocalTime> slots = slotTimes(s);
            return !slots.isEmpty() && slots.get(slots.size() - 1).isAfter(now());
        }
        return true;
    }

    /** Igual que {@link #isDayBookable(LocalDate, RestaurantSettingsEntity)} pero según el tipo. */
    public boolean isDayBookable(LocalDate date, RestaurantSettingsEntity s, ReservationType type) {
        if (date.isBefore(today())) return false;
        if (!isOperatingDay(date, s, type) || isDateBlocked(date)) return false;
        if (date.equals(today())) {
            List<LocalTime> slots = slotTimes(s);
            return !slots.isEmpty() && slots.get(slots.size() - 1).isAfter(now());
        }
        return true;
    }

    /** Próximos días reservables a partir de {@code from} (inclusive). */
    public List<LocalDate> nextBookableDays(LocalDate from, int count, RestaurantSettingsEntity s) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = from.isBefore(today()) ? today() : from;
        for (int i = 0; i < MAX_LOOKAHEAD_DAYS && days.size() < count; i++, d = d.plusDays(1)) {
            if (isDayBookable(d, s)) days.add(d);
        }
        return days;
    }

    /** Próximos días reservables para un tipo concreto (normal o VIP). */
    public List<LocalDate> nextBookableDays(LocalDate from, int count, RestaurantSettingsEntity s,
                                            ReservationType type) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = from.isBefore(today()) ? today() : from;
        for (int i = 0; i < MAX_LOOKAHEAD_DAYS && days.size() < count; i++, d = d.plusDays(1)) {
            if (isDayBookable(d, s, type)) days.add(d);
        }
        return days;
    }
}
