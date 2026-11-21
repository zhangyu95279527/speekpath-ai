package com.speakpath.plan;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 训练内容库：微课、口语题、复述、词汇、语法训练；含难度/时长/先修标签。
 */
@Entity
@Table(name = "content_items")
public class ContentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "item_type", nullable = false, length = 40)
    private String itemType;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false)
    private short difficulty = 3;

    @Column(name = "duration_min", nullable = false)
    private short durationMin = 10;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String tags = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String prerequisites = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    private String contentJson = "{}";

    @Column(nullable = false, length = 20)
    private String status = "PUBLISHED";

    /** 练习材料生成状态：PENDING / GENERATING / READY / FAILED。 */
    @Column(name = "material_status", nullable = false, length = 20)
    private String materialStatus = "PENDING";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ContentItem() {
    }

    public UUID getId() {
        return id;
    }

    public String getItemType() {
        return itemType;
    }

    public String getTitle() {
        return title;
    }

    public short getDifficulty() {
        return difficulty;
    }

    public short getDurationMin() {
        return durationMin;
    }

    public String getTags() {
        return tags;
    }

    public String getContentJson() {
        return contentJson;
    }

    public void setContentJson(String contentJson) {
        this.contentJson = contentJson;
    }

    public String getStatus() {
        return status;
    }

    public String getMaterialStatus() {
        return materialStatus;
    }

    public void setMaterialStatus(String materialStatus) {
        this.materialStatus = materialStatus;
    }
}
