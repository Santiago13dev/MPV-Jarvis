package com.whatsappmvp.adapter.dto.request;

import lombok.Data;

@Data
public class WebhookMessageRequest {
    private String waMessageId;
    private String phone;
    private String remoteJid;
    private String displayName;
    private String content;
    private String messageType;
    private String timestamp;

    /** Adjunto (imagen/PDF) en base64, solo cuando el cliente envía un posible comprobante de pago. */
    private String mediaBase64;
    private String mediaMimeType;
}
