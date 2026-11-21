package com.speakpath.ai.gateway;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 调用日志：记录 provider/model/配置版本/提示词版本/延迟/用量，大表按时间归档。
 */
@Entity
@Table(name = "ai_call_logs")
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(nullable = false, length = 32)
    private String capability;

    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode;

    @Column(name = "model_code", nullable = false, length = 120)
    private String modelCode;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(name = "prompt_version", length = 80)
    private String promptVersion;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(nullable = false, length = 20)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage_json", nullable = false, columnDefinition = "jsonb")
    private String usageJson = "{}";

    @Column(name = "error_class", length = 80)
    private String errorClass;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AiCallLog() {
    }

    public AiCallLog(String traceId, UUID userId, UUID sessionId, String capability, String providerCode,
                     String modelCode, int configVersion, String promptVersion, Integer latencyMs,
                     String status, String usageJson, String errorClass) {
        this.traceId = traceId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.capability = capability;
        this.providerCode = providerCode;
        this.modelCode = modelCode;
        this.configVersion = configVersion;
        this.promptVersion = promptVersion;
        this.latencyMs = latencyMs;
        this.status = status;
        this.usageJson = usageJson;
        this.errorClass = errorClass;
    }
}
