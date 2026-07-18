package com.whatsappmvp.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "daily_metrics")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyMetricsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "metric_date", unique = true, nullable = false)
    private java.time.LocalDate metricDate;

    @Builder.Default @Column(name = "total_messages_in")     private Integer totalMessagesIn = 0;
    @Builder.Default @Column(name = "total_messages_out")    private Integer totalMessagesOut = 0;
    @Builder.Default @Column(name = "total_ai_calls")        private Integer totalAiCalls = 0;
    @Builder.Default @Column(name = "total_ai_tokens")       private Integer totalAiTokens = 0;
    @Builder.Default @Column(name = "total_faq_matches")     private Integer totalFaqMatches = 0;
    @Builder.Default @Column(name = "total_keyword_matches") private Integer totalKeywordMatches = 0;
    @Builder.Default @Column(name = "total_human_takeovers") private Integer totalHumanTakeovers = 0;
    @Builder.Default @Column(name = "new_contacts")          private Integer newContacts = 0;
    @Builder.Default @Column(name = "active_conversations")  private Integer activeConversations = 0;
}
