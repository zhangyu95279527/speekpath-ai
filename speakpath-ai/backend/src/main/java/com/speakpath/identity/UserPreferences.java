package com.speakpath.identity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "user_preferences")
public class UserPreferences {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "daily_minutes", nullable = false)
    private int dailyMinutes = 30;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rest_days", nullable = false, columnDefinition = "jsonb")
    private String restDays = "[]";

    @Column(name = "target_exam", length = 40)
    private String targetExam;

    @Column(name = "target_score", precision = 4, scale = 1)
    private BigDecimal targetScore;

    @Column(name = "exam_date")
    private LocalDate examDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preference_json", nullable = false, columnDefinition = "jsonb")
    private String preferenceJson = "{}";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserPreferences() {
    }

    public UserPreferences(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getDailyMinutes() {
        return dailyMinutes;
    }

    public void setDailyMinutes(int dailyMinutes) {
        this.dailyMinutes = dailyMinutes;
        this.updatedAt = Instant.now();
    }

    public String getRestDays() {
        return restDays;
    }

    public void setRestDays(String restDays) {
        this.restDays = restDays;
        this.updatedAt = Instant.now();
    }

    public String getTargetExam() {
        return targetExam;
    }

    public void setTargetExam(String targetExam) {
        this.targetExam = targetExam;
        this.updatedAt = Instant.now();
    }

    public BigDecimal getTargetScore() {
        return targetScore;
    }

    public void setTargetScore(BigDecimal targetScore) {
        this.targetScore = targetScore;
        this.updatedAt = Instant.now();
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDate examDate) {
        this.examDate = examDate;
        this.updatedAt = Instant.now();
    }

    public String getPreferenceJson() {
        return preferenceJson;
    }

    public void setPreferenceJson(String preferenceJson) {
        this.preferenceJson = preferenceJson;
        this.updatedAt = Instant.now();
    }
}
