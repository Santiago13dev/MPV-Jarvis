package com.whatsappmvp.adapter.dto.request;

import lombok.Data;

@Data
public class WebhookMessageRequest {
    private String waMessageId;
    private String phone;
    private String displayName;
    private String content;
    private String messageType;
    private String timestamp;
}
