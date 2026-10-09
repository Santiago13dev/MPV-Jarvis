package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Libera los cupos de reservas cuyo hold venció (PENDING / PAYMENT_PENDING → EXPIRED).
 * El tiempo de retención es configurable (restaurant_settings.hold_minutes).
 * Cada reserva se expira en su propia transacción, con re-verificación bajo lock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpirationJob {

    private final ReservationService reservationService;

    @Scheduled(fixedDelayString = "${app.reservations.expiration-interval-ms:60000}", initialDelay = 30_000)
    public void expireOverdueHolds() {
        List<UUID> ids;
        try {
            ids = reservationService.findExpiredHoldIds();
        } catch (Exception e) {
            log.error("[RESERVATION] expiration job could not query holds: {}", e.getMessage());
            return;
        }
        int expired = 0;
        for (UUID id : ids) {
            try {
                if (reservationService.expire(id)) expired++;
            } catch (Exception e) {
                log.error("[RESERVATION] could not expire reservationId={}: {}", id, e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("[RESERVATION] expiration job released {} reservation(s)", expired);
        }
    }
}
