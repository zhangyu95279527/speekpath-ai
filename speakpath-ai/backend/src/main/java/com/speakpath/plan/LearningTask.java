package com.speakpath.plan;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 学习任务：绑定计划版本；完成任务不随版本变更丢失（保留历史引用）。
 */
@Entity
@Table(name = "learning_tasks")
public class LearningTask {

    public static final String TODO = "TODO";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String SKIPPED = "SKIPPED";
    public static final String RESCHEDULED = "RESCHEDULED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_version_id", nullable = false)
    private UUID planVersionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "task_date", nullable = false)
    private LocalDate taskDate;

    @Column(name = "task_type", nullable = false, length = 40)
    private String taskType;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(name = "duration_min", nullable = false)
    private short durationMin;

    @Column(nullable = false)
    private short priority = 3;

    @Column(nullable = false, length = 20)
    private String status = TODO;

    @Column(name = "is_locked", nullable = false)
    private boolean locked = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    private String contentJson = "{}";

    @Column(name = "actual_duration_min")
    private Short actualDurationMin;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LearningTask() {
    }

    public LearningTask(UUID planVersionId, UUID userId, LocalDate taskDate, String taskType, String title,
                        short durationMin, short priority, String contentJson) {
        this.planVersionId = planVersionId;
        this.userId = userId;
        this.taskDate = taskDate;
        this.taskType = taskType;
        this.title = title;
        this.durationMin = durationMin;
        this.priority = priority;
        this.contentJson = contentJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanVersionId() {
        return planVersionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getTaskDate() {
        return taskDate;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getTitle() {
        return title;
    }

    public short getDurationMin() {
        return durationMin;
    }

    public short getPriority() {
        return priority;
    }

    public String getStatus() {
        return status;
    }

    public boolean isLocked() {
        return locked;
    }

    public String getContentJson() {
        return contentJson;
    }

    public Short getActualDurationMin() {
        return actualDurationMin;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setTaskDate(LocalDate taskDate) {
        this.taskDate = taskDate;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public void complete(short actualMinutes) {
        this.status = COMPLETED;
        this.actualDurationMin = actualMinutes;
        this.completedAt = Instant.now();
    }

    public void skip() {
        this.status = SKIPPED;
    }

    public void reschedule(LocalDate targetDate) {
        this.taskDate = targetDate;
        this.status = TODO;
    }

    /** 每日主题化重建：原位更新任务内容，保留任务 ID（口语会话可能引用）。 */
    public void updateContent(String title, short durationMin, short priority, String contentJson) {
        this.title = title;
        this.durationMin = durationMin;
        this.priority = priority;
        this.contentJson = contentJson;
        this.status = TODO;
    }
}
