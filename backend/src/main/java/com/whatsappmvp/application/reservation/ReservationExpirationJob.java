package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.service.ReservationService;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationDraftJpaRepository;
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
 * También purga los borradores de conversación vencidos (ese chat ya no vuelve a escribir
 * y nadie los borra: sin esto la tabla crece sin límite).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpirationJob {

    private final ReservationService reservationService;
    private final ReservationDraftJpaRepository drafts;
    private final ReservationCalendarService calendar;

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

    /** Borra borradores con más de {@value ReservationConversationService#DRAFT_TTL_MINUTES} min de inactividad. */
    @Scheduled(fixedDelayString = "${app.reservations.expiration-interval-ms:60000}", initialDelay = 45_000)
    public void purgeExpiredDrafts() {
        try {
            int removed = drafts.deleteByUpdatedAtBefore(
                    calendar.nowDateTime().minusMinutes(ReservationConversationService.DRAFT_TTL_MINUTES));
            if (removed > 0) {
                log.info("[RESERVATION] purge removed {} expired draft(s)", removed);
            }
        } catch (Exception e) {
            log.error("[RESERVATION] draft purge failed: {}", e.getMessage());
        }
    }
}
