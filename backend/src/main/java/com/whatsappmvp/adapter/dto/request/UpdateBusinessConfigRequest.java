package com.whatsappmvp.adapter.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateBusinessConfigRequest {
    @NotBlank private String businessName;
    private String businessType;
    private String welcomeMessage;
    private String offHoursMessage;
    private Integer humanDelaySeconds;
    private Boolean aiEnabled;
    private String aiModel;
    private Integer maxAiTokens;
}
