package com.whatsappmvp.application.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappmvp.application.reservation.ReservationIntentExtractor.Context;
import com.whatsappmvp.application.reservation.ReservationIntentExtractor.Extraction;
import com.whatsappmvp.application.reservation.ReservationIntentExtractor.Intent;
import com.whatsappmvp.domain.enums.ReservationType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reglas locales del extractor (sin LLM): el lenguaje del cliente se convierte en datos estructurados.
 * El extractor solo interpreta; no decide disponibilidad ni precios.
 */
class ReservationIntentExtractorTest {

    private static final Context FREE = new Context(null, 11, false);

    private final ReservationIntentExtractor extractor = new ReservationIntentExtractor(
            new DateExpressionResolver(Clock.fixed(Instant.parse("2026-10-05T14:19:00Z"), ZoneId.of("America/Bogota"))),
            new TimeExpressionResolver(),
            null, // el LLM solo se usa en enrich()
            new ObjectMapper());

    @Test
    void createReservationWithTomorrowAndPartySize_askingOnlyForWhatIsMissing() {
        Extraction x = extractor.extract("Hola quiero reservar para mañana para 5", FREE);
        assertThat(x.intent()).isEqualTo(Intent.CREATE_RESERVATION);
        assertThat(x.partySize()).isEqualTo(5);
        assertThat(x.date().date()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(x.time().hasTime()).isFalse();
    }

    @Test
    void sizeAndDateWithoutTheWordReserve() {
        Extraction x = extractor.extract("Somos 6 para el sábado", FREE);
        assertThat(x.intent()).isEqualTo(Intent.CREATE_RESERVATION);
        assertThat(x.partySize()).isEqualTo(6);
        assertThat(x.date().date()).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void vipIsTypedAndPartySizeIsNotAPrice() {
        Extraction x = extractor.extract("Quiero el VIP para 15 personas el sábado a las 2", FREE);
        assertThat(x.type()).isEqualTo(ReservationType.VIP);
        assertThat(x.partySize()).isEqualTo(15);
        assertThat(x.time().time()).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    void explicitlyNotVipMeansNormal() {
        assertThat(extractor.extract("No quiero VIP, una reserva normal", FREE).type())
                .isEqualTo(ReservationType.NORMAL);
    }

    @Test
    void availabilityQuestion() {
        Extraction x = extractor.extract("¿Tienen disponibilidad para 6 este sábado?", FREE);
        assertThat(x.intent()).isEqualTo(Intent.CHECK_AVAILABILITY);
        assertThat(x.partySize()).isEqualTo(6);
    }

    @Test
    void cancelAndModifyIntents() {
        assertThat(extractor.extract("Quiero cancelar mi reserva", FREE).intent()).isEqualTo(Intent.CANCEL_RESERVATION);
        assertThat(extractor.extract("Quiero cambiar mi reserva", FREE).intent()).isEqualTo(Intent.MODIFY_RESERVATION);
    }

    @Test
    void cancelWithoutRepeatingTheWordReservation() {
        assertThat(extractor.extract("No puedo asistir, quiero cancelar", FREE).intent())
                .isEqualTo(Intent.CANCEL_RESERVATION);
        assertThat(extractor.extract("cancela", FREE).intent()).isEqualTo(Intent.CANCEL_RESERVATION);
        assertThat(extractor.extract("mejor anulemos", FREE).intent()).isEqualTo(Intent.CANCEL_RESERVATION);
    }

    @Test
    void availabilityQuestionsWithoutTheWordCupo() {
        assertThat(extractor.extract("Tienen lugar para el sábado", FREE).wantsAvailability()).isTrue();
        assertThat(extractor.extract("¿Quedan lugares?", FREE).wantsAvailability()).isTrue();
        assertThat(extractor.extract("Lugar para 4 personas", FREE).wantsAvailability()).isTrue();
    }

    @Test
    void changingAFieldMidDraftIsNotATopicSwitch() {
        Context drafting = new Context("PARTY_SIZE", 11, false, true);
        assertThat(extractor.extract("quiero cambiar a 6", drafting).intent()).isEqualTo(Intent.NONE);
        assertThat(extractor.extract("quiero cambiar la reserva a 6", drafting).intent())
                .isEqualTo(Intent.MODIFY_RESERVATION);
        // sin borrador, "cambiar a 6" es la reserva existente la que quiere tocar
        assertThat(extractor.extract("quiero cambiar a 6", FREE).intent()).isEqualTo(Intent.MODIFY_RESERVATION);
        // y "cancelar" sin borrador también cambia de flujo (sin secuestrar la política de cancelación)
        assertThat(extractor.extract("mejor cancela eso", FREE).intent()).isEqualTo(Intent.CANCEL_RESERVATION);
    }

    @Test
    void aQuestionAboutCancellationPolicyIsNotAnAttemptToCancel() {
        // "¿me devuelven el dinero si cancelo?" es una consulta de política → FAQ, no flujo de cancelación
        assertThat(extractor.extract("¿me devuelven el dinero si cancelo?", FREE).intent())
                .isEqualTo(Intent.GENERAL_QUESTION);
        // pero la intención sí es cancelar
        assertThat(extractor.extract("no puedo asistir, quiero cancelar", FREE).intent())
                .isEqualTo(Intent.CANCEL_RESERVATION);
    }

    @Test
    void askingForTheReservationNumberIsNotCreatingOne() {
        assertThat(extractor.extract("tengo reserva a las 12", FREE).intent())
                .isEqualTo(Intent.GET_RESERVATION);
        assertThat(extractor.extract("ya reservé, ¿me pasas el número?", FREE).intent())
                .isEqualTo(Intent.GET_RESERVATION);
    }

    @Test
    void shortAnswersDependOnWhatTheBotAsked() {
        assertThat(extractor.extract("cinco", new Context("PARTY_SIZE", 11, false)).partySize()).isEqualTo(5);
        assertThat(extractor.extract("Kevin Perez", new Context("NAME", 11, false)).customerName())
                .isEqualTo("Kevin Perez");
        assertThat(extractor.extract("a las 12", new Context("TIME", 11, false)).time().time())
                .isEqualTo(LocalTime.NOON);
        // Sin contexto, un número suelto no es nada
        assertThat(extractor.extract("cinco", FREE).partySize()).isNull();
    }

    @Test
    void yesAndNo() {
        assertThat(extractor.extract("sí", new Context("CONFIRM", 11, false)).yes()).isTrue();
        assertThat(extractor.extract("no", new Context("CONFIRM", 11, false)).no()).isTrue();
        // "no, mejor a las 3" trae un dato nuevo: es un cambio, no un rechazo
        Extraction change = extractor.extract("no, mejor a las 3", new Context("CONFIRM", 11, false));
        assertThat(change.no()).isFalse();
        assertThat(change.time().time()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    void nameInsideASentenceStopsAtTheNextWord() {
        Extraction x = extractor.extract("mi nombre es Ana Gomez y somos 4", FREE);
        assertThat(x.customerName()).isEqualTo("Ana Gomez");
        assertThat(x.partySize()).isEqualTo(4);
    }

    @Test
    void receiptPhoneCanDifferFromTheWhatsappNumber() {
        Extraction x = extractor.extract("El comprobante lo quiero enviar al 3001234567", FREE);
        assertThat(x.receiptPhone()).isEqualTo("3001234567");
    }

    @Test
    void modifyingFromOneSlotToAnotherTakesTheDestination() {
        Extraction x = extractor.extract("Quiero pasarla del sábado a las 2 al domingo a las 3",
                new Context(null, 11, true));
        assertThat(x.date().date()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(x.time().time()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    void generalQuestionsAreNotReservationData() {
        Extraction x = extractor.extract("¿Tienen parqueadero?", FREE);
        assertThat(x.hasBookingData()).isFalse();
        assertThat(x.isEmpty()).isTrue();
    }

    @Test
    void informationRequestAboutReservationsGoesToFaqNotToTheBookingFlow() {
        // Sin signo de pregunta y sin verbo claro de reservar: es información, no una reserva
        assertThat(extractor.extract("Quiero información de las reservas", FREE).intent())
                .isEqualTo(Intent.GENERAL_QUESTION);
        assertThat(extractor.extract("precio del VIP", FREE).intent()).isEqualTo(Intent.GENERAL_QUESTION);
        assertThat(extractor.extract("¿Cómo funciona la reserva?", FREE).intent()).isEqualTo(Intent.GENERAL_QUESTION);
    }

    @Test
    void clearBookingVerbStillStartsAReservation() {
        assertThat(extractor.extract("Quiero reservar", FREE).intent()).isEqualTo(Intent.CREATE_RESERVATION);
        assertThat(extractor.extract("Quiero el VIP", FREE).intent()).isEqualTo(Intent.CREATE_RESERVATION);
    }

    @Test
    void questionThatMentionsDataIsStillAGeneralQuestion() {
        // El servicio conversacional usa esta intención para NO aplicar el dato al borrador
        Extraction price = extractor.extract("¿Cuánto cuesta para 5 personas?", FREE);
        assertThat(price.intent()).isEqualTo(Intent.GENERAL_QUESTION);
        assertThat(price.partySize()).isEqualTo(5);

        assertThat(extractor.extract("¿Abren el domingo?", FREE).intent()).isEqualTo(Intent.GENERAL_QUESTION);
    }
}
