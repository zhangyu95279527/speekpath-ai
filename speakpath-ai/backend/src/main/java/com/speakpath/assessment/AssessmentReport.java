package com.speakpath.assessment;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 测评报告：保存 rubric/model/prompt 版本，支持审计与历史复算。
 */
@Entity
@Table(name = "assessment_reports")
public class AssessmentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "rubric_version", nullable = false)
    private int rubricVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", nullable = false, columnDefinition = "jsonb")
    private String reportJson;

    @Column(name = "overall_low", precision = 4, scale = 1)
    private BigDecimal overallLow;

    @Column(name = "overall_high", precision = 4, scale = 1)
    private BigDecimal overallHigh;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_trace_json", nullable = false, columnDefinition = "jsonb")
    private String modelTraceJson = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AssessmentReport() {
    }

    public AssessmentReport(UUID sessionId, int rubricVersion, String reportJson,
                            BigDecimal overallLow, BigDecimal overallHigh, BigDecimal confidence,
                            String modelTraceJson) {
        this.sessionId = sessionId;
        this.rubricVersion = rubricVersion;
        this.reportJson = reportJson;
        this.overallLow = overallLow;
        this.overallHigh = overallHigh;
        this.confidence = confidence;
        this.modelTraceJson = modelTraceJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getReportJson() {
        return reportJson;
    }

    public BigDecimal getOverallLow() {
        return overallLow;
    }

    public BigDecimal getOverallHigh() {
        return overallHigh;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
