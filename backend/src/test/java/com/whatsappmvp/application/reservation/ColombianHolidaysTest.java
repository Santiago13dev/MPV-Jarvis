package com.whatsappmvp.application.reservation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Calendario colombiano: los lunes festivos son los únicos lunes que abren. */
class ColombianHolidaysTest {

    @Test
    void knownHolidayMondaysIn2026() {
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 1, 12))).isTrue();  // Reyes
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 3, 23))).isTrue();  // San José
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 5, 18))).isTrue();  // Ascensión
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 6, 8))).isTrue();   // Corpus Christi
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 6, 15))).isTrue();  // Sagrado Corazón
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 6, 29))).isTrue();  // San Pedro
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 8, 17))).isTrue();  // Asunción
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 10, 12))).isTrue(); // Día de la Raza
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 11, 2))).isTrue();  // Todos los Santos
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 11, 16))).isTrue(); // Cartagena
    }

    @Test
    void ordinaryMondaysAreNotHolidays() {
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 10, 19))).isFalse();
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 11, 9))).isFalse();
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 11, 23))).isFalse();
    }

    @Test
    void fixedHolidaysCanFallOnAnyWeekday() {
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2026, 7, 20))).isTrue();  // lunes
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2026, 8, 7))).isTrue();   // viernes
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2026, 12, 8))).isTrue();  // martes
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2026, 12, 25))).isTrue(); // viernes
        assertThat(ColombianHolidays.isHolidayMonday(LocalDate.of(2026, 11, 1))).isFalse();
    }

    @Test
    void easterHolidaysAreCalculated() {
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2026, 4, 2))).isTrue(); // Jueves Santo
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2026, 4, 3))).isTrue(); // Viernes Santo
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2027, 3, 25))).isTrue(); // Jueves Santo 2027
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2027, 3, 26))).isTrue(); // Viernes Santo 2027
    }

    @Test
    void nextHolidayMondayIsDynamic() {
        assertThat(ColombianHolidays.nextHolidayMonday(LocalDate.of(2026, 10, 19)))
                .isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(ColombianHolidays.nextHolidayMonday(LocalDate.of(2026, 11, 3)))
                .isEqualTo(LocalDate.of(2026, 11, 16));
    }
}
