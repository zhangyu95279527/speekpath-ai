package com.speakpath.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 考试模板：版本化配置与评分 rubric，session 锁定版本。
 */
@Entity
@Table(name = "exam_templates")
public class ExamTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "exam_code", nullable = false, length = 40)
    private String examCode;

    @Column(nullable = false)
    private int version;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private String configJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rubric_json", nullable = false, columnDefinition = "jsonb")
    private String rubricJson;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ExamTemplate() {
    }

    public UUID getId() {
        return id;
    }

    public String getExamCode() {
        return examCode;
    }

    public int getVersion() {
        return version;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getConfigJson() {
        return configJson;
    }

    public String getRubricJson() {
        return rubricJson;
    }

    public String getStatus() {
        return status;
    }
}
