package com.speakpath.common.idempotency;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String path;

    @Column(name = "idem_key", nullable = false, length = 120)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", nullable = false, columnDefinition = "jsonb")
    private String responseJson = "{}";

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(UUID userId, String path, String idemKey, String requestHash,
                             String responseJson, int statusCode, Instant expiresAt) {
        this.userId = userId;
        this.path = path;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.responseJson = responseJson;
        this.statusCode = statusCode;
        this.expiresAt = expiresAt;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
