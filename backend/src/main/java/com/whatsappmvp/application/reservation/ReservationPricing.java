package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.reservation.AvailabilityModels.PriceQuote;
import com.whatsappmvp.domain.enums.CelebrationType;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Única fuente de verdad del precio.
 *
 * NORMAL: precio base (0 por defecto = solo guardar el espacio) + paquete de celebración si aplica.
 * VIP: valor fijo por el espacio completo + paquete de celebración si aplica.
 * El paquete cubre 1 homenajeado; cada homenajeado adicional suma extraHonoreePrice.
 * El LLM jamás calcula ni decide el total.
 */
@Component
public class ReservationPricing {

    public PriceQuote quote(ReservationType type, int partySize, RestaurantSettingsEntity s) {
        return quote(type, partySize, null, null, s);
    }

    public PriceQuote quote(ReservationType type, int partySize, CelebrationType celebration,
                            Integer honorees, RestaurantSettingsEntity s) {
        BigDecimal total = type == ReservationType.VIP
                ? safe(s.getVipPrice())
                : safe(s.getNormalPrice());

        if (celebration != null && celebration.isCelebration()) {
            total = total.add(safe(s.getCelebrationPrice()));
            int extra = Math.max(0, (honorees != null ? honorees : 1) - 1);
            total = total.add(safe(s.getExtraHonoreePrice()).multiply(BigDecimal.valueOf(extra)));
        }
        return new PriceQuote(type, partySize, total, total, false);
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
