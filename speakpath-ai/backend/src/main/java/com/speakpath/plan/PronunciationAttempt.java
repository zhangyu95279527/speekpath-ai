package com.speakpath.plan;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 跟读评测记录：同一学习内容的多次评测历史（任务维度，按句子/词下标区分）。
 */
@Entity
@Table(name = "pronunciation_attempts")
public class PronunciationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "item_index", nullable = false)
    private short itemIndex;

    @Column(name = "ref_text", nullable = false)
    private String refText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private String resultJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggestions_json", nullable = false, columnDefinition = "jsonb")
    private String suggestionsJson = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PronunciationAttempt() {
    }

    public PronunciationAttempt(UUID userId, UUID taskId, short itemIndex, String refText,
                                String resultJson, String suggestionsJson) {
        this.userId = userId;
        this.taskId = taskId;
        this.itemIndex = itemIndex;
        this.refText = refText;
        this.resultJson = resultJson;
        this.suggestionsJson = suggestionsJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public short getItemIndex() {
        return itemIndex;
    }

    public String getRefText() {
        return refText;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getSuggestionsJson() {
        return suggestionsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
