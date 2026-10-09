package com.whatsappmvp.integration;

import com.whatsappmvp.application.reservation.ReservationConversationService;
import com.whatsappmvp.application.reservation.ReservationConversationService.Kind;
import com.whatsappmvp.application.reservation.ReservationConversationService.Result;
import com.whatsappmvp.domain.enums.ConversationStatus;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.persistence.entity.ContactEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ConversationEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ContactJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flujo conversacional completo (lenguaje natural → borrador en BD → reserva real) contra PostgreSQL.
 * Verifica que el bot solo confirma cuando la reserva ya existe en la base de datos.
 */
@SpringBootTest
@EnabledIf("com.whatsappmvp.integration.PostgresTestBase#dockerAvailable")
class ReservationConversationIntegrationTest extends PostgresTestBase {

    private static final String[] MONTHS = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
    private static final AtomicLong PHONES = new AtomicLong(3_200_000_000L);

    @Autowired ReservationConversationService conversationService;
    @Autowired ContactJpaRepository contacts;
    @Autowired ConversationJpaRepository conversations;
    @Autowired ReservationJpaRepository reservations;

    private ConversationEntity newConversation(String phone) {
        ContactEntity contact = contacts.save(ContactEntity.builder()
                .phone(phone).displayName("Cliente").lastSeen(LocalDateTime.now()).build());
        return conversations.save(ConversationEntity.builder()
                .contact(contact).status(ConversationStatus.AUTO).lastMessageAt(LocalDateTime.now()).build());
    }

    private Result say(ConversationEntity conv, String phone, String text, String waId) {
        return conversationService.handleText(conv, phone, text, waId);
    }

    @Test
    void fullConversationCreatesARealReservationOnlyAfterTheCustomerConfirms() {
        String phone = String.valueOf(PHONES.incrementAndGet());
        ConversationEntity conv = newConversation(phone);
        LocalDate saturday = LocalDate.now(ZoneId.of("America/Bogota")).plusDays(1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).plusWeeks(40);
        String dateText = "el " + saturday.getDayOfMonth() + " de " + MONTHS[saturday.getMonthValue() - 1];

        // 1) Solo dice la cantidad: el bot no vuelve a preguntarla, pide el día
        Result r1 = say(conv, phone, "Hola quiero reservar para 5", "wa-1");
        assertThat(r1.kind()).isEqualTo(Kind.REPLY);
        assertThat(r1.text()).contains("qué día");
        assertThat(reservations.findUpcomingByPhone(phone, ReservationStatus.ACTIVE, LocalDate.now())).isEmpty();

        // 2) Da la fecha: el bot ofrece horarios REALES del backend
        Result r2 = say(conv, phone, dateText, "wa-2");
        assertThat(r2.text()).contains("tengo disponibilidad").contains("11:30 AM");

        // 3) Elige hora ("a las 12"): pide el nombre
        Result r3 = say(conv, phone, "a las 12", "wa-3");
        assertThat(r3.text()).containsIgnoringCase("nombre");

        // 4) Da el nombre: resumen con el precio calculado por el backend, todavía SIN reserva
        Result r4 = say(conv, phone, "Kevin Perez", "wa-4");
        assertThat(r4.text()).contains("12:00 PM").contains("5 personas").contains("$200.000").contains("¿Deseas continuar?");
        assertThat(reservations.findUpcomingByPhone(phone, ReservationStatus.ACTIVE, LocalDate.now())).isEmpty();

        // 5) Confirma: ahora sí existe en PostgreSQL y el mensaje trae el número de reserva
        Result r5 = say(conv, phone, "sí", "wa-5");
        assertThat(r5.text()).contains("Registré tu reserva").contains("#");

        List<ReservationEntity> saved = reservations.findUpcomingByPhone(phone, ReservationStatus.ACTIVE, LocalDate.now());
        assertThat(saved).hasSize(1);
        ReservationEntity r = saved.get(0);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(r.getReservationType()).isEqualTo(ReservationType.NORMAL);
        assertThat(r.getPartySize()).isEqualTo(5);
        assertThat(r.getSlotDate()).isEqualTo(saturday);
        assertThat(r.getCustomerName()).isEqualTo("Kevin Perez");
        assertThat(r.getTotalPrice()).isEqualByComparingTo("200000");
        assertThat(r5.text()).contains("#" + r.getReservationNumber());
    }

    @Test
    void aClosedDayIsExplainedInHumanLanguage() {
        String phone = String.valueOf(PHONES.incrementAndGet());
        ConversationEntity conv = newConversation(phone);

        Result r = say(conv, phone, "quiero reservar el lunes para 4", "wa-closed");
        assertThat(r.kind()).isEqualTo(Kind.REPLY);
        assertThat(r.text()).contains("no tenemos servicio").contains("sábados y domingos");
        assertThat(reservations.findUpcomingByPhone(phone, ReservationStatus.ACTIVE, LocalDate.now())).isEmpty();
    }

    @Test
    void aGeneralQuestionIsNotHandledByTheReservationEngine() {
        String phone = String.valueOf(PHONES.incrementAndGet());
        ConversationEntity conv = newConversation(phone);
        assertThat(say(conv, phone, "¿Tienen parqueadero?", "wa-q").kind()).isEqualTo(Kind.NOT_HANDLED);
    }

    @Test
    void aQuestionInTheMiddleOfABookingKeepsTheDraft() {
        String phone = String.valueOf(PHONES.incrementAndGet());
        ConversationEntity conv = newConversation(phone);
        say(conv, phone, "quiero reservar para 4", "wa-m1");

        Result q = say(conv, phone, "¿Tienen parqueadero?", "wa-m2");
        assertThat(q.kind()).isEqualTo(Kind.QUESTION_WITH_REMINDER);
        assertThat(q.text()).contains("¿Seguimos con tu reserva?");
        assertThat(conversationService.hasActiveDraft(conv)).isTrue();
    }

    @Test
    void cancellingAnExistingReservationAsksForConfirmationAndReleasesIt() {
        String phone = String.valueOf(PHONES.incrementAndGet());
        ConversationEntity conv = newConversation(phone);
        LocalDate saturday = LocalDate.now(ZoneId.of("America/Bogota")).plusDays(1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).plusWeeks(41);
        String dateText = "el " + saturday.getDayOfMonth() + " de " + MONTHS[saturday.getMonthValue() - 1];

        say(conv, phone, "Quiero reservar para 3 personas " + dateText + " a las 12 a nombre de Laura Gomez", "wa-c1");
        Result confirm = say(conv, phone, "sí", "wa-c2");
        assertThat(confirm.text()).contains("Registré tu reserva");

        Result ask = say(conv, phone, "Quiero cancelar mi reserva", "wa-c3");
        assertThat(ask.text()).contains("¿Confirmas que quieres cancelar");

        Result done = say(conv, phone, "sí", "wa-c4");
        assertThat(done.text()).contains("cancelé tu reserva");
        assertThat(reservations.findUpcomingByPhone(phone, ReservationStatus.ACTIVE, LocalDate.now())).isEmpty();
    }
}
