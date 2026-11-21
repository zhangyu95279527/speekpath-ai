package com.speakpath.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "question_bank")
public class QuestionBankItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "exam_code", nullable = false, length = 40)
    private String examCode;

    @Column(name = "section_code", nullable = false, length = 40)
    private String sectionCode;

    @Column(name = "question_text", nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String questionText;

    @Column(nullable = false)
    private short difficulty;

    @Column(name = "estimated_seconds", nullable = false)
    private int estimatedSeconds = 60;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String tags = "[]";

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected QuestionBankItem() {
    }

    public UUID getId() {
        return id;
    }

    public String getExamCode() {
        return examCode;
    }

    public String getSectionCode() {
        return sectionCode;
    }

    public String getQuestionText() {
        return questionText;
    }

    public short getDifficulty() {
        return difficulty;
    }

    public int getEstimatedSeconds() {
        return estimatedSeconds;
    }

    public String getTags() {
        return tags;
    }

    public String getStatus() {
        return status;
    }
}
