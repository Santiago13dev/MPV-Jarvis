package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.request.ReservationRequest;
import com.whatsappmvp.adapter.dto.request.ReservationUpdateRequest;
import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.adapter.dto.response.ReservationResponse;
import com.whatsappmvp.application.reservation.Actor;
import com.whatsappmvp.application.reservation.AvailabilityModels.AvailabilityResult;
import com.whatsappmvp.application.reservation.AvailabilityModels.SlotAvailability;
import com.whatsappmvp.application.reservation.ReceiptStorageService;
import com.whatsappmvp.application.reservation.ReservationAvailabilityService;
import com.whatsappmvp.application.reservation.ReservationMessages;
import com.whatsappmvp.application.service.ReservationService;
import com.whatsappmvp.application.service.ReservationService.CreateCommand;
import com.whatsappmvp.application.service.ReservationService.ModifyCommand;
import com.whatsappmvp.application.service.ReservationService.Outcome;
import com.whatsappmvp.application.service.ReservationService.SearchFilter;
import com.whatsappmvp.application.service.ReservationService.Stats;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.domain.exception.BusinessException;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationReceiptEntity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API de reservas para el dashboard/administración. Requiere autenticación JWT (SecurityConfig).
 * No contiene reglas de negocio: todo pasa por ReservationService / ReservationAvailabilityService,
 * los mismos que usa WhatsApp.
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationAvailabilityService availability;
    private final ReservationMessages messages;
    private final ReceiptStorageService receiptStorage;

    // ── Listado / detalle ─────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<ReservationStatus> status,
            @RequestParam(required = false) ReservationType type,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500),
                Sort.by(Sort.Order.desc("slotDate"), Sort.Order.asc("slotTime")));
        Page<ReservationEntity> result = reservationService.search(
                new SearchFilter(from, to, status, type, query), pageable);
        List<ReservationResponse> body = result.getContent().stream().map(ReservationResponse::from).toList();
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.getTotalElements()))
                .body(ApiResponse.ok("Reservations retrieved successfully", body));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReservationResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(ReservationResponse.from(reservationService.getById(id))));
    }

    // ── Crear / modificar ─────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> create(
            @Valid @RequestBody ReservationRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication auth) {
        Outcome outcome = reservationService.create(new CreateCommand(
                req.getCustomerName(), req.getPhoneNumber(), req.getReceiptPhone(),
                req.getReservationType(), req.getDate(), req.getTime(), req.getPartySize(),
                req.getNotes(), null, blankToNull(idempotencyKey), req.getStatus(),
                Actor.admin(auth.getName())));
        return respond(outcome, outcome.duplicate() ? "Reserva ya existente (solicitud repetida)" : "Reserva creada");
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<ReservationResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody ReservationUpdateRequest req, Authentication auth) {
        Outcome outcome = reservationService.modify(id, new ModifyCommand(
                req.getDate(), req.getTime(), req.getPartySize(), req.getReservationType(),
                null, Actor.admin(auth.getName())));
        return respond(outcome, outcome.unchanged() ? "Sin cambios" : "Reserva actualizada");
    }

    // ── Estados ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ApiResponse<ReservationResponse>> confirm(@PathVariable UUID id, Authentication auth) {
        ReservationEntity r = reservationService.confirm(id, Actor.admin(auth.getName()));
        return ResponseEntity.ok(ApiResponse.ok("Reserva confirmada", ReservationResponse.from(r)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<ReservationResponse>> cancel(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, String> body, Authentication auth) {
        String reason = body != null ? body.get("reason") : null;
        ReservationEntity r = reservationService.cancel(id, Actor.admin(auth.getName()), reason, null);
        return ResponseEntity.ok(ApiResponse.ok("Reserva cancelada", ReservationResponse.from(r)));
    }

    @PostMapping("/{id}/reject-receipt")
    public ResponseEntity<ApiResponse<ReservationResponse>> rejectReceipt(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, String> body, Authentication auth) {
        String note = body != null ? body.get("note") : null;
        ReservationEntity r = reservationService.rejectReceipt(id, Actor.admin(auth.getName()), note);
        return ResponseEntity.ok(ApiResponse.ok("Comprobante rechazado", ReservationResponse.from(r)));
    }

    /** Compatibilidad con el dashboard anterior: solo CONFIRMED y CANCELLED. */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<ReservationResponse>> updateStatus(
            @PathVariable UUID id, @RequestParam ReservationStatus status, Authentication auth) {
        Actor actor = Actor.admin(auth.getName());
        ReservationEntity r = switch (status) {
            case CONFIRMED -> reservationService.confirm(id, actor);
            case CANCELLED -> reservationService.cancel(id, actor, null, null);
            default -> throw new BusinessException("Transición no permitida por esta vía: " + status);
        };
        return ResponseEntity.ok(ApiResponse.ok("Reservation status updated", ReservationResponse.from(r)));
    }

    /** Las reservas nunca se borran físicamente: DELETE equivale a cancelar (queda en historial/auditoría). */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id, Authentication auth) {
        reservationService.cancel(id, Actor.admin(auth.getName()), "Eliminada desde el dashboard", null);
        return ResponseEntity.ok(ApiResponse.ok("Reserva cancelada (se conserva en el historial)", null));
    }

    // ── Comprobantes ──────────────────────────────────────────────────────

    @PostMapping(value = "/{id}/payment-receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ReservationResponse>> uploadReceipt(
            @PathVariable UUID id, @RequestPart("file") MultipartFile file, Authentication auth) throws IOException {
        ReservationEntity existing = reservationService.getById(id);
        var stored = receiptStorage.store(existing.getId(), file.getBytes(), file.getContentType());
        ReservationEntity r = reservationService.registerReceipt(
                id, stored.relativePath(), stored.mimeType(), null, Actor.admin(auth.getName()));
        return ResponseEntity.ok(ApiResponse.ok("Comprobante registrado", ReservationResponse.from(r)));
    }

    @GetMapping("/{id}/receipts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> receipts(@PathVariable UUID id) {
        List<Map<String, Object>> body = reservationService.getReceipts(id).stream().map(rc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rc.getId());
            m.put("mimeType", rc.getMimeType());
            m.put("status", rc.getStatus());
            m.put("uploadedAt", rc.getUploadedAt());
            m.put("reviewedBy", rc.getReviewedBy());
            m.put("reviewedAt", rc.getReviewedAt());
            m.put("reviewNote", rc.getReviewNote());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /** Entrega el archivo (requiere JWT; el frontend lo pide como blob). */
    @GetMapping("/{id}/receipts/{receiptId}/file")
    public ResponseEntity<Resource> receiptFile(@PathVariable UUID id, @PathVariable UUID receiptId) {
        ReservationReceiptEntity rc = reservationService.getReceipt(id, receiptId);
        Resource resource = receiptStorage.load(rc.getFilePath());
        MediaType type = rc.getMimeType() != null ? MediaType.parseMediaType(rc.getMimeType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(resource);
    }

    // ── Disponibilidad ────────────────────────────────────────────────────

    @GetMapping("/availability")
    public ResponseEntity<ApiResponse<AvailabilityResult>> availability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time) {
        return ResponseEntity.ok(ApiResponse.ok(availability.getAvailability(date, time)));
    }

    @GetMapping("/available-slots")
    public ResponseEntity<ApiResponse<List<LocalTime>>> availableSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "NORMAL") ReservationType type,
            @RequestParam int partySize) {
        return ResponseEntity.ok(ApiResponse.ok(availability.findAvailableSlots(date, type, partySize, null)));
    }

    /** Panel de ocupación: todas las franjas de un día. */
    @GetMapping("/day-availability")
    public ResponseEntity<ApiResponse<List<SlotAvailability>>> dayAvailability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(availability.getDayAvailability(date)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Stats>> stats() {
        return ResponseEntity.ok(ApiResponse.ok(reservationService.stats()));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Si el backend rechazó la operación (cupo, VIP, día cerrado...) responde 409 con texto humano. */
    private ResponseEntity<ApiResponse<ReservationResponse>> respond(Outcome outcome, String okMessage) {
        if (outcome.check() != null && !outcome.check().available()) {
            throw new BusinessException(messages.reasonText(outcome.check()), HttpStatus.CONFLICT);
        }
        return ResponseEntity.ok(ApiResponse.ok(okMessage, ReservationResponse.from(outcome.reservation())));
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
