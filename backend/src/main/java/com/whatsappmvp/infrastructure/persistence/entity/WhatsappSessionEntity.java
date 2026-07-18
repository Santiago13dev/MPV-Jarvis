package com.whatsappmvp.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsappSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_name", unique = true, nullable = false)
    @Builder.Default
    private String sessionName = "default";

    // DISCONNECTED | CONNECTING | QR_READY | CONNECTED | ERROR
    @Column(nullable = false)
    @Builder.Default
    private String status = "DISCONNECTED";

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "qr_code", columnDefinition = "TEXT")
    private String qrCode;

    @Column(name = "connected_at")
    private LocalDateTime connectedAt;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
