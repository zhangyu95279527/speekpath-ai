package com.speakpath.speaking;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 实时口语会话：记录 provider/model/transport 与时间线，课后异步反馈。
 */
@Entity
@Table(name = "speaking_sessions")
public class SpeakingSession {

    public static final String CREATED = "CREATED";
    public static final String CONNECTED = "CONNECTED";
    public static final String ENDED = "ENDED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "topic", length = 255)
    private String topic;

    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode;

    @Column(name = "model_code", nullable = false, length = 120)
    private String modelCode;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(nullable = false, length = 20)
    private String transport;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "summary_job_id")
    private UUID summaryJobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SpeakingSession() {
    }

    public SpeakingSession(UUID userId, UUID taskId, String providerCode, String modelCode, String transport) {
        this.userId = userId;
        this.taskId = taskId;
        this.providerCode = providerCode;
        this.modelCode = modelCode;
        this.transport = transport;
        this.status = CREATED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getModelCode() {
        return modelCode;
    }

    public String getStatus() {
        return status;
    }

    public String getTransport() {
        return transport;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public UUID getSummaryJobId() {
        return summaryJobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void markConnected() {
        this.status = CONNECTED;
        this.startedAt = Instant.now();
    }

    public void end() {
        this.status = ENDED;
        this.endedAt = Instant.now();
        if (this.startedAt != null) {
            this.durationMs = java.time.Duration.between(this.startedAt, this.endedAt).toMillis();
        }
    }

    public void fail() {
        this.status = FAILED;
        this.endedAt = Instant.now();
    }

    public void setSummaryJobId(UUID summaryJobId) {
        this.summaryJobId = summaryJobId;
    }
}
