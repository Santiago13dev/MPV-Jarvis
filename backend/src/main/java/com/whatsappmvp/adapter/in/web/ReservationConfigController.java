package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.request.ReservationSettingsRequest;
import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.application.reservation.RestaurantSettingsService;
import com.whatsappmvp.application.reservation.RestaurantSettingsService.SettingsUpdate;
import com.whatsappmvp.domain.exception.BusinessException;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationBlockEntity;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import com.whatsappmvp.infrastructure.persistence.entity.SlotCapacityRuleEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Configuración de reservas (precios, VIP, horarios, capacidades, bloqueos). Solo administración (JWT).
 * Nada está hardcodeado en el motor: todo se edita desde aquí.
 */
@RestController
@RequestMapping("/api/reservations/configuration")
@RequiredArgsConstructor
public class ReservationConfigController {

    private final RestaurantSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> get() {
        return ResponseEntity.ok(ApiResponse.ok(toMap(settingsService.get())));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@RequestBody ReservationSettingsRequest r) {
        RestaurantSettingsEntity s = settingsService.update(new SettingsUpdate(
                r.getNormalPrice(), r.getVipPrice(), r.getVipCapacity(), r.getOperatingDays(),
                r.getReservationStartTime(), r.getReservationEndTime(), r.getSlotIntervalMinutes(),
                r.getDefaultSlotCapacity(), r.getMaxPartySize(), r.getHoldMinutes(),
                r.getVipConsumesNormalCapacity(), r.getPaymentInstructions()));
        return ResponseEntity.ok(ApiResponse.ok("Configuración actualizada", toMap(s)));
    }

    // ── Capacidad por día y/o franja ──────────────────────────────────────

    @GetMapping("/capacity-rules")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> rules() {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.listCapacityRules().stream().map(this::toMap).toList()));
    }

    /** dayOfWeek y/o slotTime pueden ser null (comodín). La regla más específica gana. */
    @PostMapping("/capacity-rules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addRule(@RequestBody Map<String, Object> body) {
        DayOfWeek day = body.get("dayOfWeek") != null ? DayOfWeek.valueOf(body.get("dayOfWeek").toString()) : null;
        LocalTime time = body.get("slotTime") != null ? LocalTime.parse(body.get("slotTime").toString()) : null;
        if (body.get("capacity") == null) throw new BusinessException("La capacidad es obligatoria");
        int capacity = ((Number) body.get("capacity")).intValue();
        return ResponseEntity.ok(ApiResponse.ok("Regla creada",
                toMap(settingsService.addCapacityRule(day, time, capacity))));
    }

    @DeleteMapping("/capacity-rules/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable UUID id) {
        settingsService.deleteCapacityRule(id);
        return ResponseEntity.ok(ApiResponse.ok("Regla eliminada", null));
    }

    // ── Bloqueo de fechas / franjas ───────────────────────────────────────

    @GetMapping("/blocks")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> blocks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        LocalDate start = from != null ? from : LocalDate.now();
        return ResponseEntity.ok(ApiResponse.ok(settingsService.listBlocks(start).stream().map(this::toMap).toList()));
    }

    /** slotTime null = se bloquea el día completo. */
    @PostMapping("/blocks")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addBlock(@RequestBody Map<String, Object> body) {
        if (body.get("date") == null) throw new BusinessException("La fecha es obligatoria");
        LocalDate date = LocalDate.parse(body.get("date").toString());
        LocalTime time = body.get("slotTime") != null && !body.get("slotTime").toString().isBlank()
                ? LocalTime.parse(body.get("slotTime").toString()) : null;
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        return ResponseEntity.ok(ApiResponse.ok("Bloqueo creado", toMap(settingsService.addBlock(date, time, reason))));
    }

    @DeleteMapping("/blocks/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteBlock(@PathVariable UUID id) {
        settingsService.deleteBlock(id);
        return ResponseEntity.ok(ApiResponse.ok("Bloqueo eliminado", null));
    }

    // ── mapeo ─────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(RestaurantSettingsEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("normalPrice", s.getNormalPrice());
        m.put("vipPrice", s.getVipPrice());
        m.put("vipCapacity", s.getVipCapacity());
        m.put("operatingDays", s.operatingDaysSet().stream().sorted().map(DayOfWeek::name).toList());
        m.put("reservationStartTime", s.getReservationStartTime());
        m.put("reservationEndTime", s.getReservationEndTime());
        m.put("slotIntervalMinutes", s.getSlotIntervalMinutes());
        m.put("defaultSlotCapacity", s.getDefaultSlotCapacity());
        m.put("maxPartySize", s.getMaxPartySize());
        m.put("holdMinutes", s.getHoldMinutes());
        m.put("vipConsumesNormalCapacity", s.getVipConsumesNormalCapacity());
        m.put("paymentInstructions", s.getPaymentInstructions());
        return m;
    }

    private Map<String, Object> toMap(SlotCapacityRuleEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("dayOfWeek", r.getDayOfWeek());
        m.put("slotTime", r.getSlotTime());
        m.put("capacity", r.getCapacity());
        return m;
    }

    private Map<String, Object> toMap(ReservationBlockEntity b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("date", b.getBlockDate());
        m.put("slotTime", b.getSlotTime());
        m.put("reason", b.getReason());
        return m;
    }
}
