package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.reservation.TimeExpressionResolver.Period;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Horas en lenguaje natural. Pivote 11: el restaurante abre 11:30, así que "a las 2" es 2:00 PM. */
class TimeExpressionResolverTest {

    private static final int PIVOT = 11;
    private final TimeExpressionResolver resolver = new TimeExpressionResolver();

    private LocalTime time(String text) {
        var r = resolver.resolve(text, PIVOT, false);
        assertThat(r.hasTime()).as("'%s' debería resolver una hora", text).isTrue();
        return r.time();
    }

    @Test
    void smallHoursWithoutMeridiemAreAfternoon() {
        assertThat(time("a las 2")).isEqualTo(LocalTime.of(14, 0));
        assertThat(time("a las dos")).isEqualTo(LocalTime.of(14, 0));
        assertThat(time("a la una")).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    void noonAndHalfHours() {
        assertThat(time("a las 12 y media")).isEqualTo(LocalTime.of(12, 30));
        assertThat(time("11:30")).isEqualTo(LocalTime.of(11, 30));
        assertThat(time("mediodía")).isEqualTo(LocalTime.NOON);
    }

    @Test
    void twentyFourHourAndExplicitMeridiem() {
        assertThat(time("14:00")).isEqualTo(LocalTime.of(14, 0));
        assertThat(time("2pm")).isEqualTo(LocalTime.of(14, 0));
        assertThat(time("a las 3 de la tarde")).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    void timeInsideALongerSentence() {
        assertThat(time("Somos 8 y queremos ir el sábado a la una")).isEqualTo(LocalTime.of(13, 0));
        assertThat(time("5 personas a las 2")).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    void dayPeriodsAreNotExactTimes() {
        var afternoon = resolver.resolve("en la tarde", PIVOT, false);
        assertThat(afternoon.hasTime()).isFalse();
        assertThat(afternoon.period()).isEqualTo(Period.AFTERNOON);

        var afterNoon = resolver.resolve("después del mediodía", PIVOT, false);
        assertThat(afterNoon.hasTime()).isFalse();
        assertThat(afterNoon.period()).isEqualTo(Period.AFTERNOON);
    }

    @Test
    void aBareNumberIsOnlyATimeWhenTheBotJustAskedForIt() {
        assertThat(resolver.resolve("2", PIVOT, false).hasTime()).isFalse();
        assertThat(resolver.resolve("2", PIVOT, true).time()).isEqualTo(LocalTime.of(14, 0));
    }
}
