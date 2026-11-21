package com.speakpath.progress;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 每日学习评价记录（每天 0 点后自动生成）。 */
@Entity
@Table(name = "daily_learning_reports")
public class DailyLearningReport {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "tasks_total", nullable = false)
    private int tasksTotal;

    @Column(name = "tasks_done", nullable = false)
    private int tasksDone;

    @Column(name = "speaking_sessions", nullable = false)
    private int speakingSessions;

    @Column(name = "speaking_minutes", nullable = false)
    private int speakingMinutes;

    @Column(name = "turns_count", nullable = false)
    private int turnsCount;

    @Column(name = "pronunciation_avg")
    private Integer pronunciationAvg;

    private Integer score;

    @Column(columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String suggestions = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DailyLearningReport() {
    }

    public DailyLearningReport(UUID userId, LocalDate reportDate) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.reportDate = reportDate;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public int getTasksTotal() {
        return tasksTotal;
    }

    public void setTasksTotal(int tasksTotal) {
        this.tasksTotal = tasksTotal;
    }

    public int getTasksDone() {
        return tasksDone;
    }

    public void setTasksDone(int tasksDone) {
        this.tasksDone = tasksDone;
    }

    public int getSpeakingSessions() {
        return speakingSessions;
    }

    public void setSpeakingSessions(int speakingSessions) {
        this.speakingSessions = speakingSessions;
    }

    public int getSpeakingMinutes() {
        return speakingMinutes;
    }

    public void setSpeakingMinutes(int speakingMinutes) {
        this.speakingMinutes = speakingMinutes;
    }

    public int getTurnsCount() {
        return turnsCount;
    }

    public void setTurnsCount(int turnsCount) {
        this.turnsCount = turnsCount;
    }

    public Integer getPronunciationAvg() {
        return pronunciationAvg;
    }

    public void setPronunciationAvg(Integer pronunciationAvg) {
        this.pronunciationAvg = pronunciationAvg;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(String suggestions) {
        this.suggestions = suggestions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
