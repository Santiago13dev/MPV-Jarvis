package com.whatsappmvp.application.service;

import com.whatsappmvp.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio de Rate Limiting anti-spam.
 * Usa ventanas de tiempo en memoria (más rápido que DB para cada mensaje).
 * Para producción con múltiples instancias, migrar a Redis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final AppProperties appProperties;

    // phone → [count, windowStart]
    private final Map<String, RateLimitEntry> windowMap = new ConcurrentHashMap<>();

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
