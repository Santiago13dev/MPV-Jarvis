package com.whatsappmvp.application.reservation;

import com.whatsappmvp.domain.enums.CelebrationType;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationPricingAndStatusTest {

    private final ReservationPricing pricing = new ReservationPricing();
    private final RestaurantSettingsEntity settings = RestaurantSettingsEntity.builder().build(); // normal 0 / VIP 100.000

    // ── Precios: los calcula el backend, nunca el LLM ─────────────────────

    @Test
    void normalWithoutCelebrationIsFree_andDoesNotDependOnPeople() {
        for (int people : new int[]{1, 2, 5, 10}) {
            var q = pricing.quote(ReservationType.NORMAL, people, settings);
            assertThat(q.unitPrice()).isEqualByComparingTo("0");
            assertThat(q.totalPrice()).isEqualByComparingTo("0");
            assertThat(q.perPerson()).isFalse();
        }
    }

    @Test
    void celebrationPackageCoversOneHonoree_andChargesExtraPerAdditional() {
        var one = pricing.quote(ReservationType.NORMAL, 10, CelebrationType.BIRTHDAY_MALE, 1, settings);
        assertThat(one.totalPrice()).isEqualByComparingTo("40000");

        var three = pricing.quote(ReservationType.NORMAL, 10, CelebrationType.ANNIVERSARY, 3, settings);
        assertThat(three.totalPrice()).isEqualByComparingTo("60000"); // 40.000 + 2 × 10.000
    }

    @Test
    void vipCelebrationAddsThePackageToTheSpacePrice() {
        var q = pricing.quote(ReservationType.VIP, 15, CelebrationType.FIRST_COMMUNION, 2, settings);
        assertThat(q.totalPrice()).isEqualByComparingTo("150000"); // 100.000 + 40.000 + 10.000
    }

    @Test
    void vipIsAFlatPriceForTheWholeSpace_notPerPerson() {
        var q = pricing.quote(ReservationType.VIP, 15, settings);
        assertThat(q.totalPrice()).isEqualByComparingTo("100000"); // no 15 × 100.000
        assertThat(q.perPerson()).isFalse();
    }

    // ── Estados ───────────────────────────────────────────────────────────

    @Test
    void happyPath() {
        assertThat(ReservationStatus.PAYMENT_PENDING.canTransitionTo(ReservationStatus.PAYMENT_RECEIPT_SENT)).isTrue();
        assertThat(ReservationStatus.PAYMENT_RECEIPT_SENT.canTransitionTo(ReservationStatus.CONFIRMED)).isTrue();
        assertThat(ReservationStatus.PAYMENT_PENDING.canTransitionTo(ReservationStatus.EXPIRED)).isTrue();
    }

    @Test
    void rejectedReceiptGoesBackToPaymentPending() {
        assertThat(ReservationStatus.PAYMENT_RECEIPT_SENT.canTransitionTo(ReservationStatus.PAYMENT_PENDING)).isTrue();
    }

    @Test
    void terminalStatesNeverComeBack() {
        for (ReservationStatus target : ReservationStatus.values()) {
            assertThat(ReservationStatus.CANCELLED.canTransitionTo(target)).isFalse();
            assertThat(ReservationStatus.EXPIRED.canTransitionTo(target)).isFalse();
        }
        assertThat(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.PAYMENT_PENDING)).isFalse();
        // Una reserva vencida no puede "confirmarse" después
        assertThat(ReservationStatus.EXPIRED.canTransitionTo(ReservationStatus.CONFIRMED)).isFalse();
    }

    @Test
    void onlyActiveStatesConsumeCapacity_andOnlyPendingOnesExpire() {
        assertThat(ReservationStatus.ACTIVE).contains(ReservationStatus.PENDING, ReservationStatus.PAYMENT_PENDING,
                ReservationStatus.PAYMENT_RECEIPT_SENT, ReservationStatus.CONFIRMED);
        assertThat(ReservationStatus.ACTIVE).doesNotContain(ReservationStatus.CANCELLED, ReservationStatus.EXPIRED);
        assertThat(ReservationStatus.HOLDS).containsExactlyInAnyOrder(ReservationStatus.PENDING,
                ReservationStatus.PAYMENT_PENDING);
    }
}
