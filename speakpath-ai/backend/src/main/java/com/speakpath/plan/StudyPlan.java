package com.speakpath.plan;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "study_plans")
public class StudyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_report_id")
    private UUID sourceReportId;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "active_version", nullable = false)
    private int activeVersion = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "goal_json", nullable = false, columnDefinition = "jsonb")
    private String goalJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected StudyPlan() {
    }

    public StudyPlan(UUID userId, UUID sourceReportId, LocalDate startDate, LocalDate endDate, String goalJson) {
        this.userId = userId;
        this.sourceReportId = sourceReportId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.goalJson = goalJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getSourceReportId() {
        return sourceReportId;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getActiveVersion() {
        return activeVersion;
    }

    public void setActiveVersion(int activeVersion) {
        this.activeVersion = activeVersion;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGoalJson() {
        return goalJson;
    }

    public void setGoalJson(String goalJson) {
        this.goalJson = goalJson;
    }
}
