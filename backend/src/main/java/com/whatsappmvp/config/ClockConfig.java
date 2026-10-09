package com.whatsappmvp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Reloj oficial del sistema de reservas. Toda lógica de calendario ("hoy", "mañana",
 * vencimientos, franjas pasadas) usa este reloj — nunca la fecha del modelo de IA ni UTC sin conversión.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.reservations.timezone:America/Bogota}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
