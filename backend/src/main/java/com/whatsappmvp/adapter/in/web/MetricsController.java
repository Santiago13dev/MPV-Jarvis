package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.infrastructure.persistence.entity.DailyMetricsEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.MessageJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import com.whatsappmvp.domain.enums.ConversationStatus;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationJpaRepository;
import com.whatsappmvp.adapter.dto.response.DashboardDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MessageJpaRepository messageRepository;
    private final ConversationJpaRepository conversationRepository;
    private final ReservationJpaRepository reservationRepository;

    /**
     * Retorna métricas del día actual calculadas en tiempo real.
     * Para producción, usar la tabla daily_metrics pre-agregada.
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardDTO>> summary() {
        LocalDate today = LocalDate.now();

        DailyMetricsEntity metrics = DailyMetricsEntity.builder()
            .metricDate(today)
            .totalMessagesIn((int) messageRepository.countInboundByDate(today))
            .totalMessagesOut((int) messageRepository.countOutboundByDate(today))
            .totalAiTokens((int) messageRepository.sumAiTokensByDate(today))
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
