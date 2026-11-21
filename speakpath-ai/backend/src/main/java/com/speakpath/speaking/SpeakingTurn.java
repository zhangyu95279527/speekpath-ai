package com.speakpath.speaking;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "speaking_turns")
public class SpeakingTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String transcript;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transcript_json", columnDefinition = "jsonb")
    private String transcriptJson;

    @Column(name = "audio_object_key", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String audioObjectKey;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SpeakingTurn() {
    }

    public SpeakingTurn(UUID sessionId, int sequenceNo, String role, String transcript,
                        String audioObjectKey, Integer durationMs, String metadataJson) {
        this.sessionId = sessionId;
        this.sequenceNo = sequenceNo;
        this.role = role;
        this.transcript = transcript;
        this.audioObjectKey = audioObjectKey;
        this.durationMs = durationMs;
        this.metadataJson = metadataJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(int sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getRole() {
        return role;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getTranscriptJson() {
        return transcriptJson;
    }

    public String getAudioObjectKey() {
        return audioObjectKey;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}
