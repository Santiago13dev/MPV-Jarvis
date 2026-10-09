package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.reservation.DateExpressionResolver.Status;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Calendario: "mañana", "este sábado", etc. se resuelven con el reloj del servidor (America/Bogota). */
class DateExpressionResolverTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    /** Lunes 5 de octubre de 2026, 09:19 en Bogotá. */
    private final DateExpressionResolver onMonday = resolverAt("2026-10-05T14:19:00Z");
    /** Sábado 10 de octubre de 2026, 10:00 en Bogotá. */
    private final DateExpressionResolver onSaturday = resolverAt("2026-10-10T15:00:00Z");

    private static DateExpressionResolver resolverAt(String instant) {
        return new DateExpressionResolver(Clock.fixed(Instant.parse(instant), BOGOTA));
    }

    @Test
    void tomorrowFromMondayIsTuesday_aClosedDayTheBackendWillReject() {
        var r = onMonday.resolve("Quiero reservar para mañana para 5");
        assertThat(r.status()).isEqualTo(Status.RESOLVED);
        assertThat(r.date()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void thisSaturdayAndSundayResolveToTheComingWeekend() {
        assertThat(onMonday.resolve("este sábado").date()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(onMonday.resolve("el domingo").date()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(onMonday.resolve("sábado").date()).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void theDayAfterTomorrow() {
        assertThat(onMonday.resolve("pasado mañana").date()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    void nextSaturdayIsStrictlyAfterToday() {
        assertThat(onMonday.resolve("el próximo sábado").date()).isEqualTo(LocalDate.of(2026, 10, 10));
        // Si hoy ya es sábado, "este sábado" es hoy y "el próximo sábado" es el de la semana siguiente
        assertThat(onSaturday.resolve("este sábado").date()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(onSaturday.resolve("el próximo sábado").date()).isEqualTo(LocalDate.of(2026, 10, 17));
    }

    @Test
    void tomorrowFromSaturdayIsSunday() {
        assertThat(onSaturday.resolve("mañana").date()).isEqualTo(LocalDate.of(2026, 10, 11));
    }

    @Test
    void explicitDates() {
        assertThat(onMonday.resolve("el 10 de octubre").date()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(onMonday.resolve("el 11").date()).isEqualTo(LocalDate.of(2026, 10, 11));
    }

    @Test
    void weekendOrSeveralDaysIsAmbiguousSoTheBotAsks() {
        var weekend = onMonday.resolve("este fin de semana");
        assertThat(weekend.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(weekend.note()).isEqualTo("WEEKEND");

        assertThat(onMonday.resolve("el sábado o el domingo").status()).isEqualTo(Status.AMBIGUOUS);
    }

    @Test
    void textWithoutDateAndMorningAsPeriodAreNotDates() {
        assertThat(onMonday.resolve("hola").status()).isEqualTo(Status.NONE);
        assertThat(onMonday.resolve("en la mañana").status()).isEqualTo(Status.NONE);
    }
}
