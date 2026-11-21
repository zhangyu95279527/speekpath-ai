package com.speakpath.identity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 版本化隐私授权记录：音频处理、AI 供应商、保留期限。
 */
@Entity
@Table(name = "consent_records")
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "consent_type", nullable = false, length = 40)
    private String consentType;

    @Column(nullable = false, length = 40)
    private String version;

    @Column(nullable = false)
    private boolean granted = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConsentRecord() {
    }

    public ConsentRecord(UUID userId, String consentType, String version, boolean granted, String payloadJson) {
        this.userId = userId;
        this.consentType = consentType;
        this.version = version;
        this.granted = granted;
        this.payloadJson = payloadJson;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getConsentType() {
        return consentType;
    }

    public boolean isGranted() {
        return granted;
    }
}
