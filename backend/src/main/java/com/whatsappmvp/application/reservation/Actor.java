package com.whatsappmvp.application.reservation;

import com.whatsappmvp.domain.enums.AuditActorType;

/** Quién ejecuta una operación sobre una reserva (para auditoría y autorización). */
public record Actor(String id, AuditActorType type) {

    public static Actor customer(String phone) {
        return new Actor(phone, AuditActorType.CUSTOMER);
    }

    public static Actor admin(String username) {
        return new Actor(username, AuditActorType.ADMIN);
    }

    public static Actor system() {
        return new Actor("system", AuditActorType.SYSTEM);
    }
}
