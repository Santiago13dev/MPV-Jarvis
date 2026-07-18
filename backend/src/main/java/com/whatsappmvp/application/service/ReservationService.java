package com.whatsappmvp.application.service;

import com.whatsappmvp.adapter.dto.request.ReservationRequest;
import com.whatsappmvp.adapter.dto.response.ReservationResponse;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationJpaRepository reservationRepository;

    @Transactional(readOnly = true)
    public List<ReservationResponse> getAllReservations() {
        return reservationRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReservationResponse createReservation(ReservationRequest request) {
        ReservationEntity entity = ReservationEntity.builder()
                .customerName(request.getCustomerName())
                .phoneNumber(request.getPhoneNumber())
                .reservationDate(request.getReservationDate())
                .amount(request.getAmount())
                .status(request.getStatus() != null ? request.getStatus() : ReservationStatus.PENDIENTE)
                .notes(request.getNotes())
                .build();
        
        ReservationEntity saved = reservationRepository.save(entity);
        return mapToResponse(saved);
    }

    @Transactional
    public ReservationResponse updateReservationStatus(UUID id, ReservationStatus status) {
        ReservationEntity entity = reservationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Reservation not found"));
        entity.setStatus(status);
        ReservationEntity updated = reservationRepository.save(entity);
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteReservation(UUID id) {
        reservationRepository.deleteById(id);
    }

    private ReservationResponse mapToResponse(ReservationEntity entity) {
        return ReservationResponse.builder()
                .id(entity.getId())
                .customerName(entity.getCustomerName())
                .phoneNumber(entity.getPhoneNumber())
                .reservationDate(entity.getReservationDate())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
