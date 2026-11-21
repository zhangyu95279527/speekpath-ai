package com.speakpath.plan;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 计划版本：每次生成/重排保存版本、原因与差异，历史只读。
 */
@Entity
@Table(name = "plan_versions")
public class PlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_json", nullable = false, columnDefinition = "jsonb")
    private String planJson;

    @Column(name = "change_reason", nullable = false, length = 80)
    private String changeReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_json", nullable = false, columnDefinition = "jsonb")
    private String diffJson = "{}";

    @Column(name = "created_by", nullable = false, length = 20)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PlanVersion() {
    }

    public PlanVersion(UUID planId, int version, String planJson, String changeReason, String diffJson, String createdBy) {
        this.planId = planId;
        this.version = version;
        this.planJson = planJson;
        this.changeReason = changeReason;
        this.diffJson = diffJson;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public int getVersion() {
        return version;
    }

    public String getPlanJson() {
        return planJson;
    }

    public String getChangeReason() {
        return changeReason;
    }
}
