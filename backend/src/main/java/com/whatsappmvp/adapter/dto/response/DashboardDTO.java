package com.whatsappmvp.adapter.dto.response;

import com.whatsappmvp.infrastructure.persistence.entity.DailyMetricsEntity;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardDTO {
    private DailyMetricsEntity metrics;
    private long totalReservations;
    private long pendingReservations;
    private long confirmedReservations;
}
