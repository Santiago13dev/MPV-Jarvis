package com.whatsappmvp.infrastructure.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "contacts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ContactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String phone;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "is_blocked")
    @Builder.Default
    private Boolean isBlocked = false;

    // Almacenado como array de texto en PostgreSQL
    @Column(columnDefinition = "TEXT[]")
    private String[] tags;

    private String notes;

    @CreationTimestamp
    @Column(name = "first_seen", updatable = false)
    private LocalDateTime firstSeen;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;
}
