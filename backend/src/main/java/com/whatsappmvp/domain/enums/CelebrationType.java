package com.whatsappmvp.domain.enums;

/**
 * Motivo de la celebración. NONE = el cliente solo quiere guardar el espacio (sin postre ni decoración).
 * El precio del paquete de celebración lo decide RestaurantSettings; aquí solo se guarda el motivo.
 */
public enum CelebrationType {
    NONE,
    BIRTHDAY,
    BIRTHDAY_MALE,
    BIRTHDAY_FEMALE,
    ANNIVERSARY,
    GRADUATION,
    FIRST_COMMUNION;

    public boolean isCelebration() {
        return this != NONE;
    }
}
