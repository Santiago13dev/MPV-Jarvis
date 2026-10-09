package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.reservation.AvailabilityModels.*;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import com.whatsappmvp.infrastructure.persistence.entity.SlotCapacityRuleEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.SlotCapacityRuleJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ReservationAvailabilityService — ÚNICA fuente de verdad sobre disponibilidad.
 *
 * Modelo: el restaurante maneja cupos de LLEGADA por franja (no ocupación de mesa por tiempo).
 * reservado(franja) = suma de personas de reservas NORMAL activas en esa franja.
 * VIP = recurso exclusivo (1 reserva activa por fecha + franja).
 *
 * Este servicio es de solo lectura. La atomicidad "verificar + crear" la garantiza ReservationService
 * llamando a {@link #check} dentro de la misma transacción que toma el lock de la franja.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationAvailabilityService {

    /** Máximo de horarios alternativos que se ofrecen al cliente. */
    private static final int MAX_ALTERNATIVES = 3;
    private static final int NEXT_DAYS_TO_OFFER = 2;
    /** UUID que nunca existe: "no excluir ninguna reserva". */
    private static final UUID NO_EXCLUDE = new UUID(0L, 0L);

    private final ReservationJpaRepository reservations;
    private final SlotCapacityRuleJpaRepository capacityRules;
    private final RestaurantSettingsService settingsService;
    private final ReservationCalendarService calendar;
    private final ReservationPricing pricing;

    // ──────────────────────────────────────────────────────────────────────
    // Consulta pública
    // ──────────────────────────────────────────────────────────────────────

    /** Valida una solicitud concreta. {@code excludeId}: reserva propia a ignorar (modificaciones). */
    @Transactional(readOnly = true)
    public RequestCheck check(LocalDate date, LocalTime time, ReservationType type, int partySize, UUID excludeId) {
        RestaurantSettingsEntity s = settingsService.get();
        UUID exclude = excludeId != null ? excludeId : NO_EXCLUDE;
        ReservationType t = type != null ? type : ReservationType.NORMAL;

        PriceQuote price = partySize > 0 ? pricing.quote(t, partySize, s) : null;

        if (partySize <= 0) {
            return fail(Reason.INVALID_PARTY_SIZE, date, time, t, partySize, null, price, List.of(), List.of());
        }

        Reason slotReason = validateSlot(date, time, s);
        if (slotReason != Reason.OK) {
            boolean dateLevel = slotReason == Reason.CLOSED_DAY || slotReason == Reason.PAST_DATE
                    || (slotReason == Reason.BLOCKED && calendar.isDateBlocked(date));
            List<LocalDate> nextDays = dateLevel
                    ? calendar.nextBookableDays(date.isBefore(calendar.today()) ? calendar.today() : date.plusDays(1),
                    NEXT_DAYS_TO_OFFER, s)
                    : List.of();
            List<LocalTime> alts = dateLevel ? List.of()
                    : alternatives(date, time, t, partySize, exclude, s);
            return fail(slotReason, date, time, t, partySize, null, price, alts, nextDays);
        }

        if (t == ReservationType.VIP) {
            if (partySize > s.getVipCapacity()) {
                return fail(Reason.VIP_CAPACITY_EXCEEDED, date, time, t, partySize, vipPool(date, time, exclude),
                        price, List.of(), List.of());
            }
            Pool vip = vipPool(date, time, exclude);
            if (vip.available() < 1) {
                return fail(Reason.VIP_TAKEN, date, time, t, partySize, vip, price,
                        alternatives(date, time, t, partySize, exclude, s), List.of());
            }
            if (Boolean.TRUE.equals(s.getVipConsumesNormalCapacity())) {
                Pool normal = normalPool(date, time, s, exclude);
                if (partySize > normal.available()) {
                    return fail(Reason.INSUFFICIENT_CAPACITY, date, time, t, partySize, normal, price,
                            alternatives(date, time, t, partySize, exclude, s), List.of());
                }
            }
            return new RequestCheck(true, Reason.OK, date, time, t, partySize, vip, price, List.of(), List.of());
        }

        // NORMAL
        if (partySize > s.getMaxPartySize()) {
            return fail(Reason.PARTY_TOO_LARGE, date, time, t, partySize, null, price, List.of(), List.of());
        }
        Pool normal = normalPool(date, time, s, exclude);
        if (partySize > normal.available()) {
            return fail(Reason.INSUFFICIENT_CAPACITY, date, time, t, partySize, normal, price,
                    alternatives(date, time, t, partySize, exclude, s), List.of());
        }
        return new RequestCheck(true, Reason.OK, date, time, t, partySize, normal, price, List.of(), List.of());
    }

    /** Franjas del día en las que cabe la solicitud (cronológico). Vacío si el día no es reservable. */
    @Transactional(readOnly = true)
    public List<LocalTime> findAvailableSlots(LocalDate date, ReservationType type, int partySize, UUID excludeId) {
        RestaurantSettingsEntity s = settingsService.get();
        UUID exclude = excludeId != null ? excludeId : NO_EXCLUDE;
        return slotsThatFit(date, type != null ? type : ReservationType.NORMAL, partySize, exclude, s);
    }

    /** Disponibilidad cruda de una franja (GET /availability). */
    @Transactional(readOnly = true)
    public AvailabilityResult getAvailability(LocalDate date, LocalTime time) {
        RestaurantSettingsEntity s = settingsService.get();
        Reason r = validateSlot(date, time, s);
        if (r != Reason.OK) {
            return new AvailabilityResult(date, time, false, r, null, null);
        }
        return new AvailabilityResult(date, time, true, null,
                normalPool(date, time, s, NO_EXCLUDE), vipPool(date, time, NO_EXCLUDE));
    }

    /** Panel de ocupación de un día (todas las franjas). Lista vacía si el día no opera. */
    @Transactional(readOnly = true)
    public List<SlotAvailability> getDayAvailability(LocalDate date) {
        RestaurantSettingsEntity s = settingsService.get();
        if (!calendar.isOperatingDay(date, s)) return List.of();
        List<SlotCapacityRuleEntity> rules = capacityRules.findAll();
        boolean dateBlocked = calendar.isDateBlocked(date);
        List<SlotAvailability> out = new ArrayList<>();
        for (LocalTime t : calendar.slotTimes(s)) {
            boolean blocked = dateBlocked || calendar.isSlotBlocked(date, t);
            boolean passed = date.isBefore(calendar.today())
                    || (date.equals(calendar.today()) && !t.isAfter(calendar.now()));
            out.add(new SlotAvailability(t, blocked, passed,
                    normalPool(date, t, s, NO_EXCLUDE, rules), vipPool(date, t, NO_EXCLUDE)));
        }
        return out;
    }

    /** Capacidad configurada para una franja (regla más específica gana). */
    @Transactional(readOnly = true)
    public int capacityFor(LocalDate date, LocalTime time) {
        return capacityFor(date, time, settingsService.get(), capacityRules.findAll());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internos
    // ──────────────────────────────────────────────────────────────────────

    private Reason validateSlot(LocalDate date, LocalTime time, RestaurantSettingsEntity s) {
        if (date == null || time == null) return Reason.INVALID_SLOT;
        if (date.isBefore(calendar.today())) return Reason.PAST_DATE;
        if (!calendar.isOperatingDay(date, s)) return Reason.CLOSED_DAY;
        if (calendar.isDateBlocked(date)) return Reason.BLOCKED;
        if (!calendar.slotTimes(s).contains(time)) return Reason.INVALID_SLOT;
        if (calendar.isSlotBlocked(date, time)) return Reason.BLOCKED;
        if (date.equals(calendar.today()) && !time.isAfter(calendar.now())) return Reason.SLOT_PASSED;
        return Reason.OK;
    }

    private Pool normalPool(LocalDate date, LocalTime time, RestaurantSettingsEntity s, UUID exclude) {
        return normalPool(date, time, s, exclude, capacityRules.findAll());
    }

    private Pool normalPool(LocalDate date, LocalTime time, RestaurantSettingsEntity s, UUID exclude,
                            List<SlotCapacityRuleEntity> rules) {
        int capacity = capacityFor(date, time, s, rules);
        long reserved = reservations.sumPartySize(date, time, ReservationType.NORMAL,
                ReservationStatus.ACTIVE, exclude);
        if (Boolean.TRUE.equals(s.getVipConsumesNormalCapacity())) {
            reserved += reservations.sumPartySize(date, time, ReservationType.VIP,
                    ReservationStatus.ACTIVE, exclude);
        }
        int r = (int) Math.min(reserved, Integer.MAX_VALUE);
        return new Pool(capacity, r, Math.max(0, capacity - r));
    }

    /** VIP: capacidad 1 (un solo espacio); reserved = reservas VIP activas en la franja. */
    private Pool vipPool(LocalDate date, LocalTime time, UUID exclude) {
        long reserved = reservations.countReservations(date, time, ReservationType.VIP,
                ReservationStatus.ACTIVE, exclude);
        int r = (int) Math.min(reserved, 1);
        return new Pool(1, r, 1 - r);
    }

    private int capacityFor(LocalDate date, LocalTime time, RestaurantSettingsEntity s,
                            List<SlotCapacityRuleEntity> rules) {
        int best = -1;
        int bestScore = -1;
        for (SlotCapacityRuleEntity r : rules) {
            boolean dayOk = r.getDayOfWeek() == null || r.getDayOfWeek() == date.getDayOfWeek();
            boolean timeOk = r.getSlotTime() == null || r.getSlotTime().equals(time);
            if (!dayOk || !timeOk) continue;
            int score = (r.getDayOfWeek() != null ? 1 : 0) + (r.getSlotTime() != null ? 2 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = r.getCapacity();
            }
        }
        return best >= 0 ? best : s.getDefaultSlotCapacity();
    }

    private List<LocalTime> slotsThatFit(LocalDate date, ReservationType type, int partySize,
                                         UUID exclude, RestaurantSettingsEntity s) {
        List<LocalTime> fits = new ArrayList<>();
        if (partySize <= 0 || !calendar.isDayBookable(date, s)) return fits;
        if (type == ReservationType.VIP && partySize > s.getVipCapacity()) return fits;
        if (type == ReservationType.NORMAL && partySize > s.getMaxPartySize()) return fits;
        List<SlotCapacityRuleEntity> rules = capacityRules.findAll();
        for (LocalTime t : calendar.slotTimes(s)) {
            if (validateSlot(date, t, s) != Reason.OK) continue;
            if (type == ReservationType.VIP) {
                if (vipPool(date, t, exclude).available() < 1) continue;
                if (Boolean.TRUE.equals(s.getVipConsumesNormalCapacity())
                        && normalPool(date, t, s, exclude, rules).available() < partySize) continue;
                fits.add(t);
            } else if (normalPool(date, t, s, exclude, rules).available() >= partySize) {
                fits.add(t);
            }
        }
        return fits;
    }

    /** Hasta 3 franjas más cercanas a la pedida donde sí cabe la solicitud, en orden cronológico. */
    private List<LocalTime> alternatives(LocalDate date, LocalTime requested, ReservationType type,
                                         int partySize, UUID exclude, RestaurantSettingsEntity s) {
        List<LocalTime> fits = slotsThatFit(date, type, partySize, exclude, s);
        if (requested != null) {
            fits.removeIf(t -> t.equals(requested));
            fits.sort(Comparator.comparingLong(t -> Math.abs(t.toSecondOfDay() - requested.toSecondOfDay())));
        }
        List<LocalTime> nearest = new ArrayList<>(fits.subList(0, Math.min(MAX_ALTERNATIVES, fits.size())));
        nearest.sort(Comparator.naturalOrder());
        return nearest;
    }

    private RequestCheck fail(Reason reason, LocalDate date, LocalTime time, ReservationType type, int partySize,
                              Pool pool, PriceQuote price, List<LocalTime> alternatives, List<LocalDate> nextDays) {
        log.info("[RESERVATION] availability check failed reason={} date={} time={} type={} partySize={}",
                reason, date, time, type, partySize);
        return new RequestCheck(false, reason, date, time, type, partySize, pool, price, alternatives, nextDays);
    }
}
