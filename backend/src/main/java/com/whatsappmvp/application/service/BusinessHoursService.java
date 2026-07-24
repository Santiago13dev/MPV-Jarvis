package com.whatsappmvp.application.service;

import com.whatsappmvp.infrastructure.persistence.entity.BusinessConfigEntity;
import com.whatsappmvp.infrastructure.persistence.entity.BusinessHoursEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.BusinessConfigJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.BusinessHoursJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Determina si el negocio está dentro del horario laboral.
 * Soporta horarios por día de la semana con toggle de activo/inactivo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessHoursService {

    private final BusinessConfigJpaRepository configRepository;
    private final BusinessHoursJpaRepository hoursRepository;

    /**
     * Verifica si el momento actual está dentro del horario laboral configurado.
     * Si no hay horarios configurados, asume que siempre está abierto.
     */
    @Transactional(readOnly = true)
    public boolean isWithinBusinessHours() {
        // TODO: Restaurar cuando se configuren horarios reales en DB
        // return isWithinBusinessHours(LocalDateTime.now());
        log.debug("[BusinessHours] Temporarily always open for testing");
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isWithinBusinessHours(LocalDateTime dateTime) {
        BusinessConfigEntity config = configRepository.findFirstByOrderByCreatedAtAsc()
                .orElse(null);

        if (config == null) {
            log.warn("[BusinessHours] No business config found — assuming open");
            return true;
        }

        List<BusinessHoursEntity> hours = hoursRepository
                .findByBusinessConfigIdOrderByDayOfWeekAsc(config.getId());

        if (hours.isEmpty()) {
            log.warn("[BusinessHours] No business hours configured — assuming open");
            return true;
        }

        // DayOfWeek en Java: 1=Lunes...7=Domingo
        // En la DB: 0=Domingo, 1=Lunes...6=Sábado
        int javaDow = dateTime.getDayOfWeek().getValue(); // 1-7
        int dbDow = javaDow % 7; // Convierte a 0=Dom, 1=Lun...6=Sab

        LocalTime currentTime = dateTime.toLocalTime();

        return hours.stream()
                .filter(h -> h.getDayOfWeek() == dbDow && Boolean.TRUE.equals(h.getIsActive()))
                .anyMatch(h -> {
                    // Caso especial: openTime == closeTime == 00:00 significa cerrado
                    if (h.getOpenTime().equals(LocalTime.MIDNIGHT) &&
                        h.getCloseTime().equals(LocalTime.MIDNIGHT)) {
                        return false;
                    }
                    return !currentTime.isBefore(h.getOpenTime()) &&
                           !currentTime.isAfter(h.getCloseTime());
                });
    }

    @Transactional(readOnly = true)
    public String getOffHoursMessage() {
        return configRepository.findFirstByOrderByCreatedAtAsc()
                .map(BusinessConfigEntity::getOffHoursMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .orElse("⏰ Estamos fuera de horario. Te responderemos pronto.");
    }

    @Transactional(readOnly = true)
    public String getWelcomeMessage() {
        return configRepository.findFirstByOrderByCreatedAtAsc()
                .map(BusinessConfigEntity::getWelcomeMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .orElse("¡Hola! 👋 ¿En qué puedo ayudarte?");
    }
}
