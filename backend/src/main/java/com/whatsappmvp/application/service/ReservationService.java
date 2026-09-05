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
import java.util.Optional;
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
                .peopleCount(request.getPeopleCount())
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

    @Transactional
    public Optional<ReservationEntity> cancelLatestByPhoneNumber(String phoneNumber) {
        Optional<ReservationEntity> opt = reservationRepository.findLatestActiveByPhoneNumber(phoneNumber);
        if (opt.isEmpty()) return Optional.empty();
        ReservationEntity entity = opt.get();
        reservationRepository.delete(entity);
        return Optional.of(entity);
    }

    @Transactional
    public Optional<ReservationEntity> updateLatestPeopleCount(String phoneNumber, int newPeopleCount) {
        Optional<ReservationEntity> opt = reservationRepository.findLatestActiveByPhoneNumber(phoneNumber);
        if (opt.isEmpty()) return Optional.empty();
        ReservationEntity entity = opt.get();
        entity.setPeopleCount(newPeopleCount);
        reservationRepository.save(entity);
        return Optional.of(entity);
    }

    @Transactional
    public Optional<ReservationEntity> addPeopleToLatest(String phoneNumber, int additionalPeople) {
        Optional<ReservationEntity> opt = reservationRepository.findLatestActiveByPhoneNumber(phoneNumber);
        if (opt.isEmpty()) return Optional.empty();
        ReservationEntity entity = opt.get();
        int existing = entity.getPeopleCount() != null ? entity.getPeopleCount() : 0;
        entity.setPeopleCount(existing + additionalPeople);
        reservationRepository.save(entity);
        return Optional.of(entity);
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
                .peopleCount(entity.getPeopleCount())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
