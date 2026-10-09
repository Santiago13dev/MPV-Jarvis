package com.whatsappmvp.integration;

import com.whatsappmvp.application.reservation.Actor;
import com.whatsappmvp.application.reservation.AvailabilityModels.Reason;
import com.whatsappmvp.application.reservation.ReservationAvailabilityService;
import com.whatsappmvp.application.service.ReservationService;
import com.whatsappmvp.application.service.ReservationService.CreateCommand;
import com.whatsappmvp.application.service.ReservationService.ModifyCommand;
import com.whatsappmvp.application.service.ReservationService.Outcome;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.domain.exception.BusinessException;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationReceiptJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas transaccionales del ReservationService contra PostgreSQL real.
 *
 * Usa la configuración por defecto del restaurante (V9): sábado y domingo, franjas cada 30 min de 11:30 a 13:00,
 * 50 cupos por franja, VIP de hasta 20 personas, máximo 40 por reserva normal.
 * Cada test trabaja en su propia semana para que los cupos de un test no afecten a otro.
 */
@SpringBootTest
@EnabledIf("com.whatsappmvp.integration.PostgresTestBase#dockerAvailable")
class ReservationServiceIntegrationTest extends PostgresTestBase {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final AtomicInteger WEEKS = new AtomicInteger(1);
    private static final AtomicLong PHONES = new AtomicLong(3_100_000_000L);

    private static final LocalTime T1130 = LocalTime.of(11, 30);
    private static final LocalTime T1200 = LocalTime.of(12, 0);
    private static final LocalTime T1230 = LocalTime.of(12, 30);
    private static final LocalTime T1300 = LocalTime.of(13, 0);

    @Autowired ReservationService service;
    @Autowired ReservationAvailabilityService availability;
    @Autowired ReservationJpaRepository reservations;
    @Autowired ReservationReceiptJpaRepository receipts;

    private LocalDate saturday;
    private LocalDate sunday;

