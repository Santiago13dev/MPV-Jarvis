package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.request.ReservationRequest;
import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.adapter.dto.response.ReservationResponse;
import com.whatsappmvp.application.service.ReservationService;
import com.whatsappmvp.domain.enums.ReservationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> getAllReservations() {
        List<ReservationResponse> reservations = reservationService.getAllReservations();
        return ResponseEntity.ok(ApiResponse.ok("Reservations retrieved successfully", reservations));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> createReservation(@RequestBody ReservationRequest request) {
        ReservationResponse response = reservationService.createReservation(request);
        return ResponseEntity.ok(ApiResponse.ok("Reservation created successfully", response));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<ReservationResponse>> updateStatus(
            @PathVariable UUID id,
            @RequestParam ReservationStatus status) {
        ReservationResponse response = reservationService.updateReservationStatus(id, status);
        return ResponseEntity.ok(ApiResponse.ok("Reservation status updated", response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteReservation(@PathVariable UUID id) {
        reservationService.deleteReservation(id);
        return ResponseEntity.ok(ApiResponse.ok("Reservation deleted successfully", null));
    }
}
