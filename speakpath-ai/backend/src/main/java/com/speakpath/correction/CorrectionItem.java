package com.speakpath.correction;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 纠错条目：区分确定错误与表达优化；每条反馈含原文证据与置信度。
 */
@Entity
@Table(name = "correction_items")
public class CorrectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "speaking_turn_id", nullable = false)
    private UUID speakingTurnId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "original_text", nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String originalText;

    @Column(name = "corrected_text", nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String correctedText;

    @Column(nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceJson = "{}";

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CorrectionItem() {
    }

    public CorrectionItem(UUID speakingTurnId, String category, String severity, String originalText,
                          String correctedText, String explanation, String evidenceJson, BigDecimal confidence) {
        this.speakingTurnId = speakingTurnId;
        this.category = category;
        this.severity = severity;
        this.originalText = originalText;
        this.correctedText = correctedText;
        this.explanation = explanation;
        this.evidenceJson = evidenceJson;
        this.confidence = confidence;
    }

    public UUID getSpeakingTurnId() {
        return speakingTurnId;
    }

    public String getCategory() {
        return category;
    }

    public String getSeverity() {
        return severity;
    }

    public String getOriginalText() {
        return originalText;
    }

    public String getCorrectedText() {
        return correctedText;
    }

    public String getExplanation() {
        return explanation;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }
}
