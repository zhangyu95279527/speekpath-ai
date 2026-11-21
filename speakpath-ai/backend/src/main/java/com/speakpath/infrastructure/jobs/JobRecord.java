package com.speakpath.infrastructure.jobs;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 异步任务记录：计划生成、报告生成、课后反馈等均通过 job 编排，接口快速返回 202 + jobId。
 */
@Entity
@Table(name = "jobs")
public class JobRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "job_type", nullable = false, length = 40)
    private String jobType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private short progress;

    @Column(name = "result_ref", length = 255)
    private String resultRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private String resultJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_json", columnDefinition = "jsonb")
    private String errorJson;

    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected JobRecord() {
    }

    public JobRecord(UUID userId, String jobType, String idempotencyKey) {
        this.userId = userId;
        this.jobType = jobType;
        this.status = "QUEUED";
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getJobType() {
        return jobType;
    }

    public String getStatus() {
        return status;
    }

    public void markRunning() {
        this.status = "RUNNING";
        this.startedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markSucceeded(String resultRef, String resultJson, short progress) {
        this.status = "SUCCEEDED";
        this.resultRef = resultRef;
        this.resultJson = resultJson == null ? "{}" : resultJson;
        this.progress = progress;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorJson) {
        this.status = "FAILED";
        this.errorJson = errorJson;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public short getProgress() {
        return progress;
    }

    public void setProgress(short progress) {
        this.progress = progress;
    }

    public String getResultRef() {
        return resultRef;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getErrorJson() {
        return errorJson;
    }
}
