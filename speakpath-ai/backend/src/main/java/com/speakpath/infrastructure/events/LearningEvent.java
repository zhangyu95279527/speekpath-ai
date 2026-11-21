package com.speakpath.infrastructure.events;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 学习事件流：任务完成、会话结束、计划重排等；供统计与趋势分析。
 */
@Entity
@Table(name = "learning_events")
public class LearningEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson = "{}";

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected LearningEvent() {
    }

    public LearningEvent(UUID userId, String eventType, String payloadJson) {
        this.userId = userId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
    }
}
