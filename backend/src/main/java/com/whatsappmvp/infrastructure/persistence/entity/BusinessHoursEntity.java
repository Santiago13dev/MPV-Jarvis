package com.whatsappmvp.infrastructure.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "business_hours")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessHoursEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_config_id", nullable = false)
    @JsonIgnore
    private BusinessConfigEntity businessConfig;

    // 0=Dom, 1=Lun, ..., 6=Sab
    @Column(name = "day_of_week", nullable = false)
    private Short dayOfWeek;

    @Column(name = "open_time", nullable = false)
    private java.time.LocalTime openTime;

    @Column(name = "close_time", nullable = false)
    private java.time.LocalTime closeTime;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;
}
