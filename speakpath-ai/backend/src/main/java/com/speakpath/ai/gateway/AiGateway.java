package com.speakpath.ai.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.ai.config.AiModelConfig;
import com.speakpath.ai.config.AiModelConfigService;
import com.speakpath.ai.provider.AiProvider;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import com.speakpath.common.api.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 统一入口：按能力路由 → 熔断检查 → 有限重试 → fallback（同能力）→ 成本/调用日志。
 */
@Service
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    private final AiModelConfigService configService;
    private final AiCallLogRepository callLogRepository;
    private final SimpleCircuitBreaker circuitBreaker;
    private final List<AiProvider> providers;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiGateway(AiModelConfigService configService, AiCallLogRepository callLogRepository,
                     SimpleCircuitBreaker circuitBreaker, List<AiProvider> providers) {
        this.configService = configService;
        this.callLogRepository = callLogRepository;
        this.circuitBreaker = circuitBreaker;
        this.providers = providers;
    }

    public record ModelInvocationContext(UUID userId, UUID sessionId, String region, String dataPolicy) {
    }

    public record Invocation(AiCapability capability, String systemPrompt, Map<String, Object> input,
                             JsonNode outputSchema, Duration timeout, String promptVersion) {
    }

    /**
     * 同步调用；所有 provider 失败时抛出 AI_PROVIDER_ERROR。
     */
    public AiProvider.AiResult invoke(Invocation invocation, ModelInvocationContext context) {
        List<AiModelConfig> candidates = configService.activeConfigs(invocation.capability());
        if (candidates.isEmpty()) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                    "No active model config for capability " + invocation.capability());
        }
        Exception last = null;
        for (AiModelConfig config : candidates) {
            if (context != null && config.getTrafficPercent() < 100) {
                int bucket = Math.floorMod(context.userId() == null ? 0 : context.userId().hashCode(), 100);
                if (bucket >= config.getTrafficPercent()) {
                    continue; // 灰度分流：该用户不在本配置流量内
                }
            }
            AiProvider provider = providerFor(config);
            if (provider == null) {
                record(config, invocation, context, null, "NO_PROVIDER", "unknown");
                last = new ApiException(ErrorCode.AI_PROVIDER_ERROR, "No adapter for provider " + config.getProviderCode());
                continue;
            }
            if (!provider.supports(config)) {
                continue;
            }
            if (circuitBreaker.isOpen(config.getCapability(), config.getProviderCode())) {
                record(config, invocation, context, null, "OPEN_CIRCUIT", "CircuitOpen");
                last = new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Circuit open for " + config.getProviderCode());
                continue;
            }
            try {
                AiProvider.AiResult result = provider.invoke(config, new AiProvider.AiRequest(
                        invocation.capability(), invocation.systemPrompt(), invocation.input(),
                        invocation.outputSchema(), invocation.timeout(),
                        TraceContext.traceId()));
                circuitBreaker.recordSuccess(config.getCapability(), config.getProviderCode());
                record(config, invocation, context, result, "SUCCESS", null);
                return result;
            } catch (Exception e) {
                circuitBreaker.recordFailure(config.getCapability(), config.getProviderCode());
                String errorClass = e.getClass().getSimpleName();
                record(config, invocation, context, null, "FAILED", errorClass);
                log.warn("AI invoke failed capability={} provider={} error={}",
                        config.getCapability(), config.getProviderCode(), errorClass);
                last = e;
            }
        }
        throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                "All providers failed for " + invocation.capability()
                        + (last == null ? "" : ": " + last.getMessage()));
    }

    /**
     * 先按 providerCode 精确匹配；否则交给各 adapter 的 supports() 判断，
     * 使 DEEPSEEK / DASHSCOPE / ZHIPU / MOONSHOT 等国内厂商复用 OpenAI 兼容 adapter。
     */
    private AiProvider providerFor(AiModelConfig config) {
        return providers.stream()
                .filter(p -> p.providerCode().equalsIgnoreCase(config.getProviderCode()))
                .findFirst()
                .orElseGet(() -> providers.stream()
                        .filter(p -> p.supports(config))
                        .findFirst()
                        .orElse(null));
    }

    private void record(AiModelConfig config, Invocation invocation, ModelInvocationContext context,
                        AiProvider.AiResult result, String status, String errorClass) {
        try {
            String usage = result == null ? "{}" : mapper.writeValueAsString(result.usage());
            AiCallLog callLog = new AiCallLog(
                    TraceContext.traceId(),
                    context == null ? null : context.userId(),
                    context == null ? null : context.sessionId(),
                    config.getCapability(), config.getProviderCode(), config.getModelCode(),
                    config.getVersion(), invocation.promptVersion(),
                    result == null ? null : (int) result.latencyMs(),
                    status, usage, errorClass);
            callLogRepository.save(callLog);
        } catch (Exception e) {
            log.warn("Failed to persist ai_call_log: {}", e.getMessage());
        }
    }
}
