package com.whatsappmvp.application.reservation;

import com.whatsappmvp.domain.exception.BusinessException;
import com.whatsappmvp.domain.exception.NotFoundException;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationBlockEntity;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import com.whatsappmvp.infrastructure.persistence.entity.SlotCapacityRuleEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationBlockJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.RestaurantSettingsJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.SlotCapacityRuleJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Configuración editable del restaurante: precios, VIP, horarios, capacidades y bloqueos. */
@Service
@RequiredArgsConstructor
public class RestaurantSettingsService {

    private final RestaurantSettingsJpaRepository settingsRepository;
    private final SlotCapacityRuleJpaRepository ruleRepository;
    private final ReservationBlockJpaRepository blockRepository;

    /** Campos null = sin cambio (PATCH). */
    public record SettingsUpdate(BigDecimal normalPrice, BigDecimal celebrationPrice,
                                 BigDecimal extraHonoreePrice, BigDecimal vipPrice, Integer vipCapacity,
                                 Set<DayOfWeek> operatingDays, Boolean openOnHolidayMondays,
                                 LocalTime reservationStartTime, LocalTime reservationEndTime,
                                 Integer slotIntervalMinutes, Integer defaultSlotCapacity, Integer maxPartySize,
                                 Integer holdMinutes, Boolean vipConsumesNormalCapacity, String paymentInstructions) {}

    @Transactional
    public RestaurantSettingsEntity get() {
        return settingsRepository.findById(RestaurantSettingsEntity.SINGLETON_ID)
                .orElseGet(() -> settingsRepository.save(RestaurantSettingsEntity.builder().build()));
    }

    @Transactional
    public RestaurantSettingsEntity update(SettingsUpdate u) {
        RestaurantSettingsEntity s = get();
        if (u.normalPrice() != null) {
            requireNonNegative(u.normalPrice(), "El precio normal");
            s.setNormalPrice(u.normalPrice());
        }
        if (u.celebrationPrice() != null) {
            requireNonNegative(u.celebrationPrice(), "El paquete de celebración");
            s.setCelebrationPrice(u.celebrationPrice());
        }
        if (u.extraHonoreePrice() != null) {
            requireNonNegative(u.extraHonoreePrice(), "El valor por homenajeado adicional");
            s.setExtraHonoreePrice(u.extraHonoreePrice());
        }
        if (u.vipPrice() != null) {
            requireNonNegative(u.vipPrice(), "El precio VIP");
            s.setVipPrice(u.vipPrice());
        }
        if (u.vipCapacity() != null) {
            if (u.vipCapacity() < 1) throw new BusinessException("La capacidad VIP debe ser mayor a 0");
            s.setVipCapacity(u.vipCapacity());
        }
        if (u.operatingDays() != null) {
            if (u.operatingDays().isEmpty()) throw new BusinessException("Debe haber al menos un día de operación");
            s.setOperatingDaysSet(u.operatingDays());
        }
        if (u.openOnHolidayMondays() != null) s.setOpenOnHolidayMondays(u.openOnHolidayMondays());
        if (u.reservationStartTime() != null) s.setReservationStartTime(u.reservationStartTime());
        if (u.reservationEndTime() != null) s.setReservationEndTime(u.reservationEndTime());
        if (s.getReservationEndTime().isBefore(s.getReservationStartTime())) {
            throw new BusinessException("La hora final no puede ser anterior a la hora inicial");
        }
        if (u.slotIntervalMinutes() != null) {
            if (u.slotIntervalMinutes() < 5) throw new BusinessException("El intervalo mínimo es de 5 minutos");
            s.setSlotIntervalMinutes(u.slotIntervalMinutes());
        }
        if (u.defaultSlotCapacity() != null) {
            if (u.defaultSlotCapacity() < 0) throw new BusinessException("La capacidad no puede ser negativa");
            s.setDefaultSlotCapacity(u.defaultSlotCapacity());
        }
        if (u.maxPartySize() != null) {
            if (u.maxPartySize() < 0) throw new BusinessException("El tamaño máximo de grupo no puede ser negativo (0 = sin límite)");
            s.setMaxPartySize(u.maxPartySize());
        }
        if (u.holdMinutes() != null) {
            if (u.holdMinutes() < 1) throw new BusinessException("El tiempo de retención debe ser de al menos 1 minuto");
            s.setHoldMinutes(u.holdMinutes());
        }
        if (u.vipConsumesNormalCapacity() != null) s.setVipConsumesNormalCapacity(u.vipConsumesNormalCapacity());
        if (u.paymentInstructions() != null) {
            s.setPaymentInstructions(u.paymentInstructions().isBlank() ? null : u.paymentInstructions().trim());
        }
        return settingsRepository.save(s);
    }

    // ── Reglas de capacidad ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SlotCapacityRuleEntity> listCapacityRules() {
        return ruleRepository.findAll();
    }

    @Transactional
    public SlotCapacityRuleEntity addCapacityRule(DayOfWeek day, LocalTime time, int capacity) {
        if (capacity < 0) throw new BusinessException("La capacidad no puede ser negativa");
        return ruleRepository.save(SlotCapacityRuleEntity.builder()
                .dayOfWeek(day).slotTime(time).capacity(capacity).build());
    }

    @Transactional
    public void deleteCapacityRule(UUID id) {
        if (!ruleRepository.existsById(id)) throw new NotFoundException("Regla no encontrada");
        ruleRepository.deleteById(id);
    }

    // ── Bloqueos ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReservationBlockEntity> listBlocks(LocalDate from) {
        return blockRepository.findByBlockDateGreaterThanEqualOrderByBlockDateAsc(from);
    }

    @Transactional
    public ReservationBlockEntity addBlock(LocalDate date, LocalTime time, String reason) {
        if (date == null) throw new BusinessException("La fecha es obligatoria");
        return blockRepository.save(ReservationBlockEntity.builder()
                .blockDate(date).slotTime(time).reason(reason).build());
    }

    @Transactional
    public void deleteBlock(UUID id) {
        if (!blockRepository.existsById(id)) throw new NotFoundException("Bloqueo no encontrado");
        blockRepository.deleteById(id);
    }

    private void requireNonNegative(BigDecimal v, String what) {
        if (v.signum() < 0) throw new BusinessException(what + " no puede ser negativo");
    }
}
