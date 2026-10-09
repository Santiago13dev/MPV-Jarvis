package com.whatsappmvp.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Configuración de reservas del restaurante (singleton, id = 1). */
@Entity
@Table(name = "restaurant_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantSettingsEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    @Builder.Default
    private Short id = SINGLETON_ID;

    @Column(name = "normal_price", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal normalPrice = new BigDecimal("40000");

    @Column(name = "vip_price", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal vipPrice = new BigDecimal("100000");

    @Column(name = "vip_capacity", nullable = false)
    @Builder.Default
    private Integer vipCapacity = 20;

    /** CSV de DayOfWeek, p. ej. "SATURDAY,SUNDAY". */
    @Column(name = "operating_days", nullable = false, length = 100)
    @Builder.Default
    private String operatingDays = "SATURDAY,SUNDAY";

    @Column(name = "reservation_start_time", nullable = false)
    @Builder.Default
    private LocalTime reservationStartTime = LocalTime.of(11, 30);

    @Column(name = "reservation_end_time", nullable = false)
    @Builder.Default
    private LocalTime reservationEndTime = LocalTime.of(13, 0);

    @Column(name = "slot_interval_minutes", nullable = false)
    @Builder.Default
    private Integer slotIntervalMinutes = 30;

    @Column(name = "default_slot_capacity", nullable = false)
    @Builder.Default
    private Integer defaultSlotCapacity = 50;

    @Column(name = "max_party_size", nullable = false)
    @Builder.Default
    private Integer maxPartySize = 40;

    @Column(name = "hold_minutes", nullable = false)
    @Builder.Default
    private Integer holdMinutes = 60;

    @Column(name = "vip_consumes_normal_capacity", nullable = false)
    @Builder.Default
    private Boolean vipConsumesNormalCapacity = false;

    @Column(name = "payment_instructions", columnDefinition = "TEXT")
    private String paymentInstructions;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    public Set<DayOfWeek> operatingDaysSet() {
        if (operatingDays == null || operatingDays.isBlank()) return EnumSet.noneOf(DayOfWeek.class);
        Set<DayOfWeek> days = Arrays.stream(operatingDays.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> DayOfWeek.valueOf(s.toUpperCase()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
        return days;
    }

    public void setOperatingDaysSet(Set<DayOfWeek> days) {
        this.operatingDays = days.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
    }
}
