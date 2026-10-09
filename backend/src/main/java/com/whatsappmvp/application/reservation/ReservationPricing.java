package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.reservation.AvailabilityModels.PriceQuote;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Única fuente de verdad del precio. NORMAL = precio × personas. VIP = precio fijo por el espacio completo.
 * El LLM jamás calcula ni decide el total.
 */
@Component
public class ReservationPricing {

    public PriceQuote quote(ReservationType type, int partySize, RestaurantSettingsEntity s) {
        if (type == ReservationType.VIP) {
            return new PriceQuote(type, partySize, s.getVipPrice(), s.getVipPrice(), false);
        }
        BigDecimal unit = s.getNormalPrice();
        return new PriceQuote(type, partySize, unit, unit.multiply(BigDecimal.valueOf(partySize)), true);
    }
}
