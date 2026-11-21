package com.speakpath.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.ai.config.AiModelConfig;
import com.speakpath.ai.config.AiModelConfigService;
import com.speakpath.ai.config.SecretResolver;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import com.speakpath.ai.provider.AiProvider;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import com.speakpath.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * 模型配置管理：draft/test/activate/rollback，全程审计。
 * 配置不可携带明文 secret；baseUrl 做 SSRF 防护。
 */
@Service
public class AiModelAdminService {

    private static final Logger log = LoggerFactory.getLogger(AiModelAdminService.class);
    private static final Set<String> STATUSES = Set.of("DRAFT", "TESTED", "ACTIVE", "ROLLED_BACK", "DISABLED");

    private final AiModelConfigService configService;
    private final SecretResolver secretResolver;
    private final AiGateway aiGateway;
    private final AuditService auditService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiModelAdminService(AiModelConfigService configService, SecretResolver secretResolver,
                               AiGateway aiGateway, AuditService auditService) {
        this.configService = configService;
        this.secretResolver = secretResolver;
        this.aiGateway = aiGateway;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return configService.all().stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        return dto(configService.findById(id).orElseThrow(() -> ApiException.notFound("AI model config")));
    }

    /**
     * 创建草稿配置：不保存明文 secret，只保存 secretRef。
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String capability = str(body.get("capability"));
        validateCapability(capability);
        String providerCode = str(body.get("providerCode"));
        String modelCode = str(body.get("modelCode"));
        if (providerCode == null || modelCode == null) {
            throw ApiException.invalid("providerCode and modelCode are required", "providerCode", "required");
        }
        String baseUrl = str(body.get("baseUrl"));
        if (baseUrl != null && !baseUrl.isBlank()) {
            validateSsrfsafe(baseUrl);
        }
        String secretRef = str(body.get("secretRef"));
        if (secretRef != null && !secretRef.startsWith("env:")) {
            throw ApiException.invalid("secretRef must reference a registered secret", "secretRef", "use env: prefix");
        }

        AiModelConfig config = new AiModelConfig();
        config.setCapability(capability);
        config.setProviderCode(providerCode);
        config.setModelCode(modelCode);
        config.setBaseUrl(baseUrl);
        config.setSecretRef(secretRef);
        config.setPriority(body.get("priority") instanceof Number n ? n.intValue() : 100);
        config.setEnabled(false);
        config.setTimeoutMs(body.get("timeoutMs") instanceof Number n ? n.intValue() : 10000);
        config.setMaxRetries(body.get("maxRetries") instanceof Number n ? n.shortValue() : (short) 1);
        config.setParametersJson(json(body.getOrDefault("parameters", Map.of())));
        config.setRegion(str(body.get("region")));
        config.setDataPolicy(str(body.get("dataPolicy")) == null ? "STANDARD" : str(body.get("dataPolicy")));
        config.setTrafficPercent(body.get("trafficPercent") instanceof Number n ? n.shortValue() : (short) 100);
        config.setVersion(nextVersion(capability));
        config.setStatus("DRAFT");
        configService.save(config);

        auditService.record("AI_MODEL_CREATE", "ai_model_config", config.getId().toString(),
                null, dto(config), null);
        return dto(config);
    }

    /**
     * 试连：MOCK 直接通过；真实配置验证 schema、延迟与 secret 可用性。
     * 测试使用受控 prompt，不发送敏感用户数据。
     * 注意：AI 调用会写入 ai_call_logs，因此不能使用只读事务。
     */
    @Transactional
    public Map<String, Object> test(UUID id) {
        AiModelConfig config = configService.findById(id).orElseThrow(() -> ApiException.notFound("AI model config"));
        long start = System.currentTimeMillis();
        try {
            if ("MOCK".equalsIgnoreCase(config.getProviderCode())) {
                return Map.of("passed", true, "latencyMs", System.currentTimeMillis() - start,
                        "schemaValid", true, "note", "mock provider always passes");
            }
            if (config.getBaseUrl() != null) {
                validateSsrfsafe(config.getBaseUrl());
            }
            if (config.getSecretRef() != null && !secretResolver.hasSecret(config.getSecretRef())) {
                return Map.of("passed", false, "latencyMs", System.currentTimeMillis() - start,
                        "schemaValid", false, "error", "secret not configured");
            }
            JsonNode result = aiGateway.invoke(
                    new AiGateway.Invocation(AiCapability.valueOf(config.getCapability()),
                            "This is a controlled connectivity test. Reply with the word OK.",
                            Map.of("test", true), null, Duration.ofSeconds(15), "admin-test"),
                    new AiGateway.ModelInvocationContext(null, null, null, "STANDARD")).output();
            return Map.of("passed", true, "latencyMs", System.currentTimeMillis() - start,
                    "schemaValid", result != null);
        } catch (Exception e) {
            return Map.of("passed", false, "latencyMs", System.currentTimeMillis() - start,
                    "schemaValid", false, "error", e.getMessage());
        }
    }

