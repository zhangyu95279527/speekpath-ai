package com.speakpath.assessment;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 测评轮次：题目快照保证历史可复现；音频只存对象 key。
 */
@Entity
@Table(name = "assessment_turns")
public class AssessmentTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "sequence_no", nullable = false)
    private short sequenceNo;

    @Column(name = "question_id")
    private UUID questionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question_snapshot", nullable = false, columnDefinition = "jsonb")
    private String questionSnapshot;

    @Column(name = "answer_text", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String answerText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transcript_json", columnDefinition = "jsonb")
    private String transcriptJson;

    @Column(name = "audio_object_key", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String audioObjectKey;

    @Column(name = "audio_duration_ms")
    private Integer audioDurationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audio_quality_json", columnDefinition = "jsonb")
    private String audioQualityJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_json", columnDefinition = "jsonb")
    private String analysisJson;

    @Column(nullable = false, length = 20)
    private String status = "SUBMITTED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AssessmentTurn() {
    }

    public AssessmentTurn(UUID sessionId, short sequenceNo, UUID questionId, String questionSnapshot,
                          String answerText, String audioObjectKey, Integer audioDurationMs) {
        this.sessionId = sessionId;
        this.sequenceNo = sequenceNo;
        this.questionId = questionId;
        this.questionSnapshot = questionSnapshot;
        this.answerText = answerText;
        this.audioObjectKey = audioObjectKey;
        this.audioDurationMs = audioDurationMs;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public short getSequenceNo() {
        return sequenceNo;
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public String getQuestionSnapshot() {
        return questionSnapshot;
    }

    public String getAnswerText() {
        return answerText;
    }

    public String getTranscriptJson() {
        return transcriptJson;
    }

    public String getAudioObjectKey() {
        return audioObjectKey;
    }

    public Integer getAudioDurationMs() {
        return audioDurationMs;
    }

    public String getAudioQualityJson() {
        return audioQualityJson;
    }

    public String getAnalysisJson() {
        return analysisJson;
    }

    public String getStatus() {
        return status;
    }

    public void setTranscriptJson(String transcriptJson) {
        this.transcriptJson = transcriptJson;
    }

    public void setAudioQualityJson(String audioQualityJson) {
        this.audioQualityJson = audioQualityJson;
    }

    public void setAnalysisJson(String analysisJson) {
        this.analysisJson = analysisJson;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
