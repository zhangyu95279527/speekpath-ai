package com.speakpath.ai.config;

import com.speakpath.ai.gateway.AiCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 启动时按已配置的密钥自动启用国内模型，无需手工在后台逐个激活。
 * 国内配置优先级高于 MOCK，失败时仍会回退到 MOCK，保证无密钥/异常时服务可用。
 */
@Component
public class AiProviderBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AiProviderBootstrap.class);

    private static final Set<String> CN_PROVIDERS = Set.of(
            "DEEPSEEK", "DASHSCOPE", "ZHIPU", "MOONSHOT", "ARK", "MINIMAX");

    private static final Set<AiCapability> TEXT_CAPABILITIES = EnumSet.of(
            AiCapability.ASSESSMENT, AiCapability.PLANNING,
            AiCapability.CORRECTION, AiCapability.TUTOR);

    private final AiModelConfigService configService;
    private final SecretResolver secretResolver;
    private final AiLlmProperties llmProperties;

    public AiProviderBootstrap(AiModelConfigService configService, SecretResolver secretResolver,
                               AiLlmProperties llmProperties) {
        this.configService = configService;
        this.secretResolver = secretResolver;
        this.llmProperties = llmProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void enableConfiguredChineseModels() {
        // LLM 已通过 app.ai.llm 配置指定厂商/模型时，无需再按密钥自动启用 DB 配置。
        if (llmProperties.isConfigured()) {
            log.info("app.ai.llm.provider 已配置（{}），跳过 DB 模型自动启用", llmProperties.getProvider());
            return;
        }
        for (AiCapability capability : TEXT_CAPABILITIES) {
            List<AiModelConfig> configs = configService.allForCapability(capability);
            // 密钥被移除时回收国内模型，避免每次请求都先打一次必然失败的厂商调用
            configs.stream()
                    .filter(c -> CN_PROVIDERS.contains(c.getProviderCode().toUpperCase(Locale.ROOT)))
                    .filter(c -> !"DISABLED".equals(c.getStatus()))
                    .filter(c -> !secretResolver.hasSecret(c.getSecretRef()))
                    .filter(c -> c.isEnabled() || "ACTIVE".equals(c.getStatus()))
                    .forEach(config -> {
                        config.setEnabled(false);
                        config.setStatus("DRAFT");
                        config.touch();
                        configService.save(config);
                        log.warn("AI 能力 {} 的国内模型 {} 缺少密钥，已回收；继续使用 MOCK 兜底",
                                capability, config.getProviderCode());
                    });

            configs.stream()
                    .filter(c -> CN_PROVIDERS.contains(c.getProviderCode().toUpperCase(Locale.ROOT)))
                    .filter(c -> !"DISABLED".equals(c.getStatus()))
                    .filter(c -> secretResolver.hasSecret(c.getSecretRef()))
                    .sorted(Comparator.comparingInt(AiModelConfig::getPriority))
                    .forEach(config -> {
                        if (config.isEnabled() && "ACTIVE".equals(config.getStatus())) {
                            return;
                        }
                        config.setEnabled(true);
                        config.setStatus("ACTIVE");
                        config.touch();
                        configService.save(config);
                        log.info("AI 能力 {} 已切换到国内模型 {} / {}（优先级 {}）",
                                capability, config.getProviderCode(), config.getModelCode(), config.getPriority());
                    });
        }
    }
}