    /**
     * 激活：同能力旧 ACTIVE 配置回滚下线，新配置启用；支持灰度百分比。
     */
    @Transactional
    public Map<String, Object> activate(UUID id, String reason) {
        AiModelConfig config = configService.findById(id).orElseThrow(() -> ApiException.notFound("AI model config"));
        if (config.getTrafficPercent() > 0 && config.getSecretRef() != null
                && !secretResolver.hasSecret(config.getSecretRef())) {
            throw ApiException.invalid("Cannot activate: secret not configured", "secretRef", config.getSecretRef());
        }
        Map<String, Object> before = dto(config);
        configService.deactivateOthers(AiCapability.valueOf(config.getCapability()), config.getId());
        config.setEnabled(true);
        config.setStatus("ACTIVE");
        config.touch();
        configService.save(config);
        auditService.record("AI_MODEL_ACTIVATE", "ai_model_config", id.toString(), before, dto(config), reason);
        return dto(config);
    }

    /**
     * 回滚：一键回滚到上一个 ACTIVE/ROLLED_BACK 版本（按 version 最大者）。
     */
    @Transactional
    public Map<String, Object> rollback(UUID id, String reason) {
        AiModelConfig config = configService.findById(id).orElseThrow(() -> ApiException.notFound("AI model config"));
        AiCapability capability = AiCapability.valueOf(config.getCapability());
        AiModelConfig previous = configService.allForCapability(capability).stream()
                .filter(c -> !c.getId().equals(id) && ("ACTIVE".equals(c.getStatus()) || "ROLLED_BACK".equals(c.getStatus())))
                .max(Comparator.comparingInt(AiModelConfig::getVersion))
                .orElse(null);
        Map<String, Object> before = dto(config);
        config.setEnabled(false);
        config.setStatus("ROLLED_BACK");
        configService.save(config);
        if (previous != null) {
            previous.setEnabled(true);
            previous.setStatus("ACTIVE");
            configService.save(previous);
        }
        auditService.record("AI_MODEL_ROLLBACK", "ai_model_config", id.toString(), before, dto(config), reason);
        return dto(config);
    }

    @Transactional
    public Map<String, Object> disable(UUID id, String reason) {
        AiModelConfig config = configService.findById(id).orElseThrow(() -> ApiException.notFound("AI model config"));
        config.setEnabled(false);
        config.setStatus("DISABLED");
        configService.save(config);
        auditService.record("AI_MODEL_DISABLE", "ai_model_config", id.toString(), null, dto(config), reason);
        return dto(config);
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> auditLogs() {
        return auditService.recent().stream().map(a -> Map.of(
                "actorId", a.getActorId().toString(),
                "action", a.getAction(),
                "targetType", a.getTargetType(),
                "targetId", a.getTargetId(),
                "reason", a.getReason() == null ? "" : a.getReason(),
                "createdAt", a.getCreatedAt().toString())).toList();
    }

    private Map<String, Object> dto(AiModelConfig config) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("configId", config.getId().toString());
        dto.put("capability", config.getCapability());
        dto.put("providerCode", config.getProviderCode());
        dto.put("modelCode", config.getModelCode());
        dto.put("baseUrl", config.getBaseUrl());
        // 只返回 secretRef 状态，绝不返回 secret 本身（TC-AI-004）
        dto.put("secretRef", config.getSecretRef());
        dto.put("secretConfigured", config.getSecretRef() != null && secretResolver.hasSecret(config.getSecretRef()));
        dto.put("priority", config.getPriority());
        dto.put("enabled", config.isEnabled());
        dto.put("timeoutMs", config.getTimeoutMs());
        dto.put("maxRetries", (int) config.getMaxRetries());
        dto.put("region", config.getRegion());
        dto.put("dataPolicy", config.getDataPolicy());
        dto.put("trafficPercent", (int) config.getTrafficPercent());
        dto.put("version", config.getVersion());
        dto.put("status", config.getStatus());
        dto.put("parameters", read(config.getParametersJson()));
        return dto;
    }

    private int nextVersion(String capability) {
        return configService.allForCapability(AiCapability.valueOf(capability)).stream()
                .mapToInt(AiModelConfig::getVersion).max().orElse(0) + 1;
    }

    private void validateCapability(String capability) {
        try {
            AiCapability.valueOf(capability);
        } catch (Exception e) {
            throw ApiException.invalid("Unknown capability", "capability", capability);
        }
    }

    /**
     * SSRF 防护（TC-AI-005）：拒绝内网/环回/链路本地地址。
     */
    private void validateSsrfsafe(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (host == null) {
                throw ApiException.invalid("Invalid baseUrl", "baseUrl", baseUrl);
            }
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()) {
                throw ApiException.invalid("baseUrl resolves to a private address", "baseUrl", host);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.invalid("Invalid baseUrl", "baseUrl", baseUrl);
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
