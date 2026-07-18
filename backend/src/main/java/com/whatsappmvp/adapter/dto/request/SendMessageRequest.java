package com.whatsappmvp.adapter.dto.request;

import lombok.Data;

@Data
public class SendMessageRequest {
    private String conversationId;
    private String text;
}
