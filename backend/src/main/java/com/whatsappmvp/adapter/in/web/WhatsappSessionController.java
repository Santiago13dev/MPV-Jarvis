package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.infrastructure.client.WhatsAppServiceClient;
import com.whatsappmvp.infrastructure.persistence.jpa.WhatsappSessionJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/whatsapp")
@RequiredArgsConstructor
public class WhatsappSessionController {

    private final WhatsAppServiceClient whatsAppClient;
    private final WhatsappSessionJpaRepository sessionRepository;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<?, ?>>> getStatus() {
        return ResponseEntity.ok(ApiResponse.ok(whatsAppClient.getSessionStatus()));
    }

    @GetMapping("/qr")
    public ResponseEntity<ApiResponse<Map<?, ?>>> getQr() {
        return ResponseEntity.ok(ApiResponse.ok(whatsAppClient.getQrCode()));
    }

    @GetMapping("/session")
    public ResponseEntity<ApiResponse<?>> getSession() {
        return ResponseEntity.ok(ApiResponse.ok(sessionRepository.findBySessionName("default").orElse(null)));
    }

    @PostMapping("/reconnect")
    public ResponseEntity<ApiResponse<Void>> reconnect() {
        whatsAppClient.reconnect();
        return ResponseEntity.ok(ApiResponse.ok("Reconnect initiated", null));
    }

    @PostMapping("/disconnect")
    public ResponseEntity<ApiResponse<Void>> disconnect() {
        whatsAppClient.disconnect();
        return ResponseEntity.ok(ApiResponse.ok("Disconnected", null));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<Void>> reset() {
        whatsAppClient.resetSession();
        return ResponseEntity.ok(ApiResponse.ok("Session reset", null));
    }
}
