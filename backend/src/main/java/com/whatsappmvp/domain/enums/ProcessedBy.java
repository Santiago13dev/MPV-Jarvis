package com.whatsappmvp.domain.enums;

public enum ProcessedBy {
    KEYWORD,  // Regla de keyword
    FAQ,      // FAQ engine
    AI,       // OpenAI fallback
    HUMAN,    // Operador humano
    SYSTEM    // Mensaje del sistema (bienvenida, fuera de horario, etc.)
}