    @BeforeEach
    void freshWeek() {
        saturday = LocalDate.now(BOGOTA).plusDays(1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                .plusWeeks(WEEKS.getAndIncrement());
        sunday = saturday.plusDays(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Outcome create(ReservationType type, LocalDate date, LocalTime time, int party) {
        return create(type, date, time, party, UUID.randomUUID().toString());
    }

    private Outcome create(ReservationType type, LocalDate date, LocalTime time, int party, String key) {
        return service.create(new CreateCommand("Cliente Test", String.valueOf(PHONES.incrementAndGet()), null,
                type, date, time, party, null, null, key, ReservationStatus.PAYMENT_PENDING, Actor.admin("test")));
    }

    private int reservedNormal(LocalDate date, LocalTime time) {
        return availability.getAvailability(date, time).normal().reserved();
    }

    // ── Precio ────────────────────────────────────────────────────────────

    @Test
    void normalPriceIsCalculatedByTheBackend() {
        Outcome o = create(ReservationType.NORMAL, saturday, T1200, 5);
        assertThat(o.ok()).isTrue();
        assertThat(o.reservation().getUnitPrice()).isEqualByComparingTo("40000");
        assertThat(o.reservation().getTotalPrice()).isEqualByComparingTo("200000");
        assertThat(o.reservation().getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(o.reservation().getReservationNumber()).isNotNull();
        assertThat(o.reservation().getHoldExpiresAt()).isNotNull();
    }

    // ── Capacidad ─────────────────────────────────────────────────────────

    @Test
    void exactlyTheMaximumCapacityIsAllowed_oneMoreIsRejectedWithRealAlternatives() {
        assertThat(create(ReservationType.NORMAL, saturday, T1200, 30).ok()).isTrue();
        assertThat(create(ReservationType.NORMAL, saturday, T1200, 20).ok()).isTrue(); // 50 de 50
        assertThat(availability.getAvailability(saturday, T1200).normal().available()).isZero();

        Outcome over = create(ReservationType.NORMAL, saturday, T1200, 1);
        assertThat(over.ok()).isFalse();
        assertThat(over.check().reason()).isEqualTo(Reason.INSUFFICIENT_CAPACITY);
        assertThat(over.check().alternatives()).isNotEmpty().doesNotContain(T1200);
        assertThat(reservedNormal(saturday, T1200)).isEqualTo(50); // no se creó nada
    }

    @Test
    void insufficientCapacityOffersTheClosestSlotsWithRoom() {
        create(ReservationType.NORMAL, saturday, T1200, 30);
        create(ReservationType.NORMAL, saturday, T1200, 16); // quedan 4

        Outcome eight = create(ReservationType.NORMAL, saturday, T1200, 8);
        assertThat(eight.ok()).isFalse();
        assertThat(eight.check().alternatives()).contains(T1130, T1230);
    }

    @Test
    void invalidPartySizesAreRejected() {
        assertThat(create(ReservationType.NORMAL, saturday, T1200, 0).check().reason())
                .isEqualTo(Reason.INVALID_PARTY_SIZE);
        assertThat(create(ReservationType.NORMAL, saturday, T1200, -3).check().reason())
                .isEqualTo(Reason.INVALID_PARTY_SIZE);
        assertThat(create(ReservationType.NORMAL, saturday, T1200, 41).check().reason())
                .isEqualTo(Reason.PARTY_TOO_LARGE);
    }

    // ── Calendario y horarios ─────────────────────────────────────────────

    @Test
    void closedDaysAreRejectedAndTheNextOpenDaysAreOffered() {
        LocalDate monday = saturday.plusDays(2);
        Outcome o = create(ReservationType.NORMAL, monday, T1200, 4);
        assertThat(o.ok()).isFalse();
        assertThat(o.check().reason()).isEqualTo(Reason.CLOSED_DAY);
        assertThat(o.check().nextOperatingDays()).isNotEmpty();
        assertThat(o.check().nextOperatingDays()).allMatch(d ->
                d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY);

        assertThat(create(ReservationType.NORMAL, saturday.minusDays(1), T1200, 4).check().reason())
                .isEqualTo(Reason.CLOSED_DAY); // viernes
    }

    @Test
    void slotsOutsideTheConfiguredHoursAreRejected() {
        assertThat(create(ReservationType.NORMAL, saturday, LocalTime.of(11, 0), 4).check().reason())
                .isEqualTo(Reason.INVALID_SLOT); // antes de la apertura (11:30)
        assertThat(create(ReservationType.NORMAL, saturday, LocalTime.of(14, 0), 4).check().reason())
                .isEqualTo(Reason.INVALID_SLOT); // después del cierre configurado
        assertThat(create(ReservationType.NORMAL, saturday, T1130, 4).ok()).isTrue(); // 11:30 es válido
    }

    // ── VIP ───────────────────────────────────────────────────────────────

    @Test
    void vipIsFlatPriced_exclusivePerSlot_andDoesNotConsumeNormalCapacity() {
        Outcome vip = create(ReservationType.VIP, saturday, T1230, 15);
        assertThat(vip.ok()).isTrue();
        assertThat(vip.reservation().getTotalPrice()).isEqualByComparingTo("100000"); // no 15 × 100.000

        Outcome second = create(ReservationType.VIP, saturday, T1230, 5);
        assertThat(second.ok()).isFalse();
        assertThat(second.check().reason()).isEqualTo(Reason.VIP_TAKEN);
        assertThat(second.check().alternatives()).isNotEmpty();

        // Otra franja sí está libre
        assertThat(create(ReservationType.VIP, saturday, T1300, 5).ok()).isTrue();

        // El VIP no gasta cupos de la zona normal
        assertThat(create(ReservationType.NORMAL, saturday, T1230, 40).ok()).isTrue();
        assertThat(create(ReservationType.NORMAL, saturday, T1230, 10).ok()).isTrue();
    }

    @Test
    void vipOverTwentyPeopleIsRejected() {
        Outcome o = create(ReservationType.VIP, saturday, T1130, 21);
        assertThat(o.ok()).isFalse();
        assertThat(o.check().reason()).isEqualTo(Reason.VIP_CAPACITY_EXCEEDED);
        assertThat(create(ReservationType.VIP, saturday, T1130, 20).ok()).isTrue(); // exactamente 20
    }

    // ── Concurrencia ──────────────────────────────────────────────────────

    @Test
    void concurrentReservationsNeverExceedCapacity() throws Exception {
        int threads = 20; // 20 × 5 personas = 100 de demanda para 50 cupos
        List<Outcome> results = runConcurrently(threads, n ->
                create(ReservationType.NORMAL, saturday, T1130, 5, "conc-" + saturday + "-" + n));

        long accepted = results.stream().filter(Outcome::ok).count();
        assertThat(accepted).isEqualTo(10);
        assertThat(reservedNormal(saturday, T1130)).isEqualTo(50);
        assertThat(availability.getAvailability(saturday, T1130).normal().available()).isZero();
    }

    @Test
    void concurrentVipAttemptsForTheSameSlotProduceExactlyOneReservation() throws Exception {
        List<Outcome> results = runConcurrently(8, n ->
                create(ReservationType.VIP, saturday, T1300, 10, "vip-" + saturday + "-" + n));

        assertThat(results.stream().filter(Outcome::ok).count()).isEqualTo(1);
        assertThat(results.stream().filter(o -> !o.ok())
                .allMatch(o -> o.check().reason() == Reason.VIP_TAKEN)).isTrue();
        assertThat(availability.getAvailability(saturday, T1300).vip().available()).isZero();
    }

    // ── Idempotencia ──────────────────────────────────────────────────────

    @Test
    void theSameRequestTwiceCreatesASingleReservation() {
        String key = "wa:" + UUID.randomUUID();
        Outcome first = create(ReservationType.NORMAL, saturday, T1200, 6, key);
        Outcome again = create(ReservationType.NORMAL, saturday, T1200, 6, key);

        assertThat(first.ok()).isTrue();
        assertThat(again.duplicate()).isTrue();
        assertThat(again.reservation().getId()).isEqualTo(first.reservation().getId());
        assertThat(reservedNormal(saturday, T1200)).isEqualTo(6); // no 12
    }

    @Test
    void theSameRequestFiredConcurrentlyCreatesASingleReservation() throws Exception {
        String key = "dup-" + UUID.randomUUID();
        List<Outcome> results = runConcurrently(6, n -> create(ReservationType.NORMAL, saturday, T1200, 4, key));

        assertThat(results.stream().map(o -> o.reservation().getId()).distinct()).hasSize(1);
        assertThat(reservedNormal(saturday, T1200)).isEqualTo(4);
    }

    @Test
    void theSameReceiptMessageIsRegisteredOnce() {
        UUID id = create(ReservationType.NORMAL, saturday, T1200, 2).reservation().getId();
        service.registerReceipt(id, "receipts/x.jpg", "image/jpeg", "wamid-dup", Actor.customer("1"));
        service.registerReceipt(id, "receipts/x.jpg", "image/jpeg", "wamid-dup", Actor.customer("1"));
        assertThat(receipts.findByReservationIdOrderByUploadedAtDesc(id)).hasSize(1);
    }

    // ── Cancelar ──────────────────────────────────────────────────────────

    @Test
    void cancellingReleasesCapacityKeepsTheRecordAndIsIdempotent() {
        Outcome o = create(ReservationType.NORMAL, saturday, T1200, 40);
        UUID id = o.reservation().getId();
        String phone = o.reservation().getPhoneNumber();
        assertThat(reservedNormal(saturday, T1200)).isEqualTo(40);

        ReservationEntity cancelled = service.cancel(id, Actor.customer(phone), "ya no puedo", phone);
        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancelledBy()).isEqualTo(phone);
        assertThat(cancelled.getCancelReason()).isEqualTo("ya no puedo");
        assertThat(reservedNormal(saturday, T1200)).isZero();
        assertThat(reservations.findById(id)).isPresent(); // no se borra físicamente

        assertThat(service.cancel(id, Actor.customer(phone), null, phone).getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void aCustomerCannotCancelSomeoneElsesReservation() {
        UUID id = create(ReservationType.NORMAL, saturday, T1200, 3).reservation().getId();
        assertThatThrownBy(() -> service.cancel(id, Actor.customer("999"), null, "999"))
                .isInstanceOf(BusinessException.class);
        assertThat(service.getById(id).getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
    }

    // ── Modificar ─────────────────────────────────────────────────────────

    @Test
    void modifyingMovesTheSlotRecalculatesThePriceAndFreesTheOldSlot() {
        Outcome o = create(ReservationType.NORMAL, saturday, T1130, 4);
        ReservationEntity r = o.reservation();
        assertThat(r.getTotalPrice()).isEqualByComparingTo("160000");

        Outcome moved = service.modify(r.getId(), new ModifyCommand(sunday, T1200, 6, null,
                r.getPhoneNumber(), Actor.customer(r.getPhoneNumber())));

        assertThat(moved.ok()).isTrue();
        assertThat(moved.reservation().getSlotDate()).isEqualTo(sunday);
        assertThat(moved.reservation().getSlotTime()).isEqualTo(T1200);
        assertThat(moved.reservation().getTotalPrice()).isEqualByComparingTo("240000");
        assertThat(moved.priceDelta()).isEqualByComparingTo("80000");
        assertThat(reservedNormal(saturday, T1130)).isZero();
        assertThat(reservedNormal(sunday, T1200)).isEqualTo(6);
    }

    @Test
    void modifyingIntoAFullSlotIsRejectedAndNothingChanges() {
        create(ReservationType.NORMAL, sunday, T1300, 25);
        create(ReservationType.NORMAL, sunday, T1300, 25);
        ReservationEntity r = create(ReservationType.NORMAL, saturday, T1130, 4).reservation();

        Outcome o = service.modify(r.getId(), new ModifyCommand(sunday, T1300, null, null,
                r.getPhoneNumber(), Actor.customer(r.getPhoneNumber())));

        assertThat(o.ok()).isFalse();
        assertThat(o.check().reason()).isEqualTo(Reason.INSUFFICIENT_CAPACITY);
        ReservationEntity unchanged = service.getById(r.getId());
        assertThat(unchanged.getSlotDate()).isEqualTo(saturday);
        assertThat(unchanged.getSlotTime()).isEqualTo(T1130);
        assertThat(reservedNormal(sunday, T1300)).isEqualTo(50);
    }

    @Test
    void modifyingToTheSameValuesChangesNothing() {
        ReservationEntity r = create(ReservationType.NORMAL, saturday, T1200, 4).reservation();
        Outcome o = service.modify(r.getId(), new ModifyCommand(saturday, T1200, 4, null,
                r.getPhoneNumber(), Actor.customer(r.getPhoneNumber())));
        assertThat(o.unchanged()).isTrue();
    }

    // ── Estados / pago ────────────────────────────────────────────────────

    @Test
    void paymentLifecycleFromPendingToConfirmedToCancelled() {
        UUID id = create(ReservationType.NORMAL, saturday, T1200, 5).reservation().getId();
        assertThat(service.getById(id).getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);

        ReservationEntity sent = service.registerReceipt(id, "receipts/a.jpg", "image/jpeg", "wamid-1",
                Actor.customer("1"));
        assertThat(sent.getStatus()).isEqualTo(ReservationStatus.PAYMENT_RECEIPT_SENT);
        assertThat(sent.getHoldExpiresAt()).isNull(); // mientras el asesor revisa, no vence

        ReservationEntity rejected = service.rejectReceipt(id, Actor.admin("asesor"), "borroso");
        assertThat(rejected.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(rejected.getHoldExpiresAt()).isNotNull(); // hold nuevo para reenviar

        service.registerReceipt(id, "receipts/b.jpg", "image/jpeg", "wamid-2", Actor.customer("1"));
        ReservationEntity confirmed = service.confirm(id, Actor.admin("asesor"));
        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedBy()).isEqualTo("asesor");
        assertThat(service.confirm(id, Actor.admin("asesor")).getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED); // idempotente

        assertThat(service.cancel(id, Actor.admin("asesor"), "cliente avisó", null).getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThatThrownBy(() -> service.confirm(id, Actor.admin("asesor")))
                .isInstanceOf(BusinessException.class); // una cancelada no se confirma
    }

    @Test
    void receiptsAreOnlyAcceptedWhileThePaymentIsOpen() {
        UUID id = create(ReservationType.NORMAL, saturday, T1200, 2).reservation().getId();
        service.cancel(id, Actor.admin("asesor"), null, null);
        assertThatThrownBy(() -> service.registerReceipt(id, "receipts/c.jpg", "image/jpeg", "wamid-3",
                Actor.customer("1"))).isInstanceOf(BusinessException.class);
    }

    // ── Expiración ────────────────────────────────────────────────────────

    @Test
    void expiredHoldsAreReleasedAndTheCapacityComesBack() {
        UUID id = create(ReservationType.NORMAL, saturday, T1200, 40).reservation().getId();
        assertThat(service.expire(id)).isFalse(); // aún vigente

        ReservationEntity e = reservations.findById(id).orElseThrow();
        e.setHoldExpiresAt(LocalDateTime.now().minusDays(1));
        reservations.saveAndFlush(e);

        assertThat(service.findExpiredHoldIds()).contains(id);
        assertThat(service.expire(id)).isTrue();
        assertThat(service.getById(id).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(service.expire(id)).isFalse();
        assertThat(reservedNormal(saturday, T1200)).isZero();
        assertThat(create(ReservationType.NORMAL, saturday, T1200, 40).ok()).isTrue();
    }

    @Test
    void aReservationWithAReceiptInReviewDoesNotExpire() {
        UUID id = create(ReservationType.NORMAL, saturday, T1200, 3).reservation().getId();
        service.registerReceipt(id, "receipts/d.jpg", "image/jpeg", "wamid-4", Actor.customer("1"));
        assertThat(service.findExpiredHoldIds()).doesNotContain(id);
        assertThat(service.expire(id)).isFalse();
        assertThat(service.getById(id).getStatus()).isEqualTo(ReservationStatus.PAYMENT_RECEIPT_SENT);
    }

    // ── util de concurrencia ──────────────────────────────────────────────

    private interface Task {
        Outcome run(int n);
    }

    /** Lanza N hilos que arrancan a la vez (barrera) y devuelve todos los resultados. */
    private List<Outcome> runConcurrently(int threads, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.run(n);
            }));
        }
        ready.await();
        go.countDown();
        List<Outcome> results = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
