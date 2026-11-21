package com.speakpath.identity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户设备会话：refresh token 只存哈希，支持设备级撤销与重放检测。
 */
@Entity
@Table(name = "user_devices")
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String platform;

    @Column(name = "device_name", length = 120)
    private String deviceName;

    @Column(name = "refresh_token_hash", nullable = false, length = 255)
    private String refreshTokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected UserDevice() {
    }

    public UserDevice(UUID userId, String platform, String deviceName, String refreshTokenHash, Instant expiresAt) {
        this.userId = userId;
        this.platform = platform;
        this.deviceName = deviceName;
        this.refreshTokenHash = refreshTokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getRefreshTokenHash() {
        return refreshTokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }
}
