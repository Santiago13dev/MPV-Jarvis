package com.whatsappmvp.domain.enums;

public enum ConversationStatus {
    AUTO,           // El bot atiende automáticamente
    HUMAN_TAKEOVER, // Un operador tomó control
    WAITING,        // Esperando respuesta del cliente
    CLOSED          // Conversación cerrada
}
