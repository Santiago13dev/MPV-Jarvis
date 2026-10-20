package com.whatsappmvp.application.reservation;

import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Regla de calendario: sábados/domingos + lunes festivos (configurable). */
class ReservationCalendarServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T15:00:00Z"), BOGOTA);
    private final ReservationCalendarService calendar = new ReservationCalendarService(clock, null);

    @Test
    void holidayMondayIsOpenByDefault_butAnOrdinaryMondayIsNot() {
        RestaurantSettingsEntity s = RestaurantSettingsEntity.builder().build();

        assertThat(calendar.isOperatingDay(LocalDate.of(2026, 11, 2), s)).isTrue();
        assertThat(calendar.isOperatingDay(LocalDate.of(2026, 11, 9), s)).isFalse();
        assertThat(calendar.isOperatingDay(LocalDate.of(2026, 11, 14), s)).isTrue(); // sábado
        assertThat(calendar.isOperatingDay(LocalDate.of(2026, 11, 15), s)).isTrue(); // domingo
    }

    @Test
    void holidayMondaysCanBeDisabledFromTheDashboard() {
        RestaurantSettingsEntity s = RestaurantSettingsEntity.builder().build();
        s.setOpenOnHolidayMondays(false);

        assertThat(calendar.isOperatingDay(LocalDate.of(2026, 11, 2), s)).isFalse();
        assertThat(calendar.isOperatingDay(LocalDate.of(2026, 11, 14), s)).isTrue();
    }

    @Test
    void ifMondayIsExplicitlySelectedAllMondaysOpen() {
        RestaurantSettingsEntity s = RestaurantSettingsEntity.builder().build();
        s.setOpenOnHolidayMondays(false);
        s.setOperatingDaysSet(Set.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SATURDAY));

        assertThat(calendar.isOperatingDay(LocalDate.of(2026, 11, 9), s)).isTrue();
        assertThat(calendar.isOperatingDay(LocalDate.of(2026, 11, 10), s)).isFalse();
    }
}
