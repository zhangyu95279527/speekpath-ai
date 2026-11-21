package com.speakpath.ai.config;

import com.speakpath.ai.gateway.AiCapability;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 模型配置查询服务：按能力返回启用中的配置（优先级排序）。
 */
@Service
public class AiModelConfigService {

    private final AiModelConfigRepository repository;
    private final AiLlmProperties llmProperties;

    public AiModelConfigService(AiModelConfigRepository repository, AiLlmProperties llmProperties) {
        this.repository = repository;
        this.llmProperties = llmProperties;
    }

    @Transactional(readOnly = true)
    public List<AiModelConfig> activeConfigs(AiCapability capability) {
        if (llmProperties.isConfigured()) {
            return List.of(fromLlmConfig(capability));
        }
        return repository.findByCapabilityAndEnabledTrueAndStatusOrderByPriorityAsc(
                capability.name(), "ACTIVE");
    }

    @Transactional(readOnly = true)
    public Optional<AiModelConfig> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<AiModelConfig> allForCapability(AiCapability capability) {
        return repository.findByCapabilityOrderByPriorityAsc(capability.name());
    }

    @Transactional(readOnly = true)
    public List<AiModelConfig> all() {
        return repository.findAll();
    }

    @Transactional
    public AiModelConfig save(AiModelConfig config) {
        return repository.save(config);
    }

    @Transactional
    public void deactivateOthers(AiCapability capability, UUID exceptId) {
        for (AiModelConfig other : repository.findByCapabilityOrderByPriorityAsc(capability.name())) {
            if (!other.getId().equals(exceptId) && "ACTIVE".equals(other.getStatus())) {
                other.setStatus("ROLLED_BACK");
                other.setEnabled(false);
                repository.save(other);
            }
        }
    }

    private AiModelConfig fromLlmConfig(AiCapability capability) {
        String provider = llmProperties.getProvider().trim().toUpperCase();
        AiModelConfig config = new AiModelConfig();
        config.setCapability(capability.name());
        config.setProviderCode(provider);
        config.setModelCode(firstNonBlank(llmProperties.getModel(), defaultModel(provider)));
        config.setBaseUrl(firstNonBlank(llmProperties.getBaseUrl(), defaultBaseUrl(provider)));
        config.setSecretRef("env:" + provider.toLowerCase() + "-api-key");
        config.setParametersJson(defaultParameters(capability));
        config.setTimeoutMs(30000);
        config.setMaxRetries((short) 1);
        config.setTrafficPercent((short) 100);
        config.setPriority(1);
        config.setVersion(1);
        config.setStatus("ACTIVE");
        config.setDataPolicy("STANDARD");
        config.setRegion("cn");
        return config;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String defaultModel(String provider) {
        return switch (provider) {
            case "DEEPSEEK" -> "deepseek-flash";
            case "DASHSCOPE" -> "qwen-plus";
            case "ZHIPU" -> "glm-4-plus";
            case "MOONSHOT" -> "moonshot-v1-8k";
            default -> "";
        };
    }

    private static String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "DEEPSEEK" -> "https://api.deepseek.com/v1";
            case "DASHSCOPE" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "ZHIPU" -> "https://open.bigmodel.cn/api/paas/v4";
            case "MOONSHOT" -> "https://api.moonshot.cn/v1";
            default -> "";
        };
    }

    private static String defaultParameters(AiCapability capability) {
        return switch (capability) {
            case ASSESSMENT -> "{\"temperature\":0.2,\"maxTokens\":2500}";
            case PLANNING -> "{\"temperature\":0.3,\"maxTokens\":2500}";
            case CORRECTION -> "{\"temperature\":0.1,\"maxTokens\":1500}";
            case TUTOR -> "{\"temperature\":0.6,\"maxTokens\":1200}";
            default -> "{}";
        };
    }
}
