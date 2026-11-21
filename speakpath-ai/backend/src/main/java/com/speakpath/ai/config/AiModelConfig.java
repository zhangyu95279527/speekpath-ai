package com.speakpath.ai.config;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 模型配置：按能力独立配置，支持优先级、回退、灰度、版本与状态机。
 */
@Entity
@Table(name = "ai_model_configs")
public class AiModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 32)
    private String capability;

    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode;

    @Column(name = "model_code", nullable = false, length = 120)
    private String modelCode;

    @Column(name = "base_url", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String baseUrl;

    @Column(name = "secret_ref", length = 255)
    private String secretRef;

    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 10000;

    @Column(name = "max_retries", nullable = false)
    private short maxRetries = 1;

    @Column(name = "fallback_config_id")
    private UUID fallbackConfigId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters_json", nullable = false, columnDefinition = "jsonb")
    private String parametersJson = "{}";

    @Column(length = 32)
    private String region;

    @Column(name = "data_policy", nullable = false, length = 32)
    private String dataPolicy = "STANDARD";

    @Column(name = "traffic_percent", nullable = false)
    private short trafficPercent = 100;

    @Column(nullable = false)
    private int version = 1;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AiModelConfig() {
    }

    public UUID getId() {
        return id;
    }

    public String getCapability() {
        return capability;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getModelCode() {
        return modelCode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public short getMaxRetries() {
        return maxRetries;
    }

    public UUID getFallbackConfigId() {
        return fallbackConfigId;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public String getRegion() {
        return region;
    }

    public String getDataPolicy() {
        return dataPolicy;
    }

    public short getTrafficPercent() {
        return trafficPercent;
    }

    public int getVersion() {
        return version;
    }

    public String getStatus() {
        return status;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setSecretRef(String secretRef) {
        this.secretRef = secretRef;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public void setMaxRetries(short maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void setFallbackConfigId(UUID fallbackConfigId) {
        this.fallbackConfigId = fallbackConfigId;
    }

    public void setParametersJson(String parametersJson) {
        this.parametersJson = parametersJson;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public void setDataPolicy(String dataPolicy) {
        this.dataPolicy = dataPolicy;
    }

    public void setTrafficPercent(short trafficPercent) {
        this.trafficPercent = trafficPercent;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
