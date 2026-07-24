package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.infrastructure.persistence.entity.DailyMetricsEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.MessageJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ContactJpaRepository;
import com.whatsappmvp.domain.enums.ConversationStatus;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationJpaRepository;
import com.whatsappmvp.adapter.dto.response.DashboardDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MessageJpaRepository messageRepository;
    private final ConversationJpaRepository conversationRepository;
    private final ReservationJpaRepository reservationRepository;
    private final ContactJpaRepository contactRepository;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardDTO>> summary() {
        LocalDate today = LocalDate.now();

        DailyMetricsEntity metrics = DailyMetricsEntity.builder()
            .metricDate(today)
            .totalMessagesIn((int) messageRepository.countInboundByDate(today))
            .totalMessagesOut((int) messageRepository.countOutboundByDate(today))
            .totalAiCalls((int) messageRepository.countAiCallsByDate(today))
            .totalAiTokens((int) messageRepository.sumAiTokensByDate(today))
            .totalFaqMatches((int) messageRepository.countFaqByDate(today))
            .totalKeywordMatches((int) messageRepository.countKeywordByDate(today))
            .totalHumanTakeovers((int) messageRepository.countHumanByDate(today))
            .newContacts((int) contactRepository.countNewContactsByDate(today))
            .activeConversations((int) conversationRepository.countByStatus(ConversationStatus.AUTO))
            .build();

        long totalRes = reservationRepository.count();
        long pendingRes = reservationRepository.countByStatus(ReservationStatus.PENDIENTE);
        long confirmedRes = reservationRepository.countByStatus(ReservationStatus.CONFIRMADA);

        DashboardDTO dashboardDTO = DashboardDTO.builder()
            .metrics(metrics)
            .totalReservations(totalRes)
            .pendingReservations(pendingRes)
            .confirmedReservations(confirmedRes)
            .build();

        return ResponseEntity.ok(ApiResponse.ok(dashboardDTO));
    }
}
