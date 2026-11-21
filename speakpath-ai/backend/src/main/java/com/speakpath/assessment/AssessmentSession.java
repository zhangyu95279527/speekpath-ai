package com.speakpath.assessment;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 测评会话：锁定考试模板与 rubric 版本；状态机见规格书 §2.2。
 */
@Entity
@Table(name = "assessment_sessions")
public class AssessmentSession {

    public static final String CREATED = "CREATED";
    public static final String PREPARING = "PREPARING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String PROCESSING = "PROCESSING";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABORTED = "ABORTED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "exam_template_id", nullable = false)
    private UUID examTemplateId;

    @Column(name = "exam_code", nullable = false, length = 40)
    private String examCode;

    @Column(name = "exam_version", nullable = false)
    private int examVersion;

    @Column(name = "rubric_version", nullable = false)
    private int rubricVersion;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "target_score", precision = 4, scale = 1)
    private BigDecimal targetScore;

    @Column(name = "turn_count", nullable = false)
    private short turnCount;

    @Column(name = "max_turns", nullable = false)
    private short maxTurns = 15;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AssessmentSession() {
    }

    public AssessmentSession(UUID userId, UUID examTemplateId, String examCode, int examVersion,
                             int rubricVersion, BigDecimal targetScore, short maxTurns) {
        this.userId = userId;
        this.examTemplateId = examTemplateId;
        this.examCode = examCode;
        this.examVersion = examVersion;
        this.rubricVersion = rubricVersion;
        this.status = CREATED;
        this.targetScore = targetScore;
        this.maxTurns = maxTurns;
        this.startedAt = Instant.now();
        this.expiresAt = Instant.now().plusSeconds(48 * 3600);
    }

    public void transition(String target) {
        if (!canTransition(target)) {
            throw new IllegalStateException("Illegal assessment transition " + status + " -> " + target);
        }
        this.status = target;
    }

    public boolean canTransition(String target) {
        return switch (status) {
            case CREATED -> target.equals(PREPARING) || target.equals(IN_PROGRESS) || target.equals(ABORTED);
            case PREPARING -> target.equals(IN_PROGRESS) || target.equals(ABORTED);
            case IN_PROGRESS -> target.equals(PROCESSING) || target.equals(ABORTED) || target.equals(FAILED);
            case PROCESSING -> target.equals(COMPLETED) || target.equals(FAILED);
            default -> false;
        };
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getExamTemplateId() {
        return examTemplateId;
    }

    public String getExamCode() {
        return examCode;
    }

    public int getExamVersion() {
        return examVersion;
    }

    public int getRubricVersion() {
        return rubricVersion;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getTargetScore() {
        return targetScore;
    }

    public short getTurnCount() {
        return turnCount;
    }

    public void incrementTurnCount() {
        this.turnCount++;
    }

    public short getMaxTurns() {
        return maxTurns;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markCompleted() {
        this.completedAt = Instant.now();
    }
}
