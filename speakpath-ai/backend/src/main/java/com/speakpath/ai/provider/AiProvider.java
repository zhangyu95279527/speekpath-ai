package com.speakpath.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.speakpath.ai.config.AiModelConfig;
import com.speakpath.ai.gateway.AiCapability;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 统一 Provider SPI（规格书 §6.2）：厂商协议转换为统一 AiResult。
 */
public interface AiProvider {

    String providerCode();

    Set<AiCapability> supportedCapabilities();

    boolean supports(AiModelConfig config);

    AiResult invoke(AiModelConfig config, AiRequest request);

    record AiRequest(
            AiCapability capability,
            String systemPrompt,
            Map<String, Object> input,
            JsonNode outputSchema,
            Duration timeout,
            String correlationId) {
    }

    record AiResult(
            String providerCode,
            String modelCode,
            JsonNode output,
            long latencyMs,
            Map<String, Object> usage,
            String finishReason) {
    }

    record Usage(Long inputTokens, Long outputTokens, BigDecimal estimatedCost, String currency) {
    }
}
