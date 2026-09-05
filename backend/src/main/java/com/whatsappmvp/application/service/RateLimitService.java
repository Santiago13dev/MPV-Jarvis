package com.whatsappmvp.application.service;

import com.whatsappmvp.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio de Rate Limiting anti-spam + control de envíos outbound.
 * - Inbound: ventanas de tiempo por teléfono (anti-spam del cliente)
 * - Outbound: semaphore global + delay por usuario (evita bloqueo por WhatsApp)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final AppProperties appProperties;

    // ── INBOUND (mensajes del cliente) ──────────────────────────────────────
    private final Map<String, RateLimitEntry> windowMap = new ConcurrentHashMap<>();

    // ── OUTBOUND (respuestas del bot) ───────────────────────────────────────
    // Máximo 3 envíos concurrentes a WhatsApp API
    private final Semaphore outboundSemaphore = new Semaphore(3);
    // Delay mínimo entre mensajes al mismo usuario (evita flooding)
    private final Map<String, Instant> lastSendTime = new ConcurrentHashMap<>();
    private static final Duration MIN_DELAY_BETWEEN_SENDS = Duration.ofSeconds(2);

    /**
     * Verifica si el teléfono está dentro del límite de mensajes.
     * @return true si está dentro del límite (permitir), false si excede (bloquear)
     */
    public boolean isAllowed(String phone) {
        int maxMessages = appProperties.getRateLimit().getMaxMessagesPerWindow();
        int windowMinutes = appProperties.getRateLimit().getWindowMinutes();

        windowMap.compute(phone, (key, entry) -> {
            LocalDateTime now = LocalDateTime.now();

            if (entry == null || entry.windowStart.plusMinutes(windowMinutes).isBefore(now)) {
                // Nueva ventana
                return new RateLimitEntry(new AtomicInteger(1), now);
            }

            // Ventana activa — incrementar contador
            entry.count.incrementAndGet();
            return entry;
        });

        RateLimitEntry entry = windowMap.get(phone);
        boolean allowed = entry.count.get() <= maxMessages;

        if (!allowed) {
            log.warn("[RateLimit] Phone {} exceeded limit: {}/{} in {} min window",
                    phone, entry.count.get(), maxMessages, windowMinutes);
        }

        return allowed;
    }

    /**
     * Bloquea hasta que sea seguro enviar un mensaje outbound.
     * - Espera por un slot del semaphore (max 3 concurrentes)
     * - Espera el delay mínimo entre mensajes al mismo usuario (2s)
     */
    public void acquireOutbound(String phone) throws InterruptedException {
        // 1. Esperar por un slot del semaphore global
        outboundSemaphore.acquire();

        // 2. Esperar delay mínimo por usuario
        Instant lastSend = lastSendTime.get(phone);
        if (lastSend != null) {
            long waitMs = MIN_DELAY_BETWEEN_SENDS.toMillis() - Duration.between(lastSend, Instant.now()).toMillis();
            if (waitMs > 0) {
                log.debug("[RateLimit] Per-user delay: waiting {}ms for phone {}", waitMs, phone);
                Thread.sleep(waitMs);
            }
        }
    }

    /**
     * Libera el slot del semaphore y registra el timestamp de envío.
     */
    public void releaseOutbound(String phone) {
        lastSendTime.put(phone, Instant.now());
        outboundSemaphore.release();
    }

    /** Limpiar ventanas expiradas cada 10 minutos para evitar memory leak */
    @Scheduled(fixedDelay = 600_000)
    public void cleanExpiredWindows() {
        int windowMinutes = appProperties.getRateLimit().getWindowMinutes();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(windowMinutes);
        int before = windowMap.size();
        windowMap.entrySet().removeIf(e -> e.getValue().windowStart.isBefore(cutoff));
        int removed = before - windowMap.size();
        if (removed > 0) {
            log.debug("[RateLimit] Cleaned {} expired rate limit entries", removed);
        }

        // Limpiar timestamps de envío antiguos (> 1 hora)
        Instant cutoffSend = Instant.now().minus(Duration.ofHours(1));
        lastSendTime.entrySet().removeIf(e -> e.getValue().isBefore(cutoffSend));
    }

    private static class RateLimitEntry {
        final AtomicInteger count;
        final LocalDateTime windowStart;

        RateLimitEntry(AtomicInteger count, LocalDateTime windowStart) {
            this.count = count;
            this.windowStart = windowStart;
        }
    }
}
