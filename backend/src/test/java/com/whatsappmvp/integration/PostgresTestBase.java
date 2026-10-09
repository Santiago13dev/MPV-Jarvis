package com.whatsappmvp.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de los tests de integración: PostgreSQL 15 real (como en producción) con las migraciones Flyway V1..V9.
 * Se usa PostgreSQL y no H2 porque el motor depende de pg_advisory_xact_lock, índices únicos parciales
 * y SELECT ... FOR UPDATE.
 *
 * Un solo contenedor para toda la ejecución. Si no hay Docker, los tests que extienden esta clase se omiten
 * (cada uno declara {@code @EnabledIf("...PostgresTestBase#dockerAvailable")}).
 */
public abstract class PostgresTestBase {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        synchronized (POSTGRES) {
            if (!POSTGRES.isRunning()) {
                POSTGRES.start();
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Los tests de concurrencia lanzan decenas de hilos que esperan un lock dentro de su transacción
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        // El job de expiración no debe correr solo durante los tests (se prueba llamando expire() directamente)
        registry.add("app.reservations.expiration-interval-ms", () -> "3600000");
    }
}
