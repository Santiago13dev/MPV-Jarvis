package com.whatsappmvp.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "business_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "business_type")
    private String businessType;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage;

    @Column(name = "off_hours_message", columnDefinition = "TEXT")
    private String offHoursMessage;

    @Column(name = "human_delay_seconds")
    @Builder.Default
    private Integer humanDelaySeconds = 300;

    @Column(name = "ai_enabled")
    @Builder.Default
    private Boolean aiEnabled = true;

    @Column(name = "ai_model")
    @Builder.Default
    private String aiModel = "gpt-4o-mini";

    @Column(name = "max_ai_tokens")
    @Builder.Default
    private Integer maxAiTokens = 500;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
